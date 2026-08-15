-- =====================================================================================
-- IT-STS — статусы, outbox, DLQ (§6.3, §6.4, FR-3.3, AD-03).
--
-- Проверки этой области делятся надвое: то, что видно в базе (история переходов, строки
-- outbox, published_at), и то, что видно только в топике (порядок, ключ раздела,
-- заголовки, состав документа). Второе SQL не покажет — для него README, раздел
-- «Что проверяется не в базе».
--
-- Ключевое правило, которое проверяется тремя кейсами сразу: published_at ставится
-- ТОЛЬКО после подтверждения брокера, а неудачная публикация останавливает проход —
-- порядок статусов на сообщение важнее пропускной способности релея.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-STS-001  Состав статусного события
-- @arrange
-- @assert
-- ВНИМАНИЕ. Хранимый payload — НЕ документ §6.4, и спрашивать с него состав §6.4 нельзя
-- (дефект обвязки D-21, прогон 15.08.2026; тот же по природе, что D-18 этапа 8). В базе
-- лежит MessageStatusEventJson: вложенные key и outcome, без schemaVersion, и null'ы
-- Jackson выбрасывает. Плоские четырнадцать полей §6.4 и явные null'ы рисует релей
-- (StatusEventCodec) в момент публикации, поэтому предмет кейса проверяется ВЫЧИТКОЙ
-- ТОПИКА — см. README, раздел «Что проверяется не в базе».
SELECT count(*) >= 1 AS ok, 'событие создано' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
-- То, что в базе действительно есть: обе половины хранимой формы и её обязательные поля.
SELECT bool_and(payload ?& array['eventId','occurredAt','key','outcome','segments']) AS ok,
       'хранимая форма события цела' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
SELECT bool_and(payload -> 'key' ?& array['streamId','messageId','externalMessageId','correlationId']) AS ok,
       'опознание сообщения на месте' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
SELECT bool_and(payload -> 'outcome' ? 'status') AS ok, 'статус на месте' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
SELECT jsonb_pretty(payload) FROM outbox_event WHERE event_type = 'MESSAGE_STATUS' LIMIT 1;
-- Проверка предмета кейса (после того как релей опубликовал):
--   docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
--     --bootstrap-server localhost:9092 --topic comm.outbound.status.v1 \
--     --from-beginning --timeout-ms 8000 \
--     --property print.key=true --property print.partition=true --property print.headers=true
-- В записи обязаны быть все четырнадцать полей §6.4 (schemaVersion, eventId, occurredAt,
-- streamId, batchId, messageId, externalMessageId, channel, provider, status,
-- providerStatus, reason, segments, correlationId), отсутствующие — явными null,
-- schemaVersion = "1.0".


-- >>> IT-STS-002  Ключ раздела — идентификатор сообщения
-- @arrange
-- @assert
SELECT count(DISTINCT aggregate_id) = 1 AS ok, 'все события — про одно сообщение' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
-- Порядок в базе задаётся created_at; в топике он сохранится, потому что ключ раздела —
-- messageId, и все события сообщения попадают в один раздел.
-- Статус лежит в outcome, а не в корне payload'а (D-21, см. шапку IT-STS-001).
SELECT array_agg(payload -> 'outcome' ->> 'status' ORDER BY created_at) AS порядок_в_outbox
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
-- Порядок и номер раздела в самом топике проверяются вычиткой (print.partition=true):
-- все события одного сообщения обязаны лежать в ОДНОМ разделе и идти в этом же порядке.


-- >>> IT-STS-003  Заголовки события
-- @arrange
-- @assert
SELECT count(*) >= 1 AS ok, 'событие есть' AS check FROM outbox_event;
-- commhub-event-id — это eventId ИЗ PAYLOAD'а, а не идентификатор строки outbox: строка
-- получает свой UuidV7 (OutboxEvent.ofMessage), и он наружу не уезжает. Сверять заголовок
-- со столбцом id бессмысленно — они и не обязаны совпадать (D-21).
-- schemaVersion в базе не хранится вовсе: её ставит релей (StatusEventCodec.SCHEMA_VERSION).
SELECT payload ->> 'eventId' AS commhub_event_id, event_type AS commhub_event_type,
       payload -> 'key' ->> 'streamId' AS commhub_stream_id,
       id AS строка_outbox,
       'сверить с заголовками записи в топике' AS check
  FROM outbox_event ORDER BY created_at LIMIT 5;


