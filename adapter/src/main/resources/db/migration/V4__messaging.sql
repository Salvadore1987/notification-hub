-- =====================================================================================
-- V4 — рантайм отправки: батчи, сообщения, история статусов, попытки доставки (SRS §10.1).
--
-- message, message_status_history и delivery_attempt секционированы по диапазону времени
-- (DB-02); партиции месячные — при 1,2–1,5 млн сообщений/сутки (DB-05) месяц даёт ~40 млн
-- строк на партицию, что ещё держит индексы в памяти, а отцепление идёт одним ALTER.
-- Создание/отсоединение партиций автоматизируется в V6 (comm_hub.ensure_partitions).
--
-- Отступления от логической схемы §10.1 (домен — источник истины):
--   * контент хранится в message.contents jsonb — MessageContents держит контент на канал
--     (MP-02), а не один MessageContent;
--   * message.selected_channel/selected_provider_id — результат маршрутизации, в §10.1 он
--     не выделен, но без него карточку сообщения не собрать (FR-2.2, FR-6.3);
--   * message_status_history.details — текст ответа провайдера к статусу (ST-01);
--   * delivery_attempt.result — исход попытки (AttemptResult), отдельно от response_code.
-- =====================================================================================

CREATE TABLE batch (
    id              uuid        PRIMARY KEY,
    stream_id       varchar(64) NOT NULL REFERENCES stream (id),
    channel         text        NOT NULL,
    status          text        NOT NULL DEFAULT 'ACCEPTED',
    total           bigint      NOT NULL DEFAULT 0,
    processed       bigint      NOT NULL DEFAULT 0,
    sent            bigint      NOT NULL DEFAULT 0,
    delivered       bigint      NOT NULL DEFAULT 0,
    failed          bigint      NOT NULL DEFAULT 0,
    cost_estimate   numeric(18, 4),
    cost_currency   char(3),
    timing          jsonb,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT batch_channel_ck CHECK (channel IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT batch_status_ck CHECK (status IN ('ACCEPTED', 'PROCESSING', 'PAUSED', 'STOPPED', 'COMPLETED')),
    CONSTRAINT batch_counters_ck CHECK (total >= 0 AND processed >= 0 AND sent >= 0 AND delivered >= 0 AND failed >= 0),
    CONSTRAINT batch_cost_currency_ck CHECK ((cost_estimate IS NULL) = (cost_currency IS NULL))
);

COMMENT ON TABLE batch IS 'Массовая рассылка с загруженным списком получателей (FR-1.6, FR-3.1).';
COMMENT ON COLUMN batch.total IS 'Ожидаемое число элементов; 0, пока элементы догружаются частями (FR-1.6).';
COMMENT ON COLUMN batch.timing IS 'Timing батча: sendAt, ttl, quietHoursOverride (FR-1.4).';

CREATE INDEX batch_stream_created_idx ON batch (stream_id, created_at DESC);
CREATE INDEX batch_active_idx ON batch (created_at DESC) WHERE status IN ('ACCEPTED', 'PROCESSING', 'PAUSED');

-- -------------------------------------------------------------------------------------
-- message — секционируется по accepted_at (DB-02). Первичный ключ включает ключ
-- секционирования, как того требует PostgreSQL; идентичность сообщения — по id (UUIDv7).
-- -------------------------------------------------------------------------------------

CREATE TABLE message (
    id                      uuid        NOT NULL,
    accepted_at             timestamptz NOT NULL,
    external_id             varchar(64) NOT NULL,
    stream_id               varchar(64) NOT NULL,
    batch_id                uuid,
    traffic_class           text        NOT NULL,
    priority                text        NOT NULL,
    dedup_key               varchar(128) NOT NULL,
    correlation_id          varchar(64) NOT NULL,
    recipient               jsonb       NOT NULL,
    channel_plan            jsonb       NOT NULL,
    contents                jsonb       NOT NULL,
    template_id             uuid,
    template_version_id     uuid,
    template_variables      jsonb,
    timing                  jsonb,
    status                  text        NOT NULL,
    status_reason           text,
    selected_channel        text,
    selected_provider_id    uuid,
    selected_provider_code  varchar(32),
    segments                integer     NOT NULL DEFAULT 0,
    cost                    numeric(18, 4),
    cost_currency           char(3),
    duplicate_of            uuid,
    test                    boolean     NOT NULL DEFAULT false,
    terminal_at             timestamptz,
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT message_pk PRIMARY KEY (id, accepted_at),
    CONSTRAINT message_traffic_class_ck CHECK (
        traffic_class IN ('CRITICAL_OTP', 'TRANSACTIONAL', 'NOTIFICATION')),
    CONSTRAINT message_priority_ck CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'REALTIME')),
    CONSTRAINT message_status_ck CHECK (status IN (
        'ACCEPTED', 'VALIDATED', 'ROUTED', 'QUEUED', 'SENDING', 'SENT_TO_PROVIDER', 'RETRYING',
        'DELIVERED', 'UNDELIVERED', 'EXPIRED', 'REJECTED', 'DUPLICATE', 'CANCELLED', 'FAILED')),
    CONSTRAINT message_selected_channel_ck CHECK (
        selected_channel IS NULL OR selected_channel IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT message_segments_ck CHECK (segments >= 0),
    CONSTRAINT message_cost_currency_ck CHECK ((cost IS NULL) = (cost_currency IS NULL))
) PARTITION BY RANGE (accepted_at);

