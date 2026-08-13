-- =====================================================================================
-- 99-teardown.sql — полная очистка после прогона: состояние И контур.
--
-- 00-reset.sql оставляет конфигурацию на месте (её ставит 01-contour.sql перед каждым
-- кейсом). Этот файл убирает и её — чтобы после набора база выглядела так же, как после
-- миграций на пустой контур, и следующий, кто её возьмёт, не унаследовал чужих потоков.
--
-- Удаляется только то, что завёл набор: провайдеры MOCK_*, потоки из §2.1 спецификации,
-- шаблоны из §2.4. Настоящая конфигурация контура (Playmobile, SMS Gate, боевые потоки)
-- не трогается — по имени, а не по «удалить всё».
--
-- audit_log не чистится и здесь: V7 запрещает DELETE и TRUNCATE на уровне БД (SEC-08).
--
-- Запуск: psql "$COMMHUB_DB" -v ON_ERROR_STOP=1 -f 99-teardown.sql
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;

BEGIN;

-- 1. Изменчивое состояние — тем же способом, что и в 00-reset.sql.
TRUNCATE TABLE
    message,
    message_status_history,
    delivery_attempt,
    push_delivery,
    outbox_event,
    dedup_registry,
    suppression_list,
    frequency_counter,
    quota_counter,
    dlq_entry,
    batch;

UPDATE kill_switch
   SET active = false, includes_critical_otp = false,
       changed_at = NULL, changed_by = NULL, reason = NULL
 WHERE id;

-- Курсор витрины (FR-6.4): в 00-reset.sql его нет намеренно — экспорт вне охвата набора
-- и по умолчанию выключен. Здесь снимаем, чтобы не осталось следов прогона.
DELETE FROM export_cursor WHERE name = 'data-mart';

-- 2. Контур. Порядок важен: шаблоны и политики → потоки → каналы → провайдеры
-- (у stream.default_provider_id внешний ключ на provider).

DELETE FROM template_provider_mapping
 WHERE template_id IN (SELECT id FROM template WHERE code IN
       ('OTP_RU_UZ', 'ONLY_RU', 'DRAFT_ONLY', 'ARCHIVED_CARD', 'MANY_VARS'));

DELETE FROM template_version
 WHERE template_id IN (SELECT id FROM template WHERE code IN
       ('OTP_RU_UZ', 'ONLY_RU', 'DRAFT_ONLY', 'ARCHIVED_CARD', 'MANY_VARS'));

DELETE FROM template
 WHERE code IN ('OTP_RU_UZ', 'ONLY_RU', 'DRAFT_ONLY', 'ARCHIVED_CARD', 'MANY_VARS');

-- Политики набор заводит только внутри кейсов и всегда со scope, поэтому чистим все:
-- базовый контур их не содержит, и уцелевшая политика кейса испортит следующий прогон.
DELETE FROM routing_policy;

DELETE FROM stream
 WHERE id IN ('ibank-otp', 'core-banking', 'marketing-bulk', 'marketing-defer',
              'quota-day', 'quota-alert', 'stream-suspended', 'stream-disabled',
              'email-stream', 'push-stream', 'rate-limited');

DELETE FROM provider WHERE code LIKE 'MOCK\_%';

-- Каналы не удаляются, а возвращаются в исходное: профиль канала — это строка, которую
-- кто-то создал, и её отсутствие на контуре означает NO_ROUTE_AVAILABLE при первой же
-- отправке (docs/QUICKSTART-SEND.md). Если каналы были заведены до набора — они остаются;
-- если набор их создал, останется безобидный ACTIVE-профиль с пустым порядком отката.
-- Порядок отката значащий (первый — основной), поэтому уцелевшие коды собираются
-- обратно в исходном порядке через WITH ORDINALITY, а не сортировкой по алфавиту.
WITH cleaned AS (
    SELECT c.code AS channel_code,
           COALESCE(
               jsonb_agg(e.value ORDER BY e.ord) FILTER (WHERE e.value IS NOT NULL),
               '[]'::jsonb) AS fallback_order
      FROM channel c
      LEFT JOIN LATERAL jsonb_array_elements_text(c.fallback_order)
           WITH ORDINALITY AS e(value, ord)
        ON e.value NOT LIKE 'MOCK\_%'
     GROUP BY c.code
)
UPDATE channel
   SET status = 'ACTIVE',
       quiet_hours = NULL,
       quota_config = NULL,
       fallback_order = cleaned.fallback_order,
       updated_at = now()
  FROM cleaned
 WHERE channel.code = cleaned.channel_code;

COMMIT;

-- Контрольная сводка: следов набора остаться не должно.
SELECT 'streams набора'   AS entity,
       count(*)           AS rows
  FROM stream
 WHERE id IN ('ibank-otp', 'core-banking', 'marketing-bulk', 'marketing-defer',
              'quota-day', 'quota-alert', 'stream-suspended', 'stream-disabled',
              'email-stream', 'push-stream', 'rate-limited')
UNION ALL SELECT 'провайдеры MOCK_*', count(*) FROM provider WHERE code LIKE 'MOCK\_%'
UNION ALL SELECT 'шаблоны набора',    count(*) FROM template WHERE code IN
       ('OTP_RU_UZ', 'ONLY_RU', 'DRAFT_ONLY', 'ARCHIVED_CARD', 'MANY_VARS')
UNION ALL SELECT 'сообщения',         count(*) FROM message
UNION ALL SELECT 'политики',          count(*) FROM routing_policy
ORDER BY entity;
