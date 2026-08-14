-- =====================================================================================
-- IT-FLT — подавление, согласие, тихие часы, частота (FR-5.1…5.4).
--
-- Хеш адреса считается так же, как AddressHash.of: адрес приводится к нижнему регистру,
-- затем SHA-256 в шестнадцатеричном виде. В SQL это encode(sha256(lower(addr)::bytea), 'hex').
-- Один и тот же хеш ищут и список подавления, и счётчики частоты — если посчитать его
-- иначе, запрет молча перестанет совпадать, и кейс станет зелёным по неверной причине.
--
-- Про время. Тихие часы проверяются по часам Модуля, а не по строке в базе, поэтому
-- «сейчас 23:10 в Ташкенте» на живом стенде достигается одним из двух способов:
--   1) запустить кейс в это время;
--   2) сдвинуть окно потока так, чтобы текущий момент оказался внутри него, — именно это
--      и делают предусловия ниже, чтобы кейс шёл в любое время суток.
-- Второй способ честен: предметом кейса является поведение в окне, а не показания часов.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-FLT-001  Адрес в списке подавления
-- @arrange
INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until, created_by, created_at)
VALUES ('01950000-0000-7000-8000-000000000001', 'SMS',
        encode(sha256(lower('998901234500')::bytea), 'hex'), NULL,
        'OPT_OUT', NULL, 'qa-arrange', now());
-- @assert
SELECT status_reason = 'SUPPRESSED' AS ok, 'сообщение отклонено списком' AS check FROM message;
SELECT count(*) = 0 AS ok, 'провайдер не вызывался' AS check FROM delivery_attempt;


-- >>> IT-FLT-002  Подавление по всем каналам
-- @arrange
-- channel IS NULL — запись «этому получателю не писать никуда».
INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until, created_by, created_at)
VALUES ('01950000-0000-7000-8000-000000000002', NULL,
        encode(sha256(lower('998901234500')::bytea), 'hex'), NULL,
        'COMPLAINT', NULL, 'qa-arrange', now()),
       ('01950000-0000-7000-8000-000000000012', NULL,
        encode(sha256(lower('ivan00@example.com')::bytea), 'hex'), NULL,
        'COMPLAINT', NULL, 'qa-arrange', now());
-- @assert
SELECT count(*) = 2 AS ok, 'оба сообщения отклонены' AS check
  FROM message WHERE status_reason = 'SUPPRESSED';
SELECT count(*) = 0 AS ok, 'ни одной попытки отправки' AS check FROM delivery_attempt;


-- >>> IT-FLT-003  Истёкшая запись подавления
-- @arrange
INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until, created_by, created_at)
VALUES ('01950000-0000-7000-8000-000000000003', 'SMS',
        encode(sha256(lower('998901234500')::bytea), 'hex'), NULL,
        'DELIVERY_FAILURES', now() - interval '1 day', 'qa-arrange', now() - interval '30 days');
-- @assert
SELECT valid_until < now() AS ok, 'запись действительно истекла' AS check
  FROM suppression_list WHERE id = '01950000-0000-7000-8000-000000000003';
SELECT status_reason IS DISTINCT FROM 'SUPPRESSED' AS ok,
       'срок применяется на пути отправки: истёкшая запись не запрещает' AS check
  FROM message;
SELECT count(*) >= 1 AS ok, 'сообщение ушло провайдеру' AS check FROM delivery_attempt;


-- >>> IT-FLT-004  Подавление другого канала не мешает
-- @arrange
INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until, created_by, created_at)
VALUES ('01950000-0000-7000-8000-000000000004', 'EMAIL',
        encode(sha256(lower('998901234500')::bytea), 'hex'), NULL,
        'HARD_BOUNCE', NULL, 'qa-arrange', now());
-- @assert
SELECT status_reason IS DISTINCT FROM 'SUPPRESSED' AS ok,
       'запрет на EMAIL не распространяется на SMS' AS check
  FROM message;
SELECT count(*) >= 1 AS ok, 'SMS отправлено' AS check FROM delivery_attempt;


-- >>> IT-FLT-005  Порядок фильтров
-- @arrange
-- Адрес одновременно подавлен и попадает в окно тишины. Ожидается SUPPRESSED:
-- подавление проверяется первым, и причина отказа должна быть именно та, что сработала.
INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until, created_by, created_at)
VALUES ('01950000-0000-7000-8000-000000000005', 'SMS',
        encode(sha256(lower('998901234500')::bytea), 'hex'), NULL,
        'OPT_OUT', NULL, 'qa-arrange', now());

UPDATE stream
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '1 hour', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' + interval '2 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'REJECT'),
       updated_at = now()
 WHERE id = 'marketing-bulk';
-- @assert
SELECT status_reason = 'SUPPRESSED' AS ok,
       'названа первая сработавшая причина, а не последняя проверенная' AS check
  FROM message;


-- >>> IT-FLT-006  Тихие часы, поведение REJECT
-- @arrange
-- Окно строится вокруг текущего момента, чтобы кейс шёл в любое время суток.
UPDATE stream
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '1 hour', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' + interval '2 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'REJECT'),
       updated_at = now()
 WHERE id = 'marketing-bulk';