COMMENT ON TABLE message IS 'Каноническое сообщение — единица доставки (§5.2, §6.1, MP-01). Секции по accepted_at (DB-02).';
COMMENT ON COLUMN message.contents IS 'MessageContents: контент на канал (MP-02), а не один MessageContent.';
COMMENT ON COLUMN message.recipient IS 'Адреса получателя на канал + clientId; PII (DB-04), в логах и UI маскируется.';
COMMENT ON COLUMN message.channel_plan IS 'ChannelPlan: режим выбора канала и упорядоченные кандидаты (MP-03).';
COMMENT ON COLUMN message.status_reason IS 'RejectionReason терминального отказа (IR-01); список расширяется, CHECK намеренно нет.';
COMMENT ON COLUMN message.duplicate_of IS 'Оригинал, из-за которого сообщение получило статус DUPLICATE (FR-1.5).';
COMMENT ON COLUMN message.terminal_at IS 'Момент перехода в терминальный статус; обязателен для терминальных (ST-03).';

-- Индексы обязательного набора DB-05. Все определяются на партиционированной таблице,
-- поэтому создаются автоматически на каждой новой партиции.
CREATE INDEX message_stream_accepted_idx ON message (stream_id, accepted_at DESC);
CREATE INDEX message_external_id_idx ON message (external_id, stream_id);
CREATE INDEX message_batch_idx ON message (batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX message_dedup_key_idx ON message (dedup_key);
CREATE INDEX message_correlation_idx ON message (correlation_id);

-- Частичный индекс по нетерминальным статусам (DB-05): по нему ходят досыл, ретраи и
-- поиск «зависших» сообщений, а он остаётся маленьким — терминальные строки в него не входят.
CREATE INDEX message_pending_idx ON message (status, accepted_at)
    WHERE status IN ('ACCEPTED', 'VALIDATED', 'ROUTED', 'QUEUED', 'SENDING', 'SENT_TO_PROVIDER', 'RETRYING');

CREATE INDEX message_recipient_gin_idx ON message USING gin (recipient jsonb_path_ops);

-- -------------------------------------------------------------------------------------
-- message_status_history — таймлайн статусов, секционируется вместе с message (§10.1).
-- FK на message нет: ссылка на секционированную таблицу требовала бы (message_id,
-- accepted_at) и мешала отцеплять партиции независимо; целостность держит приложение.
-- -------------------------------------------------------------------------------------

CREATE TABLE message_status_history (
    id              uuid        NOT NULL,
    message_id      uuid        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    status          text        NOT NULL,
    reason          text,
    details         varchar(1024),
    actor_type      text        NOT NULL,
    actor_id        varchar(128),
    provider_code   varchar(32),
    CONSTRAINT message_status_history_pk PRIMARY KEY (id, occurred_at),
    CONSTRAINT message_status_history_status_ck CHECK (status IN (
        'ACCEPTED', 'VALIDATED', 'ROUTED', 'QUEUED', 'SENDING', 'SENT_TO_PROVIDER', 'RETRYING',
        'DELIVERED', 'UNDELIVERED', 'EXPIRED', 'REJECTED', 'DUPLICATE', 'CANCELLED', 'FAILED')),
    CONSTRAINT message_status_history_actor_ck CHECK (
        actor_type IN ('SYSTEM', 'OPERATOR', 'PROVIDER', 'SOURCE_SYSTEM'))
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE message_status_history IS 'Полная история переходов статуса, append-only (ST-01). Секции по occurred_at (DB-02).';
COMMENT ON COLUMN message_status_history.actor_type IS 'Кто инициировал переход: система, оператор, провайдер, система-источник.';

CREATE INDEX message_status_history_message_idx ON message_status_history (message_id, occurred_at);

-- -------------------------------------------------------------------------------------
-- delivery_attempt — попытки обращения к провайдеру, секционируются по request_at (DB-02).
-- -------------------------------------------------------------------------------------

CREATE TABLE delivery_attempt (
    id                      uuid        NOT NULL,
    message_id              uuid        NOT NULL,
    request_at              timestamptz NOT NULL,
    provider_id             uuid        NOT NULL,
    provider_code           varchar(32) NOT NULL,
    provider_message_id     varchar(64),
    attempt_no              integer     NOT NULL,
    result                  text        NOT NULL DEFAULT 'PENDING',
    response_code           varchar(64),
    response_at             timestamptz,
    latency_ms              integer,
    error_class             text,
    error_description       varchar(1024),
    CONSTRAINT delivery_attempt_pk PRIMARY KEY (id, request_at),
    CONSTRAINT delivery_attempt_no_ck CHECK (attempt_no > 0),
    CONSTRAINT delivery_attempt_result_ck CHECK (result IN ('PENDING', 'ACCEPTED', 'REJECTED', 'ERROR', 'TIMEOUT')),
    CONSTRAINT delivery_attempt_error_class_ck CHECK (
        error_class IS NULL OR error_class IN ('RETRYABLE', 'NON_RETRYABLE', 'BLOCKING')),
    CONSTRAINT delivery_attempt_response_ck CHECK (
        (result = 'PENDING') = (response_at IS NULL)),
    CONSTRAINT delivery_attempt_latency_ck CHECK (latency_ms IS NULL OR latency_ms >= 0)
) PARTITION BY RANGE (request_at);

COMMENT ON TABLE delivery_attempt IS 'Попытка передачи сообщения провайдеру (§10.1, PR-01). Секции по request_at (DB-02).';
COMMENT ON COLUMN delivery_attempt.provider_message_id IS 'Идентификатор на стороне провайдера; Playmobile message-id ≤ 20 символов (§18.1).';
COMMENT ON COLUMN delivery_attempt.latency_ms IS 'response_at − request_at; денормализовано ради отчётов о латентности (NF-01).';
COMMENT ON COLUMN delivery_attempt.error_class IS 'Классификация ошибки: retryable / non-retryable / blocking (PM-01, §18.1).';

CREATE INDEX delivery_attempt_message_idx ON delivery_attempt (message_id, attempt_no);
CREATE INDEX delivery_attempt_provider_msg_idx ON delivery_attempt (provider_code, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
CREATE INDEX delivery_attempt_provider_idx ON delivery_attempt (provider_id, request_at DESC);
