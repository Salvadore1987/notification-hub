-- =====================================================================================
-- V5 — гарантии доставки и фильтрация: outbox, DLQ, dedup-реестр, suppression, квоты
-- (SRS §10.1, AD-03, FR-1.5, FR-5.1, FR-2.6).
--
-- outbox_event секционируется по created_at (DB-02); остальные таблицы этого файла живут
-- в объёме окна (dedup — 24 ч, quota — период, suppression — актуальный список), их
-- секционировать незачем.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Transactional Outbox (AD-03): запись события в одной транзакции с бизнес-изменением,
-- relay из Phase 5 публикует его в Kafka и проставляет published_at.
-- -------------------------------------------------------------------------------------

CREATE TABLE outbox_event (
    id              uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    aggregate_type  varchar(32) NOT NULL,
    aggregate_id    varchar(64) NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    published_at    timestamptz,
    attempts        integer     NOT NULL DEFAULT 0,
    last_error      varchar(1024),
    CONSTRAINT outbox_event_pk PRIMARY KEY (id, created_at),
    CONSTRAINT outbox_event_type_ck CHECK (event_type IN ('MESSAGE_STATUS', 'MESSAGE_DLQ')),
    CONSTRAINT outbox_event_attempts_ck CHECK (attempts >= 0)
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE outbox_event IS 'Transactional Outbox → Kafka, at-least-once (AD-03). Секции по created_at (DB-02).';
COMMENT ON COLUMN outbox_event.aggregate_id IS 'Идентификатор агрегата; он же ключ партиционирования в Kafka — порядок по сообщению сохраняется.';
COMMENT ON COLUMN outbox_event.attempts IS 'Число неудачных публикаций; растёт только при ошибке relay.';

-- Очередь к публикации: relay читает строго по этому индексу, поэтому он частичный —
-- опубликованные события в него не входят и не раздувают выборку.
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at)
    WHERE published_at IS NULL;

CREATE INDEX outbox_event_aggregate_idx ON outbox_event (aggregate_type, aggregate_id, created_at);

-- -------------------------------------------------------------------------------------
-- DLQ: сообщение, исчерпавшее попытки или отвергнутое безвозвратно (FR-6.4).
-- Ключ — message_id: одно сообщение попадает в DLQ один раз, повтор меняет retried_*.
-- -------------------------------------------------------------------------------------

CREATE TABLE dlq_entry (
    message_id      uuid        PRIMARY KEY,
    reason          text        NOT NULL,
    last_error      varchar(1024),
    moved_at        timestamptz NOT NULL,
    retried_by      varchar(128),
    retried_at      timestamptz,
    archived        boolean     NOT NULL DEFAULT false,
    CONSTRAINT dlq_entry_retry_ck CHECK ((retried_by IS NULL) = (retried_at IS NULL))
);

COMMENT ON TABLE dlq_entry IS 'Запись DLQ с возможностью ручного повтора из админ-панели (FR-6.4).';
COMMENT ON COLUMN dlq_entry.reason IS 'RejectionReason, с которым сообщение ушло в DLQ (IR-01).';

CREATE INDEX dlq_entry_pending_idx ON dlq_entry (moved_at DESC) WHERE NOT archived AND retried_at IS NULL;

-- -------------------------------------------------------------------------------------
-- Реестр дедупликации (FR-1.5): ключ живёт в окне (по умолчанию 24 ч), после чего
-- вычищается по expires_at. Уникальность dedup_key и есть механизм идемпотентности —
-- вставка-конфликт возвращает исходный message_id.
-- -------------------------------------------------------------------------------------

CREATE TABLE dedup_registry (
    dedup_key       varchar(128) PRIMARY KEY,
    message_id      uuid        NOT NULL,
    registered_at   timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    CONSTRAINT dedup_registry_window_ck CHECK (expires_at > registered_at)
);

COMMENT ON TABLE dedup_registry IS 'Окно дедупликации по dedupKey → оригинальное сообщение (FR-1.5, AD-03).';

CREATE INDEX dedup_registry_expires_idx ON dedup_registry (expires_at);

-- -------------------------------------------------------------------------------------
-- Suppression list (FR-5.1): поиск идёт по хешу адреса, сам адрес не хранится (DB-04).
-- Запись адресная либо клиентская — ровно одна из двух (SuppressionEntry.forAddress /
-- forClient), это и фиксирует CHECK.
-- -------------------------------------------------------------------------------------

CREATE TABLE suppression_list (
    id              uuid        PRIMARY KEY,
    channel         text        NOT NULL,
    address_hash    varchar(64),
    client_id       varchar(64),
    reason          text        NOT NULL,
    source          varchar(64),
    valid_until     timestamptz,
    created_by      varchar(128) NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT suppression_channel_ck CHECK (channel IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT suppression_reason_ck CHECK (reason IN (
        'OPT_OUT', 'COMPLAINT', 'HARD_BOUNCE', 'DELIVERY_FAILURES', 'PROVIDER_BLACKLIST', 'MANUAL')),
    CONSTRAINT suppression_target_ck CHECK (num_nonnulls(address_hash, client_id) = 1)
);

COMMENT ON TABLE suppression_list IS 'Список подавления: адреса и клиенты, которым отправка запрещена (FR-5.1).';
COMMENT ON COLUMN suppression_list.address_hash IS 'Хеш адреса (DB-04); сам номер/email в таблицу не попадает.';
COMMENT ON COLUMN suppression_list.valid_until IS 'Срок действия записи; NULL — бессрочно.';

CREATE UNIQUE INDEX suppression_address_uk ON suppression_list (channel, address_hash)
    WHERE address_hash IS NOT NULL;
CREATE UNIQUE INDEX suppression_client_uk ON suppression_list (channel, client_id)
    WHERE client_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- Счётчики квот (FR-2.6). Область действия — поток, канал и/или провайдер; NULL означает
-- «любой», поэтому уникальность строится по COALESCE-выражениям, а не по (scope, period).
-- -------------------------------------------------------------------------------------

CREATE TABLE quota_counter (
    id              uuid        PRIMARY KEY,
    stream_id       varchar(64),
    channel         text,
    provider_id     uuid,
    window_type     text        NOT NULL,
    period_start    date        NOT NULL,
    counter         bigint      NOT NULL DEFAULT 0,
    cost_counter    numeric(18, 4) NOT NULL DEFAULT 0,
    cost_currency   char(3),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT quota_counter_channel_ck CHECK (channel IS NULL OR channel IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT quota_counter_window_ck CHECK (window_type IN ('DAY', 'MONTH')),
    CONSTRAINT quota_counter_scope_ck CHECK (num_nonnulls(stream_id, channel, provider_id) > 0),
    CONSTRAINT quota_counter_values_ck CHECK (counter >= 0 AND cost_counter >= 0)
);

COMMENT ON TABLE quota_counter IS 'Счётчики количества и стоимости по области и периоду (FR-2.6).';
COMMENT ON COLUMN quota_counter.period_start IS 'Начало окна: сутки для DAY, первое число месяца для MONTH (Asia/Tashkent).';

CREATE UNIQUE INDEX quota_counter_scope_uk ON quota_counter (
    COALESCE(stream_id, ''),
    COALESCE(channel, ''),
    COALESCE(provider_id, '00000000-0000-0000-0000-000000000000'::uuid),
    window_type,
    period_start);