-- >>> IT-STS-004  published_at ставится после подтверждения брокера
-- @arrange
-- Действие кейса: приостановить брокер (docker compose pause kafka), отправить сообщение,
-- дать релею такт, снять паузу. Предусловий в базе нет.
-- @assert
-- Пока брокер молчит: строка не опубликована, счётчик попыток растёт, ошибка записана.
SELECT count(*) > 0 AS ok, 'событие лежит в outbox' AS check FROM outbox_event;
SELECT bool_and(published_at IS NULL) AS ok,
       'при недоступном брокере ничего не помечено опубликованным' AS check
  FROM outbox_event;
SELECT bool_or(attempts > 0) AS ok, 'попытки публикации сосчитаны' AS check FROM outbox_event;
SELECT id, attempts, published_at, left(last_error, 120) AS last_error FROM outbox_event;
-- После снятия паузы: published_at проставлен, и ровно один раз.
--   SELECT count(*) FILTER (WHERE published_at IS NULL) = 0 FROM outbox_event;


-- >>> IT-STS-005  Отказ публикации останавливает проход
-- @arrange
-- @assert
-- Три события, первое не публикуется. Второе и третье обязаны остаться неопубликованными:
-- порядок статусов на сообщение важнее пропускной способности релея.
SELECT count(*) = 3 AS ok, 'все три события в очереди' AS check FROM outbox_event;
SELECT count(*) = 0 AS ok, 'ни одно не проскочило вперёд упавшего' AS check
  FROM outbox_event WHERE published_at IS NOT NULL;
SELECT id, created_at, event_type, attempts, published_at
  FROM outbox_event ORDER BY created_at;


-- >>> IT-STS-006  Приём продолжается при недоступном брокере
-- @arrange
-- Действие кейса: docker compose pause kafka, затем отправка через REST.
-- @assert
SELECT count(*) = 1 AS ok, 'сообщение принято и сохранено' AS check FROM message;
SELECT status = 'ACCEPTED' OR status IS NOT NULL AS ok, 'у сообщения есть статус' AS check
  FROM message;
SELECT count(*) >= 1 AS ok,
       'события копятся в outbox — для того он и существует (AD-03)' AS check
  FROM outbox_event WHERE published_at IS NULL;
-- Источнику при этом отвечает 202: недоступность брокера не должна останавливать приём.


-- >>> IT-STS-007  Полная цепочка статусов
-- @arrange
-- @assert
SELECT array_agg(status ORDER BY occurred_at) AS timeline FROM message_status_history;
SELECT array_agg(status ORDER BY occurred_at) @> ARRAY[
        'ACCEPTED','VALIDATED','ROUTED','QUEUED','SENDING','SENT_TO_PROVIDER','DELIVERED'] AS ok,
       'пройдена вся цепочка §6.3, ни один переход не пропущен' AS check
  FROM message_status_history;
SELECT count(*) = count(DISTINCT (message_id, occurred_at, status)) AS ok,
       'повторов перехода нет' AS check
  FROM message_status_history;


-- >>> IT-STS-008  Невозможный переход отклоняется
-- @arrange
-- Сообщение уже доставлено; приходит отчёт, требующий перехода, которого у DELIVERED нет.
--
-- ОТЧЁТА, ТРЕБУЮЩЕГО ИМЕННО SENDING, НЕ БЫВАЕТ (дефект набора D-22, прогон 15.08.2026):
-- SENDING означает «вызов провайдера в полёте» и ставится самим Модулем, а ни в §18.1, ни
-- в §18.2 нет слова статуса, которое на него отображается. Берётся ближайший достижимый
-- вход того же класса — отчёт accepted/enroute/sent (→ SENT_TO_PROVIDER): у DELIVERED
-- список разрешённых переходов ПУСТ (ST-01), поэтому любой отчёт по доставленному
-- сообщению и есть невозможный переход.
-- @assert
SELECT status = 'DELIVERED' AS ok, 'статус не сдвинулся назад' AS check FROM message;
SELECT count(*) = 0 AS ok, 'после DELIVERED в историю ничего не дописано' AS check
  FROM message_status_history
 WHERE occurred_at > (SELECT max(occurred_at) FROM message_status_history WHERE status = 'DELIVERED');
