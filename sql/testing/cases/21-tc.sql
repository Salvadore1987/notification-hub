-- =====================================================================================
-- IT-TC — классы трафика и общие выключатели (TC-01, FR-2.1, FR-3.2).
--
-- Изоляция классов построена из топиков: свои контейнер-фактори, свои пулы потоков,
-- свои лимиты. Поэтому кейсы IT-TC-001 и IT-TC-002 существуют только в Kafka-варианте,
-- а IT-TC-003 — только в REST: у REST топика нет, и класс приходится брать из документа.
--
-- Проверка «топик перекрывает документ» делается по message.traffic_class: документ
-- заявляет одно, топик другое, в базе обязано оказаться значение топика.
--
-- ДВА ПРАВИЛА, БЕЗ КОТОРЫХ КЕЙСЫ ОБЛАСТИ ЗЕЛЕНЕЮТ ПО НЕВЕРНОЙ ПРИЧИНЕ.
--
-- 1. Поток берётся core-banking, а не ibank-otp. Значение CRITICAL_OTP на OTP-потоке
--    объясняется тремя способами сразу (топик, документ, умолчание потока) и не доказывает
--    ничего. На core-banking умолчание — TRANSACTIONAL, документ заявляет NOTIFICATION,
--    и CRITICAL_OTP в базе не объясняется ничем, кроме топика.
--
-- 2. Приоритет потока снимается в @arrange. Stream.effectivePriority выбирает так:
--    явный из документа → умолчание ПОТОКА → умолчание класса, — то есть умолчание потока
--    побеждает класс, а его несёт каждый из одиннадцати потоков контура. Проверка
--    «приоритет выведен из класса» без этого недостижима в принципе (дефект обвязки D-31,
--    этап 11). Ослаблением Модуля это не является: TC-02 говорит про класс трафика,
--    а не про приоритет, и stream.default_priority (V2) существует ровно затем, чтобы
--    поток мог назначить свой.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-TC-001  Класс трафика берётся из топика
-- @arrange
UPDATE stream SET default_priority = NULL WHERE id = 'core-banking';
-- @assert
-- Документ потока core-banking с trafficClass: NOTIFICATION положен в comm.inbound.critical.v1.
SELECT count(*) = 1 AS ok, 'сообщение принято' AS check FROM message;
SELECT traffic_class = 'CRITICAL_OTP' AS ok,
       'победил топик: изоляция TC-01 построена на топиках, а не на поле документа' AS check
  FROM message;
SELECT priority = 'REALTIME' AS ok, 'приоритет выведен из класса топика' AS check FROM message;


-- >>> IT-TC-002  Каждый топик даёт свой класс
-- @arrange
UPDATE stream SET default_priority = NULL WHERE id = 'core-banking';
-- @assert
-- По документу потока core-banking в каждый из трёх входящих топиков; поля trafficClass
-- в документах нет вовсе. Два класса из трёх расходятся с умолчанием потока, поэтому
-- источник значения виден однозначно.
SELECT count(*) = 3 AS ok, 'приняты все три' AS check FROM message;
SELECT count(DISTINCT traffic_class) = 3 AS ok, 'все три класса представлены' AS check
  FROM message;
SELECT array_agg(traffic_class ORDER BY traffic_class)
       = ARRAY['CRITICAL_OTP', 'NOTIFICATION', 'TRANSACTIONAL'] AS ok,
       'классы именно те' AS check
  FROM message;
-- Приоритет здесь — тоже следствие класса: умолчание потока снято в @arrange.
SELECT bool_and(
        (traffic_class = 'CRITICAL_OTP'  AND priority = 'REALTIME') OR
        (traffic_class = 'TRANSACTIONAL' AND priority = 'NORMAL')   OR
        (traffic_class = 'NOTIFICATION'  AND priority = 'LOW')) AS ok,
       'приоритет каждого выведен из класса его топика' AS check
  FROM message;
SELECT traffic_class, priority, stream_id FROM message ORDER BY traffic_class;


-- >>> IT-TC-003  Класс трафика в REST берётся из документа
-- @arrange
UPDATE stream SET default_priority = NULL WHERE id = 'core-banking';
-- @assert
-- POST /api/v1/messages потока core-banking с trafficClass: CRITICAL_OTP в теле.
-- У REST топика нет, поэтому единственный источник — поле документа; умолчание потока
-- (TRANSACTIONAL) с ним расходится, поэтому CRITICAL_OTP в базе доказывает именно его.
SELECT traffic_class = 'CRITICAL_OTP' AS ok, 'класс взят из тела запроса' AS check FROM message;
SELECT priority = 'REALTIME' AS ok, 'приоритет выведен из класса' AS check FROM message;


-- >>> IT-TC-004  Приоритет выводится из класса
-- @arrange
UPDATE stream SET default_priority = NULL WHERE id = 'core-banking';
-- @assert
-- Три REST-отправки в один поток core-banking, класс задан в теле: CRITICAL_OTP → REALTIME,
-- TRANSACTIONAL → NORMAL, NOTIFICATION → LOW. Один поток на все три — потому что три разных
-- потока контура несут свои умолчания приоритета, совпадающие с классовыми, и проверка
-- позеленела бы, не проверив ничего.
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
-- Действие: 500 документов потока core-banking в comm.inbound.notification.v1, сразу за ними
-- один в comm.inbound.critical.v1. Оба класса приходят из топиков, поэтому поток на все 501
-- один и тихие часы marketing-потоков в предусловие не входят.
--
-- Прогонять на ПРОГРЕТОМ инстансе и отдельно на только что запущенном: разница между ними и
-- есть предмет дефекта D-33 (этап 11) — на холодном первая же рассылка пропускает вперёд себя
-- около двухсот массовых, на прогретом ни одного.
SELECT count(*) = 1 AS ok, 'OTP отправлен' AS check
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id
 WHERE m.traffic_class = 'CRITICAL_OTP';
-- «Не дожидаясь очереди» — это НОЛЬ массовых вызовов раньше OTP, а не «OTP оказался не
-- последним». Прежняя формулировка (min(OTP) < max(NOTIFICATION)) зеленела бы и на пятисотом
-- месте из пятисот одного, то есть при полном отсутствии изоляции: она проверяла момент
-- времени, а не факт (дефект обвязки D-32, этап 11 — та же природа, что D-25 этапа 10).
SELECT count(*) = 0 AS ok,
       'ни одно массовое не ушло провайдеру раньше OTP' AS check
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id
 WHERE m.traffic_class = 'NOTIFICATION'
   AND a.request_at < (SELECT min(a2.request_at)
                         FROM delivery_attempt a2 JOIN message m2 ON m2.id = a2.message_id
                        WHERE m2.traffic_class = 'CRITICAL_OTP');
-- Цифры p99 этим не доказываются — для них нагрузочный прогон (QA-05, load/k6). Но время
-- accept→provider выводится: именно в нём сформулирован SLA TC-01 (5 с), и на холодном
-- инстансе оно за него уходило (дефект D-33).
SELECT round(extract(epoch FROM a.request_at - m.accepted_at)::numeric, 3)
         AS otp_accept_to_provider_sec
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id
 WHERE m.traffic_class = 'CRITICAL_OTP';
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