-- @assert
SELECT quiet_hours ->> 'behavior' = 'REJECT' AS ok, 'окно настроено на отказ' AS check
  FROM stream WHERE id = 'marketing-bulk';
SELECT status = 'REJECTED' AND status_reason = 'QUIET_HOURS' AS ok,
       'сообщение отклонено окном тишины' AS check
  FROM message WHERE stream_id = 'marketing-bulk';
SELECT terminal_at IS NOT NULL AS ok, 'отказ терминален' AS check
  FROM message WHERE stream_id = 'marketing-bulk';


-- >>> IT-FLT-007  Тихие часы, поведение DEFER
-- @arrange
UPDATE stream
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '1 hour', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' + interval '2 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'DEFER'),
       updated_at = now()
 WHERE id = 'marketing-defer';
-- @assert
SELECT status = 'ROUTED' AS ok,
       'отложенное остаётся в ROUTED — не QUEUED и не REJECTED' AS check
  FROM message WHERE stream_id = 'marketing-defer';
SELECT terminal_at IS NULL AS ok, 'сообщение не терминально: его ещё отправят' AS check
  FROM message WHERE stream_id = 'marketing-defer';
SELECT count(*) = 0 AS ok, 'провайдер не вызывался' AS check FROM delivery_attempt;


-- >>> IT-FLT-008  Отложенное уходит по открытии окна
-- @arrange
-- Продолжение IT-FLT-007: сообщение уже лежит в ROUTED. Окно закрываем — то есть
-- переносим его в прошлое, чтобы текущий момент оказался снаружи.
UPDATE stream
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '4 hours', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '2 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'DEFER'),
       updated_at = now()
 WHERE id = 'marketing-defer';
-- @assert
SELECT status IN ('QUEUED', 'SENDING', 'SENT_TO_PROVIDER', 'DELIVERED') AS ok,
       'после открытия окна диспетчер забрал сообщение' AS check
  FROM message WHERE stream_id = 'marketing-defer';
SELECT count(*) >= 1 AS ok, 'попытка отправки состоялась' AS check FROM delivery_attempt;


-- >>> IT-FLT-009  CRITICAL_OTP тихие часы не соблюдает
-- @arrange
-- Заводим окно прямо вокруг текущего момента и на OTP-потоке тоже — чтобы проверялось
-- именно правило класса трафика, а не отсутствие окна.
UPDATE stream
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '1 hour', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' + interval '2 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'REJECT'),
       updated_at = now()
 WHERE id = 'ibank-otp';
-- @assert
SELECT quiet_hours IS NOT NULL AS ok, 'окно на OTP-потоке заведено' AS check
  FROM stream WHERE id = 'ibank-otp';
SELECT status_reason IS DISTINCT FROM 'QUIET_HOURS' AS ok,
       'OTP не подчиняется окну тишины — TrafficClass.respectsQuietHours()' AS check
  FROM message WHERE stream_id = 'ibank-otp';
SELECT count(*) >= 1 AS ok, 'OTP отправлен немедленно' AS check FROM delivery_attempt;


-- >>> IT-FLT-010  TRANSACTIONAL тихие часы не соблюдает
-- @arrange
UPDATE stream
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '1 hour', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' + interval '2 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'REJECT'),
       updated_at = now()
 WHERE id = 'core-banking';
-- @assert
SELECT status_reason IS DISTINCT FROM 'QUIET_HOURS' AS ok,
       'транзакционный класс окну не подчиняется' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT count(*) >= 1 AS ok, 'сообщение отправлено' AS check FROM delivery_attempt;


-- >>> IT-FLT-011  Окно потока важнее окна канала
-- @arrange
-- Канал: окно, в которое текущий момент НЕ попадает (значит, канал бы разрешил).
UPDATE channel
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '5 hours', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '3 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'REJECT'),
       updated_at = now()
 WHERE code = 'SMS';
-- Поток: окно, в которое текущий момент попадает (значит, поток запрещает).
UPDATE stream
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '1 hour', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' + interval '2 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'REJECT'),
       updated_at = now()
 WHERE id = 'marketing-bulk';
-- @assert
SELECT status_reason = 'QUIET_HOURS' AS ok,
       'применено окно потока: поток → канал, первое найденное побеждает' AS check
  FROM message WHERE stream_id = 'marketing-bulk';


-- >>> IT-FLT-012  Окно, переходящее через полночь
-- @arrange
-- Фиксированное окно 21:00–09:00: проверяются четыре момента — 20:59, 21:01, 08:59, 09:01.
-- Каждый прогоняется отдельно; предусловие одно и то же.
UPDATE stream
   SET quiet_hours = '{"start": "21:00", "end": "09:00", "zone": "Asia/Tashkent",
                       "behavior": "REJECT"}'::jsonb,
       updated_at = now()
 WHERE id = 'marketing-bulk';
-- @assert
-- Ожидание зависит от момента отправки: внутри окна — QUIET_HOURS, снаружи — отправка.
SELECT to_char(now() AT TIME ZONE 'Asia/Tashkent', 'HH24:MI') AS tashkent_now,
       (now() AT TIME ZONE 'Asia/Tashkent')::time >= '21:00'::time
    OR (now() AT TIME ZONE 'Asia/Tashkent')::time <  '09:00'::time AS expected_quiet;