-- Второго статусного события источнику тоже не уехало: перехода не было — событию неоткуда взяться.
SELECT count(*) = 1 AS ok, 'событие о доставке ровно одно' AS check
  FROM outbox_event WHERE payload -> 'outcome' ->> 'status' = 'DELIVERED';
SELECT count(*) = 0 AS ok, 'после доставки новых событий не появилось' AS check
  FROM outbox_event
 WHERE created_at > (SELECT max(created_at) FROM outbox_event
                      WHERE payload -> 'outcome' ->> 'status' = 'DELIVERED');
SELECT payload -> 'outcome' ->> 'status' AS status, count(*) FROM outbox_event GROUP BY 1 ORDER BY 1;
-- Наружу ошибки нет: провайдер прислал то, что прислал, и это не повод отвечать ему 500.
-- Ответ callback'а — 200 {"received":1,"applied":0}.


-- >>> IT-STS-009  Событие DLQ
-- @arrange
-- Предусловие кейса — исчерпанный бюджет попыток, и оно ровно то же, что у IT-DSP-010:
-- упереться в commhub.sending.max-total-attempts (5) можно только на канале с ТРЕМЯ
-- провайдерами, потому что max-attempts-per-provider равен 2 и порядок отката из двух
-- исчерпывается на четвёртой попытке (дефект набора D-14, этап 7). Держим предусловие
-- здесь, а не в тексте кейса: кейс обязан прогоняться сам по себе.
--
-- Действие: одно сообщение на адрес …02 (ответа нет). Полный цикл ~30 с — паузы между
-- попытками растут 2 → 4 → 8 → 16 с. После правки подождать до 10 с (кэш маршрутизации).
UPDATE channel
   SET fallback_order = '["MOCK_PRIMARY", "MOCK_RESERVE", "MOCK_CHEAP"]'::jsonb,
       updated_at = now()
 WHERE code = 'SMS';
-- @assert
SELECT count(*) = 1 AS ok, 'запись DLQ заведена' AS check FROM dlq_entry;
SELECT reason = 'ATTEMPTS_EXHAUSTED' AS ok, 'причина названа' AS check FROM dlq_entry;
SELECT NOT archived AND retried_at IS NULL AS ok, 'запись свежая, ещё не разбиралась' AS check
  FROM dlq_entry;
SELECT count(*) = 1 AS ok, 'событие DLQ положено в outbox отдельным типом' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_DLQ';
SELECT jsonb_pretty(payload) FROM outbox_event WHERE event_type = 'MESSAGE_DLQ';


-- >>> IT-STS-010  Ручной повтор из DLQ
-- @arrange
-- Продолжение IT-STS-009: сообщение уже в FAILED с записью DLQ. Сам повтор запускается
-- use case'ом (POST /api/admin/v1/dlq/retry), а не правкой строки, — иначе кейс проверил
-- бы UPDATE.
--
-- ПОРЯДОК У КЕЙСА ОБРАТНЫЙ, как у всей области IT-DSP: сначала действие IT-STS-009
-- (отправка на …02 и исчерпание бюджета), потом этот блок, потом повтор.
--
-- Зачем блок вообще нужен (дефект набора D-24, прогон 15.08.2026). Причина отказа —
-- суффикс адреса: …02 значит «провайдер не отвечает», и он не меняется от того, что
-- сообщение повторили. Поэтому повторённое сообщение падало снова через полсекунды, его
-- запись DLQ переписывалась новым приходом — retried_by/retried_at обнуляются намеренно,
-- второй приход отдаётся оператору как новый (см. DlqPersistenceAdapter) — и проверка
-- «отмечено, кто и когда повторил» была недостижима нигде, кроме гонки в полсекунды.
-- Оператор повторяет отправку ПОСЛЕ того, как устранил причину; здесь она устраняется
-- ровно так же — адрес получателя меняется на …00. Ничего, кроме причины отказа, блок
-- не трогает: ни статус, ни запись DLQ, ни попытки.
--
-- ЗАПУСКАТЬ РУКАМИ, не через run-case.sh: --arrange-only начинается с 00-reset.sql и
-- снесёт состояние, ради которого кейс и существует. Блок применяется psql'ем поверх
-- уже упавшего сообщения, а проверки — обычным --assert-only.
--
-- recipient — jsonb (msisdn / clientId / pushTokens), а не строка: сравнение с текстом
-- молча обновит ноль строк, и кейс станет зелёным по неверной причине.
UPDATE message
   SET recipient = jsonb_set(recipient, '{msisdn}', '"998901234500"')
 WHERE recipient ->> 'msisdn' = '998901234502';
