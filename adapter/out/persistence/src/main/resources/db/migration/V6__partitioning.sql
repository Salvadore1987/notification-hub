-- =====================================================================================
-- V6 — автоматическое создание и отсоединение секций (DB-02, DB-03).
--
-- Секции месячные и именуются детерминированно: <таблица>_y2026m08. Из имени выводится
-- период, поэтому обслуживание не разбирает выражения границ (pg_get_expr) — достаточно
-- имени, что делает и создание, и отцепление предсказуемыми.
--
-- У каждой секционированной таблицы есть DEFAULT-секция. Она не для хранения: строка в
-- ней означает, что запись пришла за пределами заведённых периодов. Цена — при создании
-- новой секции PostgreSQL сканирует DEFAULT (она пустая, скан мгновенный); выгода — приём
-- сообщения не падает, если планировщик секций не отработал. Мониторинг DEFAULT-секций
-- заводится вместе с алертами Phase 13.
-- =====================================================================================

CREATE OR REPLACE FUNCTION comm_hub.partitioned_tables()
    RETURNS text[]
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT ARRAY['message', 'message_status_history', 'delivery_attempt', 'outbox_event'];
$$;

COMMENT ON FUNCTION comm_hub.partitioned_tables() IS
    'Перечень секционированных по времени таблиц (DB-02); единственный источник правды для обслуживания.';

-- Создаёт месячную секцию, если её ещё нет. Идемпотентна: повторный вызов ничего не делает.
CREATE OR REPLACE FUNCTION comm_hub.create_month_partition(p_table text, p_month date)
    RETURNS text
    LANGUAGE plpgsql
AS $$
DECLARE
    v_start     timestamptz := date_trunc('month', p_month::timestamp) AT TIME ZONE 'UTC';
    v_end       timestamptz := (date_trunc('month', p_month::timestamp) + interval '1 month') AT TIME ZONE 'UTC';
    v_partition text := format('%s_y%sm%s', p_table, to_char(p_month, 'YYYY'), to_char(p_month, 'MM'));
BEGIN
    IF NOT p_table = ANY (comm_hub.partitioned_tables()) THEN
        RAISE EXCEPTION 'table % is not partitioned by time', p_table;
    END IF;

    IF to_regclass(format('comm_hub.%I', v_partition)) IS NOT NULL THEN
        RETURN v_partition;
    END IF;

    EXECUTE format(
        'CREATE TABLE comm_hub.%I PARTITION OF comm_hub.%I FOR VALUES FROM (%L) TO (%L)',
        v_partition, p_table, v_start, v_end);
    RETURN v_partition;
END;
$$;

COMMENT ON FUNCTION comm_hub.create_month_partition(text, date) IS
    'Идемпотентно заводит месячную секцию таблицы; границы всегда в UTC (UI-04).';

-- Заводит секции на текущий месяц и на p_months_ahead вперёд для всех секционированных
-- таблиц. Вызывается планировщиком приложения (ежедневно) и вручную из админ-панели.
CREATE OR REPLACE FUNCTION comm_hub.ensure_partitions(p_months_ahead integer DEFAULT 2, p_now timestamptz DEFAULT now())
    RETURNS SETOF text
    LANGUAGE plpgsql
AS $$
DECLARE
    v_table text;
    v_shift integer;
BEGIN
    IF p_months_ahead < 0 THEN
        RAISE EXCEPTION 'p_months_ahead must not be negative, got %', p_months_ahead;
    END IF;

    FOREACH v_table IN ARRAY comm_hub.partitioned_tables() LOOP
        FOR v_shift IN 0..p_months_ahead LOOP
            RETURN NEXT comm_hub.create_month_partition(
                v_table, ((p_now AT TIME ZONE 'UTC') + make_interval(months => v_shift))::date);
        END LOOP;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION comm_hub.ensure_partitions(integer, timestamptz) IS
    'Создаёт недостающие секции на текущий и ближайшие месяцы (DB-02); возвращает их имена.';

-- Отцепляет секции, целиком лежащие раньше p_before. Отцепленная таблица остаётся в БД:
-- решение об архивации или DROP принимает регламент хранения (DB-03), а не миграция.
CREATE OR REPLACE FUNCTION comm_hub.detach_partitions_before(p_table text, p_before date)
    RETURNS SETOF text
    LANGUAGE plpgsql
AS $$
DECLARE
    v_partition record;
    v_cutoff    date := date_trunc('month', p_before::timestamp)::date;
BEGIN
    IF NOT p_table = ANY (comm_hub.partitioned_tables()) THEN
        RAISE EXCEPTION 'table % is not partitioned by time', p_table;
    END IF;

    FOR v_partition IN
        SELECT child.relname AS name,
               to_date(substring(child.relname from '_y(\d{4})m\d{2}$')
                       || substring(child.relname from '_y\d{4}m(\d{2})$'), 'YYYYMM') AS starts_at
        FROM pg_inherits
        JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
        JOIN pg_class child ON child.oid = pg_inherits.inhrelid
        JOIN pg_namespace ns ON ns.oid = parent.relnamespace
        WHERE ns.nspname = 'comm_hub'
          AND parent.relname = p_table
          AND child.relname ~ '_y\d{4}m\d{2}$'
        ORDER BY 2
    LOOP
        CONTINUE WHEN v_partition.starts_at + interval '1 month' > v_cutoff;
        EXECUTE format('ALTER TABLE comm_hub.%I DETACH PARTITION comm_hub.%I', p_table, v_partition.name);
        RETURN NEXT v_partition.name;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION comm_hub.detach_partitions_before(text, date) IS
    'Отцепляет секции старше указанной даты для архивации; данные не удаляет (DB-03).';

-- DEFAULT-секции: страховка от потери строки за пределами заведённых периодов.
CREATE TABLE message_default PARTITION OF message DEFAULT;
CREATE TABLE message_status_history_default PARTITION OF message_status_history DEFAULT;
CREATE TABLE delivery_attempt_default PARTITION OF delivery_attempt DEFAULT;
CREATE TABLE outbox_event_default PARTITION OF outbox_event DEFAULT;

COMMENT ON TABLE message_default IS 'Страховочная секция; непустая означает сбой планировщика секций — повод для алерта.';

-- Стартовый набор секций, чтобы схема была работоспособна сразу после миграции с нуля (DB-01).
SELECT comm_hub.ensure_partitions(2);
