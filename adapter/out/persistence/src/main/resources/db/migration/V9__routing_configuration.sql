-- =====================================================================================
-- V9 — конфигурация маршрутизации Phase 8: квоты по каналу и провайдеру (FR-2.6),
-- лимит запросов и стратегия балансировки на поток (IR-02, FR-2.3), результат
-- health-check провайдера (PR-02, FR-6.3).
--
-- Всё редактируется из админ-панели и применяется без рестарта (AD-07, NF-07):
-- конфигурация читается снапшотом с TTL, отдельного канала инвалидации нет —
-- инстансов много, и «протолкнуть» изменение всем сразу было бы ещё одним каналом связи,
-- который может отказать. Ограничение NF-07 (≤30с) обеспечивается длиной TTL.
-- =====================================================================================

-- FR-2.6: квота живёт на всех трёх измерениях, а не только на потоке. Одно и то же
-- сообщение расходует бюджет своего потока, своего канала и своего провайдера —
-- это разные договорённости с разными владельцами.
ALTER TABLE provider ADD COLUMN quota_config jsonb;
ALTER TABLE channel  ADD COLUMN quota_config jsonb;

COMMENT ON COLUMN provider.quota_config IS 'Квота провайдера (кол-во/стоимость, день/месяц), FR-2.6.';
COMMENT ON COLUMN channel.quota_config IS 'Квота канала суммарно по всем потокам, FR-2.6.';

-- PR-02: пассивный health-check пишет сюда вердикт и время. health_checked_at нужен не
-- для отчётности, а для failback: провайдер в DOWN не получает трафика, поэтому новых
-- цифр по нему не будет — вернуть его в маршрутизацию можно только по времени молчания.
ALTER TABLE provider ADD COLUMN health_detail text;
ALTER TABLE provider ADD COLUMN health_checked_at timestamptz;

COMMENT ON COLUMN provider.health_checked_at IS 'Когда health установлен; из него считается пауза перед возвратом в маршрутизацию (FR-6.3).';
COMMENT ON COLUMN provider.health_detail IS 'Цифры, на которых основан вердикт: доля ошибок, таймаутов, латентность.';

-- IR-02: пер-стримовые лимиты синхронного API переезжают из yaml в реестр потоков.
-- Считаются запросы, а не сообщения (чанк батча — один запрос).
ALTER TABLE stream ADD COLUMN rate_limit_config jsonb;

COMMENT ON COLUMN stream.rate_limit_config IS 'Лимит запросов потока к /api/v1 (tps + перминутный потолок), IR-02.';

-- FR-2.3: стратегия балансировки настраивается «на уровне канала и/или потока».
-- Порядок разрешения в Router: политика → дефолт потока → канал.
ALTER TABLE stream ADD COLUMN default_balancing_strategy text;

ALTER TABLE stream ADD CONSTRAINT stream_default_strategy_ck CHECK (
    default_balancing_strategy IS NULL
    OR default_balancing_strategy IN ('ROUND_ROBIN', 'WEIGHTED', 'LEAST_COST', 'PRIMARY_ONLY'));

COMMENT ON COLUMN stream.default_balancing_strategy IS 'Переопределение стратегии балансировки для трафика потока (FR-2.3).';

-- FR-6.3: монитор здоровья читает попытки доставки за окно (минуты). Индекс локальный
-- для каждой секции — таблица секционирована по request_at, поэтому свежее окно всегда
-- лежит в одной-двух секциях.
CREATE INDEX delivery_attempt_provider_request_idx ON delivery_attempt (provider_id, request_at DESC);
