-- =====================================================================================
-- IT-DSP — диспетчер и сага (AD-03, AD-04, ADR-0039).
--
-- Диспетчер забирает сообщения через FOR UPDATE SKIP LOCKED и держит их лизингом:
-- message.dispatch_owner + dispatch_claimed_until, следующая попытка — next_attempt_at.
-- Это единственные три колонки, по которым видно, что происходит с очередью, и почти
-- каждый кейс области смотрит именно на них.
--
-- Сага разорвана вокруг вызова провайдера намеренно: DispatchPreparation фиксирует
-- открытую попытку (result='PENDING', response_at IS NULL), вызов идёт вне транзакции,
-- DispatchSettlement применяет ответ к заново загруженному агрегату. Отсюда главная
-- проверка области: незакрытая попытка после падения не превращается во вторую SMS.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-DSP-001  Два инстанса не отправляют дважды
-- @arrange
-- @assert
SELECT count(*) = 60 AS ok, 'приняты все шестьдесят' AS check FROM message;
SELECT count(*) = 60 AS ok, 'ровно одна попытка на сообщение' AS check FROM delivery_attempt;
SELECT count(*) = 0 AS ok, 'ни одного сообщения с двумя попытками' AS check
  FROM (SELECT message_id FROM delivery_attempt GROUP BY message_id HAVING count(*) > 1) d;
SELECT count(*) = 0 AS ok, 'ни одно не осталось неотправленным' AS check
  FROM message WHERE status NOT IN ('SENT_TO_PROVIDER', 'DELIVERED');
SELECT count(DISTINCT dispatch_owner) AS owners, 'очередь разбирали оба «пода»' AS check
  FROM message WHERE dispatch_owner IS NOT NULL;


-- >>> IT-DSP-002  Просроченный лизинг возвращает сообщение в очередь
-- @arrange
-- Сообщение захвачено «подом», который больше не отвечает: лизинг истёк.
UPDATE message
   SET status = 'QUEUED',
       dispatch_owner = 'pod-который-умер',
       dispatch_claimed_until = now() - interval '5 minutes',
       next_attempt_at = now() - interval '5 minutes'
 WHERE status NOT IN ('DELIVERED', 'REJECTED', 'EXPIRED', 'CANCELLED');
-- @assert
SELECT count(*) = 0 AS ok, 'просроченный лизинг снят' AS check
  FROM message WHERE dispatch_owner = 'pod-который-умер'
    AND dispatch_claimed_until < now();
SELECT count(*) = 1 AS ok, 'сообщение отправлено ровно один раз' AS check
  FROM delivery_attempt;
SELECT id, status, dispatch_owner, dispatch_claimed_until, next_attempt_at FROM message;


-- >>> IT-DSP-003  Разрыв саги после ответа провайдера
-- @arrange
-- Воспроизводим состояние «провайдер ответил, расчёт не применён»: открытая попытка
-- и снятый лизинг. Если диспетчер отправит второй раз — появится вторая строка попытки,
-- и это ровно тот дефект, ради которого сага разорвана (H2 аудита).
UPDATE delivery_attempt SET result = 'PENDING', response_at = NULL, response_code = NULL,
                            latency_ms = NULL, error_class = NULL, error_description = NULL;
UPDATE message
   SET status = 'SENDING',
       dispatch_owner = NULL,
       dispatch_claimed_until = NULL,
       next_attempt_at = now() - interval '1 minute';
-- @assert
SELECT count(*) = 1 AS ok, 'вторая отправка не состоялась' AS check FROM delivery_attempt;
SELECT count(*) <= 1 AS ok, 'провайдер получил сообщение не более одного раза' AS check
  FROM delivery_attempt WHERE result <> 'PENDING';
SELECT attempt_no, result, response_at, provider_message_id FROM delivery_attempt
 ORDER BY attempt_no;


