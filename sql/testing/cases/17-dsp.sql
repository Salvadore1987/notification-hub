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
--
-- ДВА ПРАВИЛА ЭТОЙ ОБЛАСТИ, без которых кейс красит стенд, а не Модуль (прогон 14.08.2026).
--
-- 1. ПОРЯДОК. Скрипт исполняет @arrange ДО действия, а почти всякое предусловие здесь
--    правит уже принятое сообщение — которого после 00-reset.sql ещё нет. Значит порядок
--    у 002, 003, 004, 007, 008, 009 обратный: сначала подать сообщение, потом @arrange,
--    потом дать диспетчеру такт. Запускать их через `--arrange-only`/`--assert-only`
--    нельзя: между ними должен встать не только запрос, но и блок предусловий.
--
-- 2. ДЕРЖАТЕЛЬ. Стенд отправляет принятое сообщение за 0,2–1 с (такт класса трафика),
--    поэтому «сообщение ждёт в очереди» руками не поймать — его нужно ЗАДЕРЖАТЬ на приёме:
--    timing.sendAfter в будущем, окно тишины DEFER вокруг «сейчас» или приостановленный
--    батч. Кейс, которому нужна очередь, задерживает сообщение и снимает задержку сам.
--
-- И один формат: timing.sendAfter/sendBefore читаются Instant.parse (TimingJson), то есть
-- ISO-8601 с «T» и «Z». Значение, записанное в jsonb как now()::text, Модуль не разберёт —
-- сообщение застрянет, а не уедет. Правильная запись:
--   to_char((now() - interval '1 hour') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
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
-- Справочно, и после разобранной очереди всегда ноль: releaseClaim обнуляет
-- dispatch_owner на каждом успешном обороте. Что очередь разбирали ОБА пода, видно не
-- отсюда, а из счётчиков вызовов каждого инстанса:
--   curl -s localhost:<порт>/actuator/prometheus | grep '^commhub_provider_calls_seconds_count'
-- снятых до и после; их дельты обязаны дать в сумме 60.
SELECT count(DISTINCT dispatch_owner) AS owners, 'очередь разбирали оба «пода»' AS check
  FROM message WHERE dispatch_owner IS NOT NULL;


-- >>> IT-DSP-002  Просроченный лизинг возвращает сообщение в очередь
-- @arrange
-- Порядок обратный (правило 1 шапки): сначала подать сообщение с timing.sendAfter через
-- час — оно будет принято и НЕ отправлено, — и только потом исполнить этот блок. Он
-- снимает задержку (timing - 'sendAfter') и приводит строку к виду «захвачено подом,
-- который больше не отвечает: лизинг истёк».
UPDATE message
   SET status = 'QUEUED',
       timing = timing - 'sendAfter',
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
-- Порядок обратный (правило 1 шапки): сначала отправить обычное сообщение на …00 и
-- дождаться попытки, потом исполнить этот блок, потом дать диспетчеру несколько тактов.
-- Воспроизводим состояние «провайдер ответил, расчёт не применён»: открытая попытка
-- и снятый лизинг. Если диспетчер отправит второй раз — появится вторая строка попытки,
-- и это ровно тот дефект, ради которого сага разорвана (H2 аудита).
--
-- Держит здесь сам статус: SENDING не входит в список статусов, которые забирает
-- CLAIM_DISPATCHABLE ('ACCEPTED','VALIDATED','ROUTED','QUEUED','RETRYING'). Обратная
-- сторона того же механизма — такое сообщение в очередь и не вернётся: подобрать его
-- может только ExpireMessagesScheduler (и только если у сообщения есть ttlSeconds или
-- sendBefore) либо отчёт провайдера. См. §4 отчёта runs/2026-08-14-stage-7.md.
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
-- Порядок обратный (правило 1 шапки), и сообщению нужен держатель (правило 2): TTL
-- истекает у того, кто ЖДЁТ, а неудержанное сообщение уходит провайдеру за секунду и
-- проверять становится нечего. Держатель этого кейса — окно тишины DEFER вокруг «сейчас»
-- у потока marketing-defer; ставится ДО подачи:
--   UPDATE stream SET quiet_hours = jsonb_build_object(
--            'start', to_char((now() AT TIME ZONE 'Asia/Tashkent') - interval '1 minute', 'HH24:MI'),
--            'end',   to_char((now() AT TIME ZONE 'Asia/Tashkent') + interval '30 minutes', 'HH24:MI'),
--            'zone', 'Asia/Tashkent', 'behavior', 'DEFER'), updated_at = now()
--    WHERE id = 'marketing-defer';   -- и подождать до 10 с, пока снимок обновится
-- Затем подать сообщение в marketing-defer с timing.ttlSeconds: 60 и исполнить блок ниже:
-- «прошло две минуты» воспроизводится сдвигом момента приёма, а не ожиданием.
--
-- next_attempt_at здесь НЕ трогается намеренно: действие кейса — такт
-- ExpireMessagesScheduler (findExpired не смотрит ни на очередь, ни на лизинг), а сдвиг
-- срока отдал бы сообщение диспетчеру, и кейс проверил бы чужую ветку — ту же отмену по
-- TTL, но в DispatchGuards.
UPDATE message
   SET accepted_at = accepted_at - interval '2 minutes'
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
-- Держит именно срок из документа, а НЕ очередь: у отложенного по sendAfter сообщения
-- next_attempt_at пуст и обязан быть пустым — это три независимых условия в
-- CLAIM_DISPATCHABLE (next_attempt_at, dispatch_claimed_until, timing.sendAfter), и
-- сработало третье. Проверка «next_attempt_at > now()» отдавала NULL, то есть «не
-- проверено», на совершенно здоровом стенде.
SELECT (timing ->> 'sendAfter')::timestamptz > now() AS ok,
       'срок отправки ещё не наступил' AS check FROM message;
SELECT next_attempt_at IS NULL AS ok, 'очередь сообщение не держит — держит срок' AS check
  FROM message;
SELECT count(*) = 0 AS ok, 'попытки отправки не было' AS check FROM delivery_attempt;
-- Второй шаг кейса: сдвинуть срок в прошлое и дать диспетчеру такт. Формат обязателен
-- ISO-8601 (шапка файла): now()::text Модуль не разберёт и сообщение застрянет.
--   UPDATE message SET timing = jsonb_set(timing, '{sendAfter}',
--            to_jsonb(to_char((now() - interval '1 hour') AT TIME ZONE 'UTC',
--                             'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))),
--          next_attempt_at = now();
-- Ждём: одна попытка, SENT_TO_PROVIDER → DELIVERED.


-- >>> IT-DSP-006  Окно allowedWindow закрыто
--
-- ⏭️ ВНЕ MVP, кейс не прогоняется до фазы 2 (дефект набора D-15, прогон 14.08.2026).
-- FR-8.5 в самой SRS ограничен: «MVP — на уровне пробрасывания в Playmobile timing;
-- собственный планировщик — фаза 2». Так оно и построено: timing.allowedWindow хранится
-- (TimingJson.allowedStartTime/allowedEndTime) и уходит в запрос Playmobile
-- (PlaymobileSendCodec: allowed-starttime/allowed-endtime), а Timing.isSendableAt смотрит
-- только sendAfter/sendBefore — окна суток не знает никто выше адаптера. На фиктивном
-- провайдере, SMS Gate, почте и push сообщение с закрытым окном уходит немедленно.
-- Проверки ниже — ожидание фазы 2, а не сегодняшнего Модуля; заодно исправлена и
-- диагностическая строка (в timing нет ключа allowedWindow — есть два плоских ключа).
-- @arrange
-- @assert
SELECT status = 'ROUTED' AS ok, 'сообщение ждёт открытия окна' AS check FROM message;
SELECT next_attempt_at IS NOT NULL AS ok, 'назначено время следующей попытки' AS check
  FROM message;
SELECT count(*) = 0 AS ok, 'провайдер не вызывался' AS check FROM delivery_attempt;
SELECT timing ->> 'allowedStartTime' AS window_start,
       timing ->> 'allowedEndTime'   AS window_end, next_attempt_at FROM message;


-- >>> IT-DSP-007  Kill switch останавливает отправку
-- @arrange
-- Порядок обратный и держатель обязателен (правила 1 и 2 шапки), причём здесь по особой
-- причине: рубильник отсекает трафик ЕЩЁ НА ПРИЁМЕ (503, IT-TC-008), поэтому сообщение,
-- поданное при включённом рубильнике, не будет отложено — его вовсе не примут, а предмет
-- этого кейса — ветка диспетчера, а не входа. Порядок: подать сообщение в core-banking с
-- timing.sendAfter через 25 с (рубильник ещё снят) → включить рубильник (через
-- 50-admin.http или блоком ниже) → дождаться, пока срок наступит, и дать несколько тактов.
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
-- Порядок: массовое сообщение (marketing-bulk) подать ПЕРВЫМ и с timing.sendAfter через
-- 25 с — при включённом рубильнике его на приёме уже не примут (см. IT-DSP-007), — затем
-- включить рубильник блоком ниже, затем подать OTP в ibank-otp без задержки: его пропустят
-- и вход, и диспетчер, потому что includes_critical_otp = false.
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
-- Держатель батча — его собственный статус, поэтому SQL здесь не нужен вовсе и порядок
-- целиком в http/: завести рассылку → PAUSE (элементы приняты и отложены на 30 с
-- deferBackoff) → залить элементы → STOP. Диспетчер отменит их на первом же такте после
-- срока. Блок ниже — запасной вариант, если рассылку останавливали не действием, а руками;
-- сдвиг next_attempt_at только избавляет от ожидания в 30 с.
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
-- Бюджет — commhub.sending.max-total-attempts, по умолчанию 5, и упереться в него можно
-- только на канале с ТРЕМЯ провайдерами: max-attempts-per-provider равен 2, поэтому
-- порядок отката из двух провайдеров исчерпывается на четвёртой попытке и сообщение
-- уходит в DLQ, не дойдя до общего предела. Кейс проверял бы тогда длину fallbackOrder
-- эталонного контура, а не бюджет (дефект набора D-14, прогон 14.08.2026; он же —
-- расхождение, замеченное на этапе 5 в IT-RTE-011).
--
-- Строку канала возвращать не нужно: 01-contour.sql перепишет её перед следующим кейсом.
-- После правки подождать до 10 с — снимок маршрутизации живёт refresh-interval (NF-07).
UPDATE channel
   SET fallback_order = '["MOCK_PRIMARY", "MOCK_RESERVE", "MOCK_CHEAP"]'::jsonb,
       updated_at = now()
 WHERE code = 'SMS';
-- Действие: одно сообщение на адрес …02 (ответа нет) — он один и тот же для всех трёх
-- провайдеров, поэтому настраивать их по отдельности не нужно. Полный цикл занимает
-- около 30 с: паузы между попытками растут 2 → 4 → 8 → 16 с.
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
-- Предусловий в SQL нет, но очередь массовых должна быть ЖИВОЙ в момент подачи OTP:
-- пятьсот сообщений на …00 разбираются за доли секунды (латентность фиктивного провайдера
-- 50 мс, concurrency 64) и доказывать становится нечего. Заливать пятьсот элементов
-- рассылки на адреса …02 — они ретраятся, и класс NOTIFICATION занят около 30 с, чего
-- с запасом хватает, чтобы подать OTP в середину.
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
