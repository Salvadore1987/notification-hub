-- =====================================================================================
-- 00-reset.sql — очистка изменчивого состояния перед прогоном тест-кейса.
--
-- Чистится ТОЛЬКО состояние: сообщения, попытки, outbox, дедуп, подавления, счётчики,
-- батчи, DLQ. Конфигурация (потоки, каналы, провайдеры, политики, шаблоны) НЕ трогается —
-- её ставит 01-contour.sql, и она общая для всех кейсов (§5 INTEGRATION-TEST-SPEC.md).
--
-- Что здесь намеренно не чистится:
--   * audit_log  — V7 запрещает DELETE и TRUNCATE на уровне БД. Журнал, который может
--                  опустошить тест, может опустошить и администратор. Кейсы, читающие
--                  журнал, пишутся безразличными к тому, что в нём уже есть (SEC-08).
--   * export_cursor — курсор витрины (FR-6.4); экспорт вне охвата набора и по умолчанию
--                  выключен. Сбрасывается только в 99-teardown.sql.
--
-- Запуск: psql "$COMMHUB_DB" -v ON_ERROR_STOP=1 -f 00-reset.sql
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;

BEGIN;

-- TRUNCATE секционированной таблицы очищает все её партиции разом — по одной ходить не надо.
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

-- Рубильник — одна строка, а не таблица под удаление: приложение ожидает её наличия
-- (V14 вставляет её миграцией). Сбрасываем значения, строку оставляем.
UPDATE kill_switch
   SET active = false,
       includes_critical_otp = false,
       changed_at = NULL,
       changed_by = NULL,
       reason = NULL
 WHERE id;

-- Здоровье провайдеров — это тоже состояние, а не конфигурация: DOWN, оставшийся от
-- предыдущего кейса, сделает следующий необъяснимо зелёным или необъяснимо красным.
UPDATE provider
   SET health_status = 'UNKNOWN',
       health_detail = NULL,
       health_checked_at = NULL
 WHERE health_status <> 'UNKNOWN'
    OR health_detail IS NOT NULL
    OR health_checked_at IS NOT NULL;

-- Отметка последней активности потока выводится в ConnectionStatus (FR-1.3) — тоже состояние.
UPDATE stream SET last_activity_at = NULL WHERE last_activity_at IS NOT NULL;

COMMIT;

-- Контрольная сводка: всё должно быть по нулям.
SELECT 'message'            AS table_name, count(*) AS rows FROM message
UNION ALL SELECT 'message_status_history', count(*) FROM message_status_history
UNION ALL SELECT 'delivery_attempt',       count(*) FROM delivery_attempt
UNION ALL SELECT 'push_delivery',          count(*) FROM push_delivery
UNION ALL SELECT 'outbox_event',           count(*) FROM outbox_event
UNION ALL SELECT 'dedup_registry',         count(*) FROM dedup_registry
UNION ALL SELECT 'suppression_list',       count(*) FROM suppression_list
UNION ALL SELECT 'frequency_counter',      count(*) FROM frequency_counter
UNION ALL SELECT 'quota_counter',          count(*) FROM quota_counter
UNION ALL SELECT 'dlq_entry',              count(*) FROM dlq_entry
UNION ALL SELECT 'batch',                  count(*) FROM batch
ORDER BY table_name;