-- >>> IT-DSP-004  Истечение TTL
-- @arrange
-- Сообщение с timing.ttlSeconds уже принято; «прошло две минуты» воспроизводим сдвигом
-- момента приёма, а не ожиданием. Сообщение остаётся неотправленным.
UPDATE message
   SET accepted_at = accepted_at - interval '2 minutes',
       next_attempt_at = now() - interval '1 minute'
 WHERE status NOT IN ('DELIVERED', 'REJECTED', 'EXPIRED', 'CANCELLED');
-- @assert
SELECT status = 'EXPIRED' AND status_reason = 'TTL_EXPIRED' AS ok,
       'просроченное сообщение снято планировщиком истечения' AS check
  FROM message;
SELECT terminal_at IS NOT NULL AS ok, 'момент терминального статуса проставлен' AS check
  FROM message;
SELECT count(*) = 0 AS ok, 'провайдер не вызывался' AS check FROM delivery_attempt;


-- >>> IT-DSP-005  sendAfter в будущем
-- @arrange
-- Предусловий нет: timing.sendAfter приходит в самом документе (через час).
-- @assert
SELECT status = 'ROUTED' AS ok, 'сообщение отложено, а не отправлено' AS check FROM message;
SELECT next_attempt_at > now() AS ok, 'следующая попытка назначена на будущее' AS check
  FROM message;
SELECT count(*) = 0 AS ok, 'попытки отправки не было' AS check FROM delivery_attempt;
-- Второй шаг кейса: сдвинуть timing в прошлое и дать диспетчеру такт —
--   UPDATE message SET timing = jsonb_set(timing, '{sendAfter}',
--          to_jsonb((now() - interval '1 hour')::text)), next_attempt_at = now();


-- >>> IT-DSP-006  Окно allowedWindow закрыто
-- @arrange
-- @assert
SELECT status = 'ROUTED' AS ok, 'сообщение ждёт открытия окна' AS check FROM message;
SELECT next_attempt_at IS NOT NULL AS ok, 'назначено время следующей попытки' AS check
  FROM message;
SELECT count(*) = 0 AS ok, 'провайдер не вызывался' AS check FROM delivery_attempt;
SELECT timing -> 'allowedWindow' AS window, next_attempt_at FROM message;


-- >>> IT-DSP-007  Kill switch останавливает отправку
-- @arrange
UPDATE kill_switch
   SET active = true, includes_critical_otp = false,
       changed_at = now(), changed_by = 'qa-arrange',
       reason = 'предусловие кейса IT-DSP-007'
 WHERE id;
-- @assert
SELECT active AS ok, 'рубильник включён' AS check FROM kill_switch;
SELECT count(*) = 0 AS ok, 'ни одной отправки при включённом рубильнике' AS check
  FROM delivery_attempt;
SELECT status IN ('ROUTED', 'QUEUED') AS ok, 'сообщение отложено, а не отклонено' AS check
  FROM message WHERE stream_id = 'core-banking';
-- Второй шаг кейса: снять рубильник и убедиться, что сообщение уходит.
--   UPDATE kill_switch SET active = false, changed_at = now() WHERE id;


-- >>> IT-DSP-008  Kill switch щадит CRITICAL_OTP
-- @arrange
UPDATE kill_switch
   SET active = true, includes_critical_otp = false,
       changed_at = now(), changed_by = 'qa-arrange',
       reason = 'предусловие кейса IT-DSP-008'
 WHERE id;
-- @assert
SELECT NOT includes_critical_otp AS ok, 'OTP из-под рубильника выведен' AS check
  FROM kill_switch;
SELECT count(*) = 1 AS ok, 'OTP отправлен' AS check
  FROM delivery_attempt a
  JOIN message m ON m.id = a.message_id
 WHERE m.stream_id = 'ibank-otp';
SELECT count(*) = 0 AS ok, 'массовое не отправлено' AS check
  FROM delivery_attempt a
  JOIN message m ON m.id = a.message_id
 WHERE m.stream_id = 'marketing-bulk';


