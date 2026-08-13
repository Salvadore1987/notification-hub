-- =====================================================================================
-- 01-contour.sql — эталонный контур набора (§2 INTEGRATION-TEST-SPEC.md).
--
-- Идемпотентен: применяется перед каждым кейсом, поэтому кейс, которому нужно изменить
-- общую строку (канал бывает ровно один на код — SMS, EMAIL, PUSH, других завести нельзя),
-- меняет её в своём блоке @arrange и НЕ обязан возвращать обратно: следующий прогон
-- перезапишет её этим файлом.
--
-- Что здесь НЕ сеется намеренно: подавления, счётчики частоты и квот, рубильник, здоровье
-- провайдеров. Это состояние, а не конфигурация; посеянное здесь, оно протекло бы между
-- кейсами. Своё состояние кейс заводит сам.
--
-- Политик маршрутизации в базовом контуре нет: канал выбирается умолчанием потока
-- (Router: политика → channelPlan → умолчание потока). Глобальная политика с action.channel
-- перебила бы умолчания email- и push-потоков. Кейс, которому нужна политика, заводит её сам.
--
-- Запуск: psql "$COMMHUB_DB" -v ON_ERROR_STOP=1 -f 01-contour.sql
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;

BEGIN;

-- -------------------------------------------------------------------------------------
-- Провайдеры. Все — фальшивые (ADR-0041): поведение задаётся суффиксом адреса получателя,
-- поэтому кейс настраивает провайдера выбором номера и ничего не стабит.
-- Тарифы различаются, чтобы LEAST_COST и WEIGHTED было на чём проверять.
-- -------------------------------------------------------------------------------------

INSERT INTO provider (id, code, channel, adapter_type, weight, tariff, enabled, maintenance, health_status)
VALUES
    ('01900000-0000-7000-8000-000000000001', 'MOCK_PRIMARY', 'SMS',   'mock-sms',   10,
     '{"perMessage": null, "perSegment": {"amount": "120.5000", "currency": "UZS"}}'::jsonb, true, false, 'UNKNOWN'),
    ('01900000-0000-7000-8000-000000000002', 'MOCK_RESERVE', 'SMS',   'mock-sms',   10,
     '{"perMessage": null, "perSegment": {"amount": "150.0000", "currency": "UZS"}}'::jsonb, true, false, 'UNKNOWN'),
    ('01900000-0000-7000-8000-000000000003', 'MOCK_CHEAP',   'SMS',   'mock-sms',   30,
     '{"perMessage": null, "perSegment": {"amount": "90.0000",  "currency": "UZS"}}'::jsonb, true, false, 'UNKNOWN'),
    ('01900000-0000-7000-8000-000000000004', 'MOCK_EMAIL',   'EMAIL', 'mock-email', 10,
     '{"perMessage": {"amount": "10.0000", "currency": "UZS"}, "perSegment": null}'::jsonb, true, false, 'UNKNOWN'),
    ('01900000-0000-7000-8000-000000000005', 'MOCK_PUSH',    'PUSH',  'mock-push',  10,
     '{"perMessage": {"amount": "1.0000", "currency": "UZS"}, "perSegment": null}'::jsonb, true, false, 'UNKNOWN')
ON CONFLICT (code) DO UPDATE SET
    channel = EXCLUDED.channel,
    adapter_type = EXCLUDED.adapter_type,
    weight = EXCLUDED.weight,
    tariff = EXCLUDED.tariff,
    quota_config = NULL,
    rate_limit_config = NULL,
    enabled = true,
    maintenance = false,
    health_status = 'UNKNOWN',
    health_detail = NULL,
    health_checked_at = NULL,
    updated_at = now();

-- -------------------------------------------------------------------------------------
-- Каналы. Их ровно три и других быть не может: channel.code — первичный ключ с CHECK
-- по перечислению Channel. Кейс, которому нужна другая стратегия балансировки или статус
-- MAINTENANCE, переписывает эту же строку в своём @arrange.
-- -------------------------------------------------------------------------------------

INSERT INTO channel (code, status, balancing_strategy, fallback_order, quiet_hours, quota_config)
VALUES
    ('SMS',   'ACTIVE', 'PRIMARY_ONLY', '["MOCK_PRIMARY", "MOCK_RESERVE"]'::jsonb, NULL, NULL),
    ('EMAIL', 'ACTIVE', 'PRIMARY_ONLY', '["MOCK_EMAIL"]'::jsonb,                   NULL, NULL),
    ('PUSH',  'ACTIVE', 'PRIMARY_ONLY', '["MOCK_PUSH"]'::jsonb,                    NULL, NULL)
ON CONFLICT (code) DO UPDATE SET
    status = EXCLUDED.status,
    balancing_strategy = EXCLUDED.balancing_strategy,
    fallback_order = EXCLUDED.fallback_order,
    quiet_hours = EXCLUDED.quiet_hours,
    quota_config = EXCLUDED.quota_config,
    updated_at = now();

