-- =====================================================================================
-- V3 — шаблоны и их локализованные версии (SRS §10.1, FR-4.1…FR-4.6).
-- Maker/checker: опубликовать версию может только не-автор (FR-4.2) — правило доменное,
-- в схеме остаются created_by/reviewed_by, по которым его можно проверить и в аудите.
--
-- Дополнение к §10.1: template_provider_mapping — соответствие шаблону на стороне
-- провайдера (Playmobile template-id) и состояние его согласования (FR-4.5).
-- =====================================================================================

CREATE TABLE template (
    id          uuid        PRIMARY KEY,
    code        varchar(64) NOT NULL,
    channel     text        NOT NULL,
    direction   varchar(64),
    owner       varchar(128),
    status      text        NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT template_code_uk UNIQUE (code),
    CONSTRAINT template_channel_ck CHECK (channel IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT template_status_ck CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

COMMENT ON TABLE template IS 'Каталог шаблонов; код уникален по всему каталогу (FR-4.1, FR-4.6).';
COMMENT ON COLUMN template.direction IS 'Бизнес-направление Банка: МСБ, Чакана, Ундирув… (§18.4).';
COMMENT ON COLUMN template.status IS 'Административный статус карточки шаблона; отправку определяет статус версии.';

CREATE INDEX template_channel_idx ON template (channel);

CREATE TABLE template_version (
    id              uuid        PRIMARY KEY,
    template_id     uuid        NOT NULL REFERENCES template (id) ON DELETE CASCADE,
    version         integer     NOT NULL,
    locale          text        NOT NULL,
    subject         text,
    body            text        NOT NULL,
    variables       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status          text        NOT NULL DEFAULT 'DRAFT',
    created_by      varchar(128) NOT NULL,
    reviewed_by     varchar(128),
    published_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT template_version_uk UNIQUE (template_id, locale, version),
    CONSTRAINT template_version_number_ck CHECK (version > 0),
    CONSTRAINT template_version_locale_ck CHECK (locale IN ('RU', 'UZ', 'EN')),
    CONSTRAINT template_version_status_ck CHECK (status IN ('DRAFT', 'ON_REVIEW', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT template_version_published_ck CHECK (status <> 'PUBLISHED' OR reviewed_by IS NOT NULL),
    CONSTRAINT template_version_variables_ck CHECK (jsonb_typeof(variables) = 'array')
);

COMMENT ON TABLE template_version IS 'Локализованная версия шаблона с merge-полями {NAME} (FR-4.1…FR-4.3).';
COMMENT ON COLUMN template_version.variables IS 'Объявленные merge-поля версии; выводятся из тела при сохранении.';

CREATE INDEX template_version_published_idx
    ON template_version (template_id, locale, version DESC)
    WHERE status = 'PUBLISHED';

CREATE TABLE template_provider_mapping (
    template_id             uuid        NOT NULL REFERENCES template (id) ON DELETE CASCADE,
    provider_code           varchar(32) NOT NULL,
    provider_template_id    varchar(64) NOT NULL,
    approved                boolean     NOT NULL DEFAULT false,
    updated_at              timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, provider_code)
);

COMMENT ON TABLE template_provider_mapping IS 'Соответствие шаблону, зарегистрированному у провайдера, и его согласование (FR-4.5).';
