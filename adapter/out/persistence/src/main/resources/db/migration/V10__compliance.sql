-- =====================================================================================
-- V10 — фильтрация и compliance: счётчики частоты отправок и обслуживание
-- suppression-списка (SRS §7.5, FR-5.1, FR-5.4, DB-04).
--
-- Таблицы под FR-5.4 в §10.1 нет: она появляется вместе с функциональностью, как и
-- договорено в плане Phase 4. Сам suppression_list заведён в V5 — здесь к нему
-- добавляются только индексы под административные выборки (§11.2).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Frequency capping (FR-5.4): сколько сообщений ушло на адрес за окно.
--
-- Считаем не строкой на сообщение, а часовыми ведрами. При прогнозных объёмах (~17,2 млн
-- push/мес, §12.2) строка на отправку — это ещё одна таблица размером с message, и она
-- нужна ради одного числа. Огрубление честное и работает в безопасную сторону: ведро, в
-- которое попал левый край окна, учитывается целиком, поэтому счётчик может быть чуть
-- больше фактического — то есть кап скорее сработает, чем промолчит.
--
-- Адрес — только хеш (DB-04): PII в счётчиках нет, и это тот же хеш, по которому
-- ищется запись в suppression_list.
-- -------------------------------------------------------------------------------------

CREATE TABLE frequency_counter (
    address_hash    varchar(64) NOT NULL,
    channel         text        NOT NULL,
    bucket_start    timestamptz NOT NULL,
    counter         integer     NOT NULL DEFAULT 0,
    CONSTRAINT frequency_counter_pk PRIMARY KEY (address_hash, channel, bucket_start),
    CONSTRAINT frequency_counter_channel_ck CHECK (channel IN ('SMS', 'EMAIL', 'PUSH')),
    CONSTRAINT frequency_counter_value_ck CHECK (counter >= 0)
);

COMMENT ON TABLE frequency_counter IS 'Счётчики отправок на получателя по часовым ведрам (FR-5.4).';
COMMENT ON COLUMN frequency_counter.address_hash IS 'Хеш адреса (DB-04); тот же, что в suppression_list.';
COMMENT ON COLUMN frequency_counter.bucket_start IS 'Начало часа (UTC); окно капа суммирует ведра от левого края.';

-- Под очистку окна (DB-03): свип ходит только по времени, без адреса.
CREATE INDEX frequency_counter_bucket_idx ON frequency_counter (bucket_start);

-- -------------------------------------------------------------------------------------
-- Suppression list (FR-5.1): выборки админ-панели — страница списка и фильтры по
-- причине и клиенту. Поиска по адресу нет и не будет: в таблице лежит хеш, и «покажи
-- этот номер» — это точечная проверка (GetSuppressions.check), а не фильтр списка.
-- -------------------------------------------------------------------------------------

CREATE INDEX suppression_created_idx ON suppression_list (created_at DESC);
CREATE INDEX suppression_reason_idx ON suppression_list (reason, created_at DESC);
