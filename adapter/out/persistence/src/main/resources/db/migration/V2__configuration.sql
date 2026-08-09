-- =====================================================================================
-- V2 — конфигурация маршрутизации: потоки, каналы, провайдеры, политики (SRS §10.1).
-- Всё редактируется из админ-панели и применяется без рестарта (AD-07, NF-07),
-- поэтому таблицы держат только конфигурацию, а не рантайм-состояние отправки.
--
-- Отступления от логической схемы §10.1 (осознанные, домен — источник истины):
--   * provider.maintenance вынесен отдельным флагом (домен различает «выключен» и
--     «на обслуживании»: Provider.isSelectable);
--   * stream.default_priority добавлен — Stream.Defaults хранит приоритет (TC-02);
--   * routing_policy.scope вычисляется из match и хранится денормализовано для
--     фильтров админ-панели.
-- =====================================================================================

CREATE TABLE provider (
    id                  uuid        PRIMARY KEY,
    code                varchar(32) NOT NULL,
    channel             text        NOT NULL,
    adapter_type        text        NOT NULL,
    weight              integer     NOT NULL DEFAULT 10,
    tariff              jsonb,
    rate_limit_config   jsonb,
    endpoint_config     jsonb,
    credentials_ref     text,
    enabled             boolean     NOT NULL DEFAULT true,
    maintenance         boolean     NOT NULL DEFAULT false,
    health_status       text        NOT NULL DEFAULT 'UNKNOWN',
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT provider_code_uk UNIQUE (code),
    CONSTRAINT provider_channel_ck CHECK (channel IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT provider_weight_ck CHECK (weight > 0 AND weight <= 100),
    CONSTRAINT provider_health_ck CHECK (health_status IN ('UP', 'DEGRADED', 'DOWN', 'UNKNOWN'))
);

COMMENT ON TABLE provider IS 'Профиль интеграции с провайдером доставки (FR-2.1, FR-2.5, AR-04).';
COMMENT ON COLUMN provider.adapter_type IS 'Ключ адаптера: playmobile-http, smsgate-http, smtp, fcm, apns (AR-04).';
COMMENT ON COLUMN provider.credentials_ref IS 'Ссылка на секрет в Vault/K8s; сами креды в БД не хранятся (SEC-04).';
COMMENT ON COLUMN provider.endpoint_config IS 'Настройки транспорта адаптера; заполняется с Phase 7.';

CREATE INDEX provider_channel_idx ON provider (channel) WHERE enabled;

CREATE TABLE channel (
    code                text        PRIMARY KEY,
    status              text        NOT NULL DEFAULT 'ACTIVE',
    balancing_strategy  text        NOT NULL,
    fallback_order      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    quiet_hours         jsonb,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT channel_code_ck CHECK (code IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT channel_status_ck CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'DISABLED')),
    CONSTRAINT channel_strategy_ck CHECK (
        balancing_strategy IN ('ROUND_ROBIN', 'WEIGHTED', 'LEAST_COST', 'PRIMARY_ONLY')),
    CONSTRAINT channel_fallback_order_ck CHECK (jsonb_typeof(fallback_order) = 'array')
);

COMMENT ON TABLE channel IS 'Агрегат Channel из §6.1 (в коде — ChannelConfig): статус, балансировка, резерв (FR-2.2, FR-2.3).';
COMMENT ON COLUMN channel.fallback_order IS 'Упорядоченный массив provider.code: первый — основной, дальше резерв (FR-2.2).';

CREATE TABLE stream (
    id                      varchar(64) PRIMARY KEY,
    name                    varchar(128) NOT NULL,
    integration_type        text        NOT NULL,
    status                  text        NOT NULL DEFAULT 'ACTIVE',
    default_channel         text,
    default_provider_id     uuid        REFERENCES provider (id) ON DELETE SET NULL,
    default_traffic_class   text,
    default_priority        text,
    quota_config            jsonb,
    quiet_hours             jsonb,
    credentials_ref         text,
    last_activity_at        timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT stream_integration_type_ck CHECK (integration_type IN ('KAFKA', 'REST')),
    CONSTRAINT stream_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT stream_default_channel_ck CHECK (default_channel IS NULL OR default_channel IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT stream_default_traffic_class_ck CHECK (
        default_traffic_class IS NULL OR default_traffic_class IN ('CRITICAL_OTP', 'TRANSACTIONAL', 'NOTIFICATION'))
);

COMMENT ON TABLE stream IS 'Зарегистрированная система-источник (§18.4, FR-1.3, FR-2.4).';
COMMENT ON COLUMN stream.last_activity_at IS 'Последняя активность; из неё выводится ConnectionStatus (FR-1.3).';

CREATE TABLE routing_policy (
    id          uuid        PRIMARY KEY,
    scope       text        NOT NULL,
    match       jsonb       NOT NULL,
    action      jsonb       NOT NULL,
    priority    integer     NOT NULL DEFAULT 0,
    enabled     boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT routing_policy_scope_ck CHECK (scope IN ('GLOBAL', 'STREAM', 'CHANNEL', 'TRAFFIC_CLASS')),
    CONSTRAINT routing_policy_priority_ck CHECK (priority >= 0)
);

COMMENT ON TABLE routing_policy IS 'Декларативное правило маршрутизации; побеждает первое совпадение по убыванию priority (FR-8.9).';

CREATE INDEX routing_policy_priority_idx ON routing_policy (priority DESC) WHERE enabled;