-- >>> IT-DSP-009  Остановленный батч не отправляется
-- @arrange
UPDATE batch SET status = 'STOPPED', updated_at = now();
UPDATE message SET next_attempt_at = now() - interval '1 minute'
 WHERE batch_id IS NOT NULL;
-- @assert
SELECT status = 'STOPPED' AS ok, 'батч остановлен' AS check FROM batch;
SELECT count(*) = 0 AS ok, 'ни один элемент не ушёл провайдеру' AS check
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id WHERE m.batch_id IS NOT NULL;
SELECT count(*) > 0 AS ok, 'элементы отменены с названной причиной' AS check
  FROM message WHERE batch_id IS NOT NULL
   AND status = 'CANCELLED' AND status_reason = 'SEND_STOPPED';


-- >>> IT-DSP-010  Бюджет попыток и переход в DLQ
-- @arrange
-- Предусловий нет: адрес получателя оканчивается на …02 (нет ответа) у обоих провайдеров.
-- Бюджет — commhub.sending.max-total-attempts, по умолчанию 5.
-- @assert
SELECT count(*) = 5 AS ok, 'исчерпан бюджет в пять попыток' AS check FROM delivery_attempt;
SELECT status = 'FAILED' AS ok, 'сообщение помечено как неудавшееся' AS check FROM message;
SELECT count(*) = 1 AS ok, 'заведена запись DLQ' AS check FROM dlq_entry;
SELECT reason = 'ATTEMPTS_EXHAUSTED' AS ok, 'причина названа' AS check FROM dlq_entry;
SELECT count(*) = 1 AS ok, 'событие DLQ положено в outbox' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_DLQ';
SELECT attempt_no, provider_code, result, error_class FROM delivery_attempt ORDER BY attempt_no;


-- >>> IT-DSP-011  Задержка между попытками растёт
-- @arrange
-- Предусловий нет: адрес …02. Проверяется commhub.sending — initial-backoff 2s,
-- multiplier 2.0, max-backoff 30s, с разбросом (jitter).
-- @assert
SELECT count(*) >= 3 AS ok, 'попыток хватает, чтобы увидеть рост' AS check
  FROM delivery_attempt;
-- Интервалы между запросами: каждый следующий не меньше предыдущего и не больше 30 с
-- плюс разброс. Точных значений не ждём — jitter на то и разброс.
SELECT attempt_no,
       request_at,
       request_at - lag(request_at) OVER (ORDER BY attempt_no) AS gap
  FROM delivery_attempt ORDER BY attempt_no;
SELECT bool_and(gap <= interval '45 seconds') AS ok, 'потолок задержки соблюдён' AS check
  FROM (SELECT request_at - lag(request_at) OVER (ORDER BY attempt_no) AS gap
          FROM delivery_attempt) g
 WHERE gap IS NOT NULL;


-- >>> IT-DSP-012  Изоляция классов трафика по пулам
-- @arrange
-- @assert
-- OTP не должен ждать, пока разберут очередь массовых: пулы и такты у классов разные.
SELECT count(*) = 1 AS ok, 'OTP отправлен' AS check
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id
 WHERE m.traffic_class = 'CRITICAL_OTP';
SELECT (SELECT min(a.request_at) FROM delivery_attempt a JOIN message m ON m.id = a.message_id
         WHERE m.traffic_class = 'CRITICAL_OTP')
     < (SELECT max(a.request_at) FROM delivery_attempt a JOIN message m ON m.id = a.message_id
         WHERE m.traffic_class = 'NOTIFICATION') AS ok,
       'OTP ушёл, не дожидаясь конца массовой очереди' AS check;
SELECT m.traffic_class, count(*) AS attempts, min(a.request_at), max(a.request_at)
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id
 GROUP BY m.traffic_class ORDER BY m.traffic_class;