-- -------------------------------------------------------------------------------------
-- Потоки. Отдельный поток на сценарий вместо переконфигурирования одного: так кейсы
-- не мешают друг другу, и глобальный сброс конфигурации не нужен.
--
-- Тихие часы 21:00–09:00 Asia/Tashkent — то же окно, что на локальном стенде
-- (docs/QUICKSTART-SEND.md), чтобы ручной прогон и тест видели одно и то же.
-- -------------------------------------------------------------------------------------

INSERT INTO stream (id, name, status, default_channel, default_traffic_class, default_priority,
                    quota_config, quiet_hours, rate_limit_config)
VALUES
    ('ibank-otp', 'iBank OTP', 'ACTIVE', 'SMS', 'CRITICAL_OTP', 'REALTIME',
     NULL, NULL, NULL),

    ('core-banking', 'Core Banking', 'ACTIVE', 'SMS', 'TRANSACTIONAL', 'NORMAL',
     NULL, NULL, NULL),

    ('marketing-bulk', 'Marketing (отказ в тишину)', 'ACTIVE', 'SMS', 'NOTIFICATION', 'LOW',
     NULL,
     '{"start": "21:00", "end": "09:00", "zone": "Asia/Tashkent", "behavior": "REJECT"}'::jsonb,
     NULL),

    ('marketing-defer', 'Marketing (отложить до окна)', 'ACTIVE', 'SMS', 'NOTIFICATION', 'LOW',
     NULL,
     '{"start": "21:00", "end": "09:00", "zone": "Asia/Tashkent", "behavior": "DEFER"}'::jsonb,
     NULL),

    ('quota-day', 'Квота 3/сутки, блокирующая', 'ACTIVE', 'SMS', 'NOTIFICATION', 'LOW',
     '{"dailyCount": 3, "monthlyCount": null, "dailyCost": null, "monthlyCost": null,
       "behavior": "BLOCK_AND_ALERT"}'::jsonb,
     NULL, NULL),

    ('quota-alert', 'Квота 1/сутки, только алерт', 'ACTIVE', 'SMS', 'NOTIFICATION', 'LOW',
     '{"dailyCount": 1, "monthlyCount": null, "dailyCost": null, "monthlyCost": null,
       "behavior": "ALERT_ONLY"}'::jsonb,
     NULL, NULL),

    ('stream-suspended', 'Приостановленный поток', 'SUSPENDED', 'SMS', 'TRANSACTIONAL', 'NORMAL',
     NULL, NULL, NULL),

    ('stream-disabled', 'Выключенный поток', 'DISABLED', 'SMS', 'TRANSACTIONAL', 'NORMAL',
     NULL, NULL, NULL),

    ('email-stream', 'Почтовый поток', 'ACTIVE', 'EMAIL', 'TRANSACTIONAL', 'NORMAL',
     NULL, NULL, NULL),

    ('push-stream', 'Push-поток', 'ACTIVE', 'PUSH', 'TRANSACTIONAL', 'NORMAL',
     NULL, NULL, NULL),

    -- perMinute намеренно 0: при заданном perMinute он становится РАЗМЕРОМ ВСПЛЕСКА
    -- (StreamLimits.toStreamLimit), и «2 rps» с perMinute=120 пропускает 120 запросов
    -- подряд — IT-ING-015 с его десятью запросами не смог бы упереться в лимит вовсе.
    -- Без perMinute всплеск равен двум секундам ставки, то есть 4 запросам.
    ('rate-limited', 'Поток с лимитом 2 rps (только REST)', 'ACTIVE', 'SMS', 'NOTIFICATION', 'LOW',
     NULL, NULL,
     '{"tps": 2, "perMinute": 0, "perRecipientPerHour": 0}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    status = EXCLUDED.status,
    default_channel = EXCLUDED.default_channel,
    default_traffic_class = EXCLUDED.default_traffic_class,
    default_priority = EXCLUDED.default_priority,
    default_balancing_strategy = NULL,
    default_provider_id = NULL,
    quota_config = EXCLUDED.quota_config,
    quiet_hours = EXCLUDED.quiet_hours,
    rate_limit_config = EXCLUDED.rate_limit_config,
    updated_at = now();

-- Политик маршрутизации базовый контур не содержит — см. шапку файла.
DELETE FROM routing_policy;

-- -------------------------------------------------------------------------------------
-- Шаблоны (§2.4 спецификации). Опубликованная версия обязана иметь reviewed_by
-- (template_version_published_ck) — маркер-чекер FR-4.2 держится ограничением БД,
-- а не только доменом.
-- -------------------------------------------------------------------------------------

INSERT INTO template (id, code, channel, direction, owner, status)
VALUES
    ('01910000-0000-7000-8000-000000000001', 'OTP_RU_UZ',     'SMS', 'OTP',       'qa', 'ACTIVE'),
    ('01910000-0000-7000-8000-000000000002', 'ONLY_RU',       'SMS', 'INFO',      'qa', 'ACTIVE'),
    ('01910000-0000-7000-8000-000000000003', 'DRAFT_ONLY',    'SMS', 'INFO',      'qa', 'ACTIVE'),
    ('01910000-0000-7000-8000-000000000004', 'ARCHIVED_CARD', 'SMS', 'INFO',      'qa', 'ARCHIVED'),
    ('01910000-0000-7000-8000-000000000005', 'MANY_VARS',     'SMS', 'INFO',      'qa', 'ACTIVE')
ON CONFLICT (code) DO UPDATE SET
    channel = EXCLUDED.channel,
    direction = EXCLUDED.direction,
    owner = EXCLUDED.owner,
    status = EXCLUDED.status,
    updated_at = now();

DELETE FROM template_version
 WHERE template_id IN (SELECT id FROM template WHERE code IN
       ('OTP_RU_UZ', 'ONLY_RU', 'DRAFT_ONLY', 'ARCHIVED_CARD', 'MANY_VARS'));

INSERT INTO template_version (id, template_id, version, locale, subject, body, variables,
                              status, created_by, reviewed_by, published_at)
VALUES
    -- OTP_RU_UZ: опубликован в двух локалях — проверка выбора локали.
    ('01920000-0000-7000-8000-000000000001', '01910000-0000-7000-8000-000000000001', 1, 'RU', NULL,
     'Ваш код подтверждения: {CODE}', '["CODE"]'::jsonb, 'PUBLISHED', 'qa-author', 'qa-approver', now()),
    ('01920000-0000-7000-8000-000000000002', '01910000-0000-7000-8000-000000000001', 1, 'UZ', NULL,
     'Tasdiqlash kodingiz: {CODE}',   '["CODE"]'::jsonb, 'PUBLISHED', 'qa-author', 'qa-approver', now()),

    -- ONLY_RU: только русская версия — проверка отката локали на RU.
    ('01920000-0000-7000-8000-000000000003', '01910000-0000-7000-8000-000000000002', 1, 'RU', NULL,
     'Только по-русски: {VALUE}', '["VALUE"]'::jsonb, 'PUBLISHED', 'qa-author', 'qa-approver', now()),

    -- DRAFT_ONLY: единственная версия в черновике — TEMPLATE_NOT_PUBLISHED.
    ('01920000-0000-7000-8000-000000000004', '01910000-0000-7000-8000-000000000003', 1, 'RU', NULL,
     'Черновик: {VALUE}', '["VALUE"]'::jsonb, 'DRAFT', 'qa-author', NULL, NULL),

    -- ARCHIVED_CARD: версия опубликована, но карточка архивирована — тоже не отправляема.
    ('01920000-0000-7000-8000-000000000005', '01910000-0000-7000-8000-000000000004', 1, 'RU', NULL,
     'Архивная карточка: {VALUE}', '["VALUE"]'::jsonb, 'PUBLISHED', 'qa-author', 'qa-approver', now()),

    -- MANY_VARS: три переменные — TEMPLATE_VARIABLE_MISSING в строгом режиме.
    ('01920000-0000-7000-8000-000000000006', '01910000-0000-7000-8000-000000000005', 1, 'RU', NULL,
     'Здравствуйте, {NAME}! Сумма {AMOUNT} по счёту {ACCOUNT}.',
     '["NAME", "AMOUNT", "ACCOUNT"]'::jsonb, 'PUBLISHED', 'qa-author', 'qa-approver', now());

COMMIT;

-- Контрольная сводка контура. Считается только СВОЁ — теми же списками, по которым
-- убирает 99-teardown.sql: на базе, где кроме набора живёт чужая конфигурация (демо-поток
-- из http/10-config.http, боевой профиль), общий count() дал бы 12 потоков вместо 11 и
-- сводка перестала бы отвечать на вопрос «встал ли контур».
SELECT 'provider' AS entity, count(*) AS rows FROM provider WHERE code LIKE 'MOCK\_%'
UNION ALL SELECT 'channel',          count(*) FROM channel
UNION ALL SELECT 'stream',           count(*) FROM stream
 WHERE id IN ('ibank-otp', 'core-banking', 'marketing-bulk', 'marketing-defer',
              'quota-day', 'quota-alert', 'stream-suspended', 'stream-disabled',
              'email-stream', 'push-stream', 'rate-limited')
UNION ALL SELECT 'routing_policy',   count(*) FROM routing_policy
UNION ALL SELECT 'template',         count(*) FROM template
 WHERE code IN ('OTP_RU_UZ', 'ONLY_RU', 'DRAFT_ONLY', 'ARCHIVED_CARD', 'MANY_VARS')
UNION ALL SELECT 'template_version', count(*) FROM template_version
 WHERE template_id IN (SELECT id FROM template WHERE code IN
       ('OTP_RU_UZ', 'ONLY_RU', 'DRAFT_ONLY', 'ARCHIVED_CARD', 'MANY_VARS'))
ORDER BY entity;