-- @assert
SELECT status IN ('QUEUED', 'SENDING', 'SENT_TO_PROVIDER', 'DELIVERED') AS ok,
       'FAILED → QUEUED: ручной повтор заново открывает жизненный цикл (ST-02)' AS check
  FROM message;
SELECT count(*) >= 1 AS ok, 'переход зафиксирован в истории' AS check
  FROM message_status_history WHERE status = 'QUEUED'
   AND occurred_at > (SELECT min(occurred_at) FROM message_status_history WHERE status = 'FAILED');
SELECT retried_by IS NOT NULL AND retried_at IS NOT NULL AS ok,
       'в записи DLQ отмечено, кто и когда повторил' AS check
  FROM dlq_entry;
SELECT count(*) > (SELECT 5) AS ok, 'появилась новая попытка сверх исчерпанного бюджета' AS check
  FROM delivery_attempt;


-- >>> IT-STS-011  Отклонённое сообщение тоже даёт событие
-- @arrange
INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until, created_by, created_at)
VALUES ('01970000-0000-7000-8000-000000000011', 'SMS',
        encode(sha256(lower('998901234500')::bytea), 'hex'), NULL,
        'OPT_OUT', NULL, 'qa-arrange', now());
-- @assert
SELECT status = 'REJECTED' AS ok, 'сообщение отклонено' AS check FROM message;
-- Статус и причина лежат в outcome, а причина — плоской парой reason/reasonDetail
-- (в объект {code, detail} её сворачивает релей). См. шапку IT-STS-001, дефект D-21.
SELECT count(*) >= 1 AS ok, 'отказ тоже уезжает системе-источнику' AS check
  FROM outbox_event
 WHERE event_type = 'MESSAGE_STATUS' AND payload -> 'outcome' ->> 'status' = 'REJECTED';
SELECT payload -> 'outcome' ->> 'reason' = 'SUPPRESSED' AS ok,
       'каноническая причина названа в событии' AS check
  FROM outbox_event WHERE payload -> 'outcome' ->> 'status' = 'REJECTED';


-- >>> IT-STS-012  Тестовая отправка помечена
-- @arrange
-- @assert
SELECT test AS ok, 'признак теста сохранён на сообщении' AS check FROM message;
SELECT count(*) = 1 AS ok, 'тестовое сообщение видно оператору наравне с боевым' AS check
  FROM message WHERE test;
-- FR-7.4 — это измерение, а не удаление: тестовая отправка обязана остаться видимой
-- тому, кто её сделал. Фильтрация по признаку — дело дашбордов и алертов, не Модуля.
--
-- Две трети кейса лежат не в базе:
--   метрики — /actuator/prometheus, серия commhub_messages_accepted_total{test="true"}
--             обязана быть ОТДЕЛЬНОЙ от test="false", а не заменять её;
--   витрина — топик comm.outbound.events.v1, поле test у события DELIVERY_EVENT.
-- Экспорт витрины по умолчанию выключен (COMMHUB_EVENT_EXPORT_ENABLED), и включать его
-- ради этой проверки нужно ДО отправки: курсор export_cursor заводится «на сейчас», и
-- сообщения, ставшие терминальными раньше, в витрину уже не попадут — докладывать заново
-- он не умеет и не должен.
