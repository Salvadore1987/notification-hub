-- =====================================================================================
-- V7 — аудит действий и модель доступа админ-панели (SRS §10.1, FR-7.2, FR-7.3).
--
-- Пользователи приходят из корпоративного SSO (UI-02), пароли здесь не хранятся: app_user
-- держит только субъект OIDC и профиль, а роли назначаются либо вручную (user_role), либо
-- маппингом групп SSO (app_role.sso_group). Локальной аутентификации в системе нет.
-- =====================================================================================

CREATE TABLE app_role (
    code        text        PRIMARY KEY,
    description varchar(255) NOT NULL,
    sso_group   varchar(128),
    CONSTRAINT app_role_sso_group_uk UNIQUE (sso_group)
);

COMMENT ON TABLE app_role IS 'Роль RBAC админ-панели; права проверяются на backend (FR-7.2, UI-02).';
COMMENT ON COLUMN app_role.sso_group IS 'Группа OIDC, которая автоматически даёт эту роль; NULL — роль назначается вручную.';

INSERT INTO app_role (code, description) VALUES
    ('ADMIN',            'Полный доступ: провайдеры, маршрутизация, потоки, пользователи, kill switch'),
    ('OPERATOR',         'Управление рассылками: пауза, остановка, повтор, DLQ, suppression list'),
    ('TEMPLATE_MANAGER', 'Шаблоны: создание, редактирование, ревью и публикация версий'),
    ('ANALYST',          'Статистика и отчёты, экспорт'),
    ('VIEWER',           'Просмотр сообщений и дашборда; адреса маскируются'),
    ('SECURITY_AUDITOR', 'Журнал аудита и его экспорт');

CREATE TABLE app_user (
    id              uuid        PRIMARY KEY,
    subject         varchar(128) NOT NULL,
    username        varchar(128) NOT NULL,
    display_name    varchar(255),
    email           varchar(255),
    enabled         boolean     NOT NULL DEFAULT true,
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_subject_uk UNIQUE (subject),
    CONSTRAINT app_user_username_uk UNIQUE (username)
);

COMMENT ON TABLE app_user IS 'Пользователь админ-панели, аутентифицируемый через SSO (UI-02); пароли не хранятся.';
COMMENT ON COLUMN app_user.subject IS 'Claim sub из OIDC-токена — устойчивый идентификатор пользователя у провайдера SSO.';

CREATE TABLE user_role (
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_code   text        NOT NULL REFERENCES app_role (code),
    granted_by  varchar(128) NOT NULL,
    granted_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_code)
);

COMMENT ON TABLE user_role IS 'Назначение роли пользователю; при маппинге групп SSO заполняется синхронизацией.';

-- -------------------------------------------------------------------------------------
-- Журнал аудита (FR-7.3): append-only. Неизменяемость поддержана правами (роль приложения
-- получает только INSERT и SELECT — выдаётся при развёртывании) и триггером ниже, чтобы
-- UPDATE/DELETE не проходили даже от владельца схемы.
-- -------------------------------------------------------------------------------------

CREATE TABLE audit_log (
    id              uuid        PRIMARY KEY,
    user_id         uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    username        varchar(128) NOT NULL,
    action          text        NOT NULL,
    entity_type     text        NOT NULL,
    entity_id       varchar(128),
    before_state    jsonb,
    after_state     jsonb,
    ip              inet,
    user_agent      varchar(512),
    occurred_at     timestamptz NOT NULL
);

COMMENT ON TABLE audit_log IS 'Кто, когда и что изменил, с состоянием до и после; append-only (FR-7.3).';
COMMENT ON COLUMN audit_log.username IS 'Имя на момент действия: запись аудита остаётся читаемой после удаления пользователя.';
COMMENT ON COLUMN audit_log.action IS 'Действие в терминах домена: PROVIDER_DISABLED, TEMPLATE_PUBLISHED, MESSAGE_RETRIED…';

CREATE INDEX audit_log_occurred_idx ON audit_log (occurred_at DESC);
CREATE INDEX audit_log_user_idx ON audit_log (user_id, occurred_at DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION comm_hub.reject_audit_log_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (FR-7.3): % is not allowed', TG_OP;
END;
$$;

-- Два триггера, а не один: TRUNCATE — событие уровня таблицы и в одном определении с
-- UPDATE/DELETE не объявляется.
CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH STATEMENT
    EXECUTE FUNCTION comm_hub.reject_audit_log_mutation();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT
    EXECUTE FUNCTION comm_hub.reject_audit_log_mutation();