SELECT status, status_reason, accepted_at AT TIME ZONE 'Asia/Tashkent' AS accepted_tashkent
  FROM message WHERE stream_id = 'marketing-bulk' ORDER BY accepted_at;


-- >>> IT-FLT-013  Повторная оценка окна диспетчером
-- @arrange
-- Сообщение принято, когда окно было открыто, и лежит в ROUTED. Теперь окно закрывается —
-- диспетчер обязан заметить это на своём такте, а не отправить по старому решению.
-- Порядок действий кейса: (1) отправить при открытом окне, (2) выполнить этот UPDATE,
-- (3) дать диспетчеру такт.
UPDATE stream
   SET quiet_hours = jsonb_build_object(
           'start', to_char(now() AT TIME ZONE 'Asia/Tashkent' - interval '1 hour', 'HH24:MI'),
           'end',   to_char(now() AT TIME ZONE 'Asia/Tashkent' + interval '2 hours', 'HH24:MI'),
           'zone',  'Asia/Tashkent',
           'behavior', 'DEFER'),
       updated_at = now()
 WHERE id = 'marketing-defer';
-- @assert
SELECT status = 'ROUTED' AS ok, 'диспетчер отложил, а не отправил' AS check
  FROM message WHERE stream_id = 'marketing-defer';
SELECT count(*) = 0 AS ok, 'попытки отправки не было' AS check FROM delivery_attempt;
SELECT next_attempt_at IS NOT NULL AS ok, 'назначено время следующей попытки' AS check
  FROM message WHERE stream_id = 'marketing-defer';


-- >>> IT-FLT-014  Частотный порог считает и алертит, но не блокирует
-- @arrange
-- Значение по умолчанию: commhub.compliance.frequency-cap.blocking=false, порог 10/24 ч.
-- Предусловий нет — двенадцать отправок делает само действие кейса.
-- @assert
SELECT count(*) = 12 AS ok, 'все двенадцать приняты' AS check
  FROM message WHERE stream_id = 'marketing-bulk';
SELECT count(*) = 0 AS ok, 'ни одного отказа по частоте' AS check
  FROM message WHERE status_reason = 'FREQUENCY_CAPPED';
SELECT sum(counter) = 12 AS ok, 'счётчик частоты сосчитал все двенадцать' AS check
  FROM frequency_counter
 WHERE address_hash = encode(sha256(lower('998901234500')::bytea), 'hex');
SELECT address_hash, channel, bucket_start, counter FROM frequency_counter ORDER BY bucket_start;


-- >>> IT-FLT-015  Частотный порог блокирует при blocking=true
-- @arrange
-- Требует контура с commhub.compliance.frequency-cap.blocking=true и max-messages=3.
-- @assert
SELECT count(*) = 3 AS ok, 'первые три отправлены' AS check
  FROM message WHERE status_reason IS NULL OR status_reason <> 'FREQUENCY_CAPPED';
SELECT count(*) = 2 AS ok, 'остальные отклонены по частоте' AS check
  FROM message WHERE status_reason = 'FREQUENCY_CAPPED';
SELECT count(*) = 3 AS ok, 'провайдер вызван ровно три раза' AS check FROM delivery_attempt;


-- >>> IT-FLT-016  Частотные корзины — часовые
-- @arrange
-- Три отправки, затем «прошли сутки»: сдвигаем корзины за пределы окна капа.
-- Ведро, в которое попал левый край окна, учитывается целиком — огрубление честное
-- и работает в безопасную сторону, поэтому сдвигаем с запасом.
UPDATE frequency_counter
   SET bucket_start = bucket_start - interval '25 hours'
 WHERE address_hash = encode(sha256(lower('998901234500')::bytea), 'hex');
-- @assert
-- Проверки читаются ПОСЛЕ действия, то есть после четвёртой отправки, а она заводит
-- свою корзину в текущем часе — то есть внутри окна. Поэтому «в окне пусто» проверять
-- нельзя (D-10): проверяется, что за окном лежат сдвинутые три, а внутри окна — ровно
-- одна корзина четвёртого сообщения.
SELECT count(*) = 1 AND sum(counter) = 3 AS ok, 'сдвинутые корзины ушли за окно капа' AS check
  FROM frequency_counter
 WHERE address_hash = encode(sha256(lower('998901234500')::bytea), 'hex')
   AND bucket_start < date_trunc('hour', now() - interval '24 hours');
SELECT count(*) = 1 AND sum(counter) = 1 AS ok,
       'внутри окна — только корзина четвёртого сообщения' AS check
  FROM frequency_counter
 WHERE address_hash = encode(sha256(lower('998901234500')::bytea), 'hex')
   AND bucket_start >= date_trunc('hour', now() - interval '24 hours');
SELECT count(*) = 0 AS ok, 'четвёртое сообщение по частоте не отклонено' AS check
  FROM message WHERE status_reason = 'FREQUENCY_CAPPED';
