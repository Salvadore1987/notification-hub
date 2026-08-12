-- =====================================================================================
-- IT-ING — приём и контракт IK-03 (SRS §8.1, §8.2, FR-1.1…1.4).
--
-- Формат файла: блок на кейс, блок начинается «-- >>> ID» и содержит две секции:
--   -- @arrange  предусловия сверх эталонного контура (может быть пустой)
--   -- @assert   проверочные запросы
-- Блок целиком извлекает bin/run-case.sh; руками файл читается как обычный SQL.
--
-- Обозначения в проверках: ok = true означает «кейс прошёл». Запросы с count() дают
-- строку всегда; запрос вида «SELECT status = … FROM message» даёт её только если
-- сообщение существует. Поэтому «(0 rows)» — это НЕ пройденная проверка, а
-- непройденная: объекта, о котором спрашивали, нет.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-ING-001  Приём корректного единичного сообщения
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'принято ровно одно сообщение' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT status, status_reason, selected_channel, selected_provider_code, segments
  FROM message WHERE stream_id = 'core-banking';


-- >>> IT-ING-002  Оба входа дают одинаковый результат
-- @arrange
-- @assert
-- Ожидается ровно две строки: одна пришла через REST, вторая через Kafka.
SELECT count(*) = 2 AS ok, 'приняты оба сообщения' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT count(DISTINCT (selected_channel, selected_provider_code, status, segments)) = 1 AS ok,
       'канал, провайдер, статус и сегменты совпадают' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT count(DISTINCT correlation_id) = 2 AS ok, 'correlationId у сообщений разный' AS check
  FROM message WHERE stream_id = 'core-banking';


-- >>> IT-ING-003  Отсутствует streamId
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'ни одного сообщения не создано' AS check FROM message;
-- REST: ожидается 400 problem+json с code=VALIDATION_FAILED и указателем на streamId.
-- Kafka: запись в comm.inbound.parse-error.v1 с заголовком commhub-failed-field=streamId.
-- Топик проверяется вне SQL — см. README, «Что проверяется не в базе».


-- >>> IT-ING-004  Отсутствует externalMessageId
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'ни одного сообщения не создано' AS check FROM message;


-- >>> IT-ING-005  Нет ни content, ни template
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'ни одного сообщения не создано' AS check FROM message;


-- >>> IT-ING-006  Заданы и content, и template — побеждает шаблон
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'сообщение принято' AS check FROM message;
SELECT template_code = 'OTP_RU_UZ' AS ok, 'сообщение отрендерено из шаблона' AS check
  FROM message;
SELECT template_version_id IS NOT NULL AS ok, 'зафиксирована версия шаблона' AS check
  FROM message;


-- >>> IT-ING-007  Мажорная версия схемы не 1
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'документ версии 2.0 отклонён' AS check FROM message;


-- >>> IT-ING-008  schemaVersion отсутствует или пуст
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'документ без версии принят намеренно' AS check FROM message;


-- >>> IT-ING-009  MSISDN не в формате 9989xxxxxxxx
-- @arrange
-- @assert
-- Подаются три варианта: '+998 90 123-45-00', '79001234567', '99890123450'.
SELECT count(*) = 0 AS ok, 'ни один неверный MSISDN не принят' AS check FROM message;


-- >>> IT-ING-010  Неизвестный streamId
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'сообщение неизвестного потока не создано' AS check FROM message;
SELECT count(*) = 0 AS ok, 'поток не появился сам собой' AS check
  FROM stream WHERE id = 'no-such-stream';


-- >>> IT-ING-011  Поток приостановлен
-- @arrange
-- @assert
SELECT status = 'SUSPENDED' AS ok, 'поток действительно приостановлен' AS check
  FROM stream WHERE id = 'stream-suspended';
-- Отказ STREAM_SUSPENDED наступает до создания агрегата: строки в message нет,
-- источнику приходит 409. Это не «потеря» — сообщение не было принято.
SELECT count(*) = 0 AS ok, 'сообщение не принято' AS check
  FROM message WHERE stream_id = 'stream-suspended';


-- >>> IT-ING-012  Тело не является JSON-объектом
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'нечитаемый документ сообщения не создал' AS check FROM message;


-- >>> IT-ING-013  Неизвестные поля в документе игнорируются
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'документ с лишним полем принят (NF-08)' AS check FROM message;


-- >>> IT-ING-014  Отказ конвейера не попадает в parse-error
-- @arrange
-- Адрес 998901234500 заранее подавлен: отправка на него будет штатным отказом конвейера.
INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until, created_by, created_at)
VALUES ('01930000-0000-7000-8000-000000000014', 'SMS',
        encode(sha256(lower('998901234500')::bytea), 'hex'), NULL,
        'OPT_OUT', NULL, 'qa-arrange', now());
-- @assert
SELECT status = 'REJECTED' AND status_reason = 'SUPPRESSED' AS ok,
       'отказ записан как исход сообщения, а не как отравленная запись' AS check
  FROM message;
SELECT terminal_at IS NOT NULL AS ok, 'проставлен момент терминального статуса (ST-03)' AS check
  FROM message;
-- В comm.inbound.parse-error.v1 должно быть пусто — проверяется вне SQL.


-- >>> IT-ING-015  Ограничение частоты потока (только REST)
-- @arrange
-- @assert
SELECT rate_limit_config ->> 'tps' = '2' AS ok, 'лимит потока действительно 2 rps' AS check
  FROM stream WHERE id = 'rate-limited';
-- Принятые сообщения не должны потеряться: отвергнутые лимитером не создают строк,
-- принятые создают. Проверяем, что счётчик совпал с числом ответов 202.
SELECT count(*) AS accepted_rows, 'сверить с числом ответов 202' AS check
  FROM message WHERE stream_id = 'rate-limited';


-- >>> IT-ING-016  Неподдерживаемый метод
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'состояние не изменилось' AS check FROM message;
-- Ожидается 405 + заголовок Allow, тело problem+json. Не 500.


-- >>> IT-ING-017  Неподдерживаемый Content-Type
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'состояние не изменилось' AS check FROM message;
-- Ожидается 415 + заголовок Accept.


-- >>> IT-ING-018  Недостижимый Accept
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'состояние не изменилось' AS check FROM message;
-- Ожидается 406, тело принудительно application/problem+json.


-- >>> IT-ING-019  Неизвестный путь
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'состояние не изменилось' AS check FROM message;
-- Ожидается 404 problem+json, не 500.


-- >>> IT-ING-020  Статус по идентификатору Модуля
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'сообщение на месте' AS check FROM message;
SELECT id AS message_id, status, 'вызвать GET /api/v1/messages/{id}' AS check FROM message;
SELECT count(*) >= 1 AS ok, 'история переходов не пуста' AS check FROM message_status_history;


-- >>> IT-ING-021  Статус по идентификатору источника
-- @arrange
-- @assert
SELECT stream_id, external_id, status,
       'вызвать GET /api/v1/messages?streamId=&externalMessageId=' AS check
  FROM message;
-- Без одного из параметров ожидается 400.


-- >>> IT-ING-022  Право источника на поток (SEC-01)
-- @arrange
-- Требует контура с commhub.security.require-source-system-token=true и токена,
-- у которого в claim commhub_streams только ibank-otp.
-- @assert
SELECT count(*) = 0 AS ok, 'отправка в чужой поток не создала сообщения' AS check
  FROM message WHERE stream_id = 'core-banking';
-- Ожидается 403, а не 404: скрывать существование потока от системы, которая его назвала,
-- бессмысленно, а 404 заставил бы её повторять запрос.
