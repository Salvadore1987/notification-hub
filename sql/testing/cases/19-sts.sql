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
SELECT count(*) >= 1 AS ok, 'событие создано' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
-- Все четырнадцать полей §6.4 обязаны присутствовать, отсутствующие — явными null.
SELECT bool_and(payload ?& array[
        'schemaVersion','eventId','occurredAt','streamId','batchId','messageId',
        'externalMessageId','channel','provider','status','providerStatus','reason',
        'segments','correlationId']) AS ok,
       'все поля §6.4 на месте' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
SELECT bool_and(payload ->> 'schemaVersion' = '1.0') AS ok, 'версия схемы зафиксирована' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
SELECT jsonb_pretty(payload) FROM outbox_event WHERE event_type = 'MESSAGE_STATUS' LIMIT 1;


-- >>> IT-STS-002  Ключ раздела — идентификатор сообщения
-- @arrange
-- @assert
SELECT count(DISTINCT aggregate_id) = 1 AS ok, 'все события — про одно сообщение' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
-- Порядок в базе задаётся created_at; в топике он сохранится, потому что ключ раздела —
-- messageId, и все события сообщения попадают в один раздел.
SELECT array_agg(payload ->> 'status' ORDER BY created_at) AS порядок_в_outbox
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';
-- Порядок в самом топике проверяется вычиткой раздела — см. README.


-- >>> IT-STS-003  Заголовки события
-- @arrange
-- @assert
SELECT count(*) >= 1 AS ok, 'событие есть' AS check FROM outbox_event;
SELECT id AS commhub_event_id, event_type AS commhub_event_type,
       payload ->> 'streamId' AS commhub_stream_id,
       payload ->> 'schemaVersion' AS commhub_schema_version,
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
-- Сообщение уже доставлено; приходит отчёт, требующий SENDING.
-- @assert
SELECT status = 'DELIVERED' AS ok, 'статус не сдвинулся назад' AS check FROM message;
SELECT count(*) = 0 AS ok, 'невозможный переход в историю не попал' AS check
  FROM message_status_history WHERE status = 'SENDING'
   AND occurred_at > (SELECT max(occurred_at) FROM message_status_history WHERE status = 'DELIVERED');
-- Наружу ошибки нет: провайдер прислал то, что прислал, и это не повод отвечать ему 500.


-- >>> IT-STS-009  Событие DLQ
-- @arrange
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
-- Сообщение уже в FAILED с записью DLQ (продолжение IT-STS-009). Предусловий нет:
-- повтор запускается use case'ом, а не правкой строки, — иначе кейс проверил бы UPDATE.
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
SELECT count(*) >= 1 AS ok, 'отказ тоже уезжает системе-источнику' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS' AND payload ->> 'status' = 'REJECTED';
SELECT payload -> 'reason' ->> 'code' = 'SUPPRESSED' AS ok,
       'каноническая причина названа в событии' AS check
  FROM outbox_event WHERE payload ->> 'status' = 'REJECTED';


-- >>> IT-STS-012  Тестовая отправка помечена
-- @arrange
-- @assert
SELECT test AS ok, 'признак теста сохранён на сообщении' AS check FROM message;
SELECT count(*) = 1 AS ok, 'тестовое сообщение видно оператору наравне с боевым' AS check
  FROM message WHERE test;
-- FR-7.4 — это измерение, а не удаление: тестовая отправка обязана остаться видимой
-- тому, кто её сделал. Фильтрация по признаку — дело дашбордов и алертов, не Модуля.
