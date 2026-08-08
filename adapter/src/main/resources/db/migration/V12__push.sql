-- =====================================================================================
-- V12 — Push-канал (SRS §9.4, PU-01…PU-13).
--
-- Каналу нужна от схемы ровно одна вещь, которой ещё нет: детальный статус по каждому
-- токену получателя (PU-09). Всё остальное уже есть и переиспользуется:
--   * инвалидация токена (PU-04, PU-08) ложится в suppression_list с новой причиной
--     PUSH_TOKEN_INVALID — «сюда больше не писать» это то же утверждение об адресе, что
--     hard bounce письма (EM-02), и проверяется тем же фильтром (FR-5.1);
--   * событие push-token.invalidated уходит через существующий outbox_event с новым
--     event_type — другого способа что-либо опубликовать у Hub'а нет (AD-03);
--   * попытка отправки остаётся одной строкой delivery_attempt: это один ход саги, и его
--     исход — исход сообщения. Строки ниже лежат под ней, по одной на устройство.
--
-- Почему отдельная таблица, а не колонка в delivery_attempt: у одной попытки push'а
-- несколько адресов и несколько ответов сразу, а не один. Расширять delivery_attempt
-- значило бы либо заводить строку-попытку на устройство (и тогда «попытка» перестанет
-- значить «ход саги» для всех каналов), либо складывать ответы устройств в текст, из
-- которого нельзя посчитать «сколько iOS-устройств отказало за сутки» — а это ровно тот
-- вопрос, ради которого PU-09 требует детальный статус.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- push_delivery — что вышло с каждым устройством (PU-09).
--
-- Секционируется по времени, как delivery_attempt: прогноз §12.2 — ~17,2 млн push/мес,
-- и при мультитокенности это строки того же порядка, что и сообщения. Обслуживание —
-- та же comm_hub.ensure_partitions (DB-02), список секционированных таблиц расширен ниже.
--
-- Токен — только хеш (DB-04). Открытый токен нужен ровно одному месту — вызову
-- платформы — и живёт в контенте сообщения; в архивной таблице он не нужен никому,
-- а платформа рядом с хешем нужна: «отказывают все iOS» — это диагноз, а хеш его не даёт.
-- -------------------------------------------------------------------------------------

CREATE TABLE push_delivery (
    id                      uuid        NOT NULL,
    message_id              uuid        NOT NULL,
    request_at              timestamptz NOT NULL,
    attempt_id              uuid        NOT NULL,
    provider_id             uuid        NOT NULL,
    provider_code           varchar(32) NOT NULL,
    provider_adapter_type   varchar(32) NOT NULL,
    token_hash              varchar(64) NOT NULL,
    platform                text        NOT NULL,
    provider_message_id     varchar(128),
    result                  text        NOT NULL,
    response_code           varchar(64),
    error_description       varchar(1024),
    token_invalidated       boolean     NOT NULL DEFAULT false,
    CONSTRAINT push_delivery_pk PRIMARY KEY (id, request_at),
    CONSTRAINT push_delivery_platform_ck CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    CONSTRAINT push_delivery_result_ck CHECK (result IN ('PENDING', 'ACCEPTED', 'REJECTED', 'ERROR', 'TIMEOUT'))
) PARTITION BY RANGE (request_at);

COMMENT ON TABLE push_delivery IS
    'Исход отправки push на одно устройство получателя (PU-09). Секции по request_at (DB-02).';
COMMENT ON COLUMN push_delivery.attempt_id IS
    'Попытка саги (delivery_attempt.id), в рамках которой шёл fan-out; строк на попытку столько, сколько устройств.';
COMMENT ON COLUMN push_delivery.token_hash IS 'SHA-256 токена (DB-04); тот же хеш, что в suppression_list.';
COMMENT ON COLUMN push_delivery.token_invalidated IS
    'Этот ответ платформы и вывел токен из реестра доставки (PU-04, PU-08).';
COMMENT ON COLUMN push_delivery.provider_message_id IS
    'Идентификатор доставки на стороне платформы: name FCM (projects/*/messages/*) или apns-id.';

-- Карточка сообщения: «на какие устройства ушло и что каждое ответило» (§11.2).
CREATE INDEX push_delivery_message_idx ON push_delivery (message_id, request_at);
-- Статистика по платформе и провайдеру (PU-09, FR-6.1) и разбор «отказывают все iOS».
CREATE INDEX push_delivery_provider_idx ON push_delivery (provider_code, platform, request_at DESC);
-- «Это устройство ещё живо?» — история по токену; частичный, инвалидаций мало.
CREATE INDEX push_delivery_invalidated_idx ON push_delivery (token_hash, request_at DESC)
    WHERE token_invalidated;

-- Секция по умолчанию — та же страховка, что и у остальных секционированных таблиц:
-- непустая DEFAULT-секция означает, что планировщик не создал очередную месячную (DB-02).
CREATE TABLE push_delivery_default PARTITION OF push_delivery DEFAULT;

-- -------------------------------------------------------------------------------------
-- Обслуживание секций: единственный источник правды — comm_hub.partitioned_tables().
-- -------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION comm_hub.partitioned_tables()
    RETURNS text[]
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT ARRAY['message', 'message_status_history', 'delivery_attempt', 'outbox_event', 'push_delivery'];
$$;

COMMENT ON FUNCTION comm_hub.partitioned_tables() IS
    'Перечень секционированных по времени таблиц (DB-02); единственный источник правды для обслуживания.';

-- Секции текущего и ближайших месяцев для новой таблицы — иначе всё уедет в DEFAULT.
SELECT comm_hub.ensure_partitions(2);

-- -------------------------------------------------------------------------------------
-- Причина PUSH_TOKEN_INVALID в suppression_list (PU-04, PU-08).
--
-- Отдельная причина, а не PROVIDER_BLACKLIST: удалённое приложение — не отказ доставлять,
-- и читаются они по-разному. Чёрный список номера — вопрос к оператору связи, мёртвый
-- токен — строка, которую система-источник обязана убрать у себя.
-- -------------------------------------------------------------------------------------

ALTER TABLE suppression_list
    DROP CONSTRAINT IF EXISTS suppression_reason_ck;

ALTER TABLE suppression_list
    ADD CONSTRAINT suppression_reason_ck CHECK (reason IN (
        'OPT_OUT', 'COMPLAINT', 'HARD_BOUNCE', 'DELIVERY_FAILURES',
        'PROVIDER_BLACKLIST', 'PUSH_TOKEN_INVALID', 'MANUAL'));

-- -------------------------------------------------------------------------------------
-- Новый тип события outbox: push-token.invalidated (PU-04).
-- -------------------------------------------------------------------------------------

ALTER TABLE outbox_event
    DROP CONSTRAINT IF EXISTS outbox_event_type_ck;

ALTER TABLE outbox_event
    ADD CONSTRAINT outbox_event_type_ck CHECK (event_type IN (
        'MESSAGE_STATUS', 'MESSAGE_DLQ', 'PUSH_TOKEN_INVALIDATED'));

COMMENT ON COLUMN outbox_event.event_type IS
    'Тип события; он же выбирает исходящий топик: статус (§6.4), DLQ (FR-3.3), инвалидация push-токена (PU-04).';
