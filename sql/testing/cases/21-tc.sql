-- =====================================================================================
-- IT-TC — классы трафика и общие выключатели (TC-01, FR-2.1, FR-3.2).
--
-- Изоляция классов построена из топиков: свои контейнер-фактори, свои пулы потоков,
-- свои лимиты. Поэтому кейсы IT-TC-001 и IT-TC-002 существуют только в Kafka-варианте,
-- а IT-TC-003 — только в REST: у REST топика нет, и класс приходится брать из документа.
--
-- Проверка «топик перекрывает документ» делается по message.traffic_class: документ
-- заявляет одно, топик другое, в базе обязано оказаться значение топика.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-TC-001  Класс трафика берётся из топика
-- @arrange
-- @assert
-- Документ с trafficClass: NOTIFICATION положен в comm.inbound.critical.v1.
SELECT count(*) = 1 AS ok, 'сообщение принято' AS check FROM message;
SELECT traffic_class = 'CRITICAL_OTP' AS ok,
       'победил топик: изоляция TC-01 построена на топиках, а не на поле документа' AS check
  FROM message;
SELECT priority = 'REALTIME' AS ok, 'приоритет выведен из класса топика' AS check FROM message;


-- >>> IT-TC-002  Каждый топик даёт свой класс
-- @arrange
-- @assert
-- По документу в каждый из трёх входящих топиков.
SELECT count(*) = 3 AS ok, 'приняты все три' AS check FROM message;
SELECT count(DISTINCT traffic_class) = 3 AS ok, 'все три класса представлены' AS check
  FROM message;
SELECT array_agg(traffic_class ORDER BY traffic_class)
       = ARRAY['CRITICAL_OTP', 'NOTIFICATION', 'TRANSACTIONAL'] AS ok,
       'классы именно те' AS check
  FROM message;
SELECT traffic_class, priority, stream_id FROM message ORDER BY traffic_class;


-- >>> IT-TC-003  Класс трафика в REST берётся из документа
-- @arrange
-- @assert
-- У REST топика нет, поэтому единственный источник — поле документа.
SELECT traffic_class = 'CRITICAL_OTP' AS ok, 'класс взят из тела запроса' AS check FROM message;
SELECT priority = 'REALTIME' AS ok, 'приоритет выведен из класса' AS check FROM message;


-- >>> IT-TC-004  Приоритет выводится из класса
-- @arrange
-- @assert
-- По сообщению каждого класса: CRITICAL_OTP → REALTIME, TRANSACTIONAL → NORMAL,
-- NOTIFICATION → LOW. Приоритет не приходит извне и не настраивается — он следствие класса.
SELECT count(*) = 3 AS ok, 'приняты все три' AS check FROM message;
SELECT bool_and(
        (traffic_class = 'CRITICAL_OTP'  AND priority = 'REALTIME') OR
        (traffic_class = 'TRANSACTIONAL' AND priority = 'NORMAL')   OR
        (traffic_class = 'NOTIFICATION'  AND priority = 'LOW')) AS ok,
       'каждый класс дал свой приоритет' AS check
  FROM message;
SELECT traffic_class, priority FROM message ORDER BY traffic_class;


-- >>> IT-TC-005  Массовая нагрузка не задерживает OTP
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'OTP отправлен' AS check
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id
 WHERE m.traffic_class = 'CRITICAL_OTP';
-- Качественная проверка: OTP ушёл раньше, чем разобрали массовую очередь. Цифры p99
-- этим не доказываются — для них нагрузочный прогон (QA-05, load/k6).
SELECT (SELECT min(a.request_at) FROM delivery_attempt a JOIN message m ON m.id = a.message_id
         WHERE m.traffic_class = 'CRITICAL_OTP')
     < (SELECT max(a.request_at) FROM delivery_attempt a JOIN message m ON m.id = a.message_id
         WHERE m.traffic_class = 'NOTIFICATION') AS ok,
       'OTP не встал в конец массовой очереди' AS check;
SELECT m.traffic_class, count(*) AS attempts,
       min(a.request_at) AS first_call, max(a.request_at) AS last_call
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id
 GROUP BY m.traffic_class ORDER BY m.traffic_class;


-- >>> IT-TC-006  Приостановленный поток
-- @arrange
-- @assert
SELECT status = 'SUSPENDED' AS ok, 'поток приостановлен' AS check
  FROM stream WHERE id = 'stream-suspended';
SELECT count(*) = 0 AS ok, 'сообщение не принято' AS check
  FROM message WHERE stream_id = 'stream-suspended';
-- Источнику отвечает 409 с причиной STREAM_SUSPENDED.


-- >>> IT-TC-007  Выключенный поток
-- @arrange
-- @assert
SELECT status = 'DISABLED' AS ok, 'поток выключен' AS check
  FROM stream WHERE id = 'stream-disabled';
SELECT count(*) = 0 AS ok,
       'DISABLED трафика тоже не принимает: acceptsTraffic() истинно только для ACTIVE' AS check
  FROM message WHERE stream_id = 'stream-disabled';


-- >>> IT-TC-008  Kill switch на приёме
-- @arrange
UPDATE kill_switch
   SET active = true, includes_critical_otp = false,
       changed_at = now(), changed_by = 'qa-arrange',
       reason = 'предусловие кейса IT-TC-008'
 WHERE id;
-- @assert
SELECT active AND NOT includes_critical_otp AS ok,
       'рубильник включён и щадит OTP' AS check
  FROM kill_switch;
SELECT count(*) = 0 AS ok, 'обычное сообщение не принято' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT count(*) = 1 AS ok, 'OTP прошёл: по умолчанию рубильник его не касается' AS check
  FROM message WHERE stream_id = 'ibank-otp';
-- Обычному источнику отвечает 503 с причиной KILL_SWITCH.
-- Полярность отката здесь обратная всему остальному: нечитаемая таблица рубильника
-- НЕ означает «ничего не остановлено» — везде ещё умолчание спасает отправку,
-- здесь оно бы её продолжило вопреки решению оператора.
