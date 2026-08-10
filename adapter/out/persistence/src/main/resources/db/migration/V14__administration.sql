-- =====================================================================================
-- V14 — раздел «Администрирование» админ-панели (§11.2): глобальный kill switch (FR-3.2)
-- и системные параметры (NF-06), плюс индексы, без которых экраны «Сообщения» и
-- «Рассылки» становятся сканом партиций (UI-03).
--
-- Оба состояния живут в БД, а не в JVM: инстансов много, и «остановлено» должно
-- означать одно и то же на всех в пределах секунд (AD-07). Инстанс, узнавший о
-- kill switch из своей памяти, — это инстанс, который продолжает слать, пока
-- остальные стоят.
-- =====================================================================================

-- FR-3.2. Строка ровно одна: kill switch — глобальный, а таблица с двумя строками
-- рано или поздно даёт вопрос «а какая из них действует». CHECK на константный
-- первичный ключ дешевле, чем договорённость.
CREATE TABLE kill_switch (
    id                    boolean     PRIMARY KEY DEFAULT true,
    active                boolean     NOT NULL DEFAULT false,
    includes_critical_otp boolean     NOT NULL DEFAULT false,
    changed_at            timestamptz,
    changed_by            varchar(128),
    reason                varchar(512),
    CONSTRAINT kill_switch_single_row_ck CHECK (id),
    -- Домен требует того же (KillSwitchState): включённый рубильник без времени
    -- включения нельзя ни объяснить оператору, ни разобрать в инциденте.
    CONSTRAINT kill_switch_changed_ck CHECK (NOT active OR changed_at IS NOT NULL)
);

COMMENT ON TABLE kill_switch IS 'Глобальная остановка отправок; читается конвейером на каждом сообщении (FR-3.2).';
COMMENT ON COLUMN kill_switch.includes_critical_otp IS
    'Останавливать ли CRITICAL_OTP: по умолчанию нет — аварийная остановка не должна молча гасить OTP.';
COMMENT ON COLUMN kill_switch.reason IS 'Обоснование включения; обязательно на входе (FR-7.3).';

-- Стартовое состояние — выключено. Строка заводится сразу, чтобы читающая сторона
-- не различала «выключено» и «ещё никто не трогал»: это одно и то же состояние.
INSERT INTO kill_switch (id, active, includes_critical_otp) VALUES (true, false, false);

-- §11.2 «Администрирование». Сюда попадает то, что оператор меняет ночью и что
-- должно совпасть на всех инстансах: баннеры, пороги, переключатели. Всё, у чего
-- есть форма — маршрутизация, провайдеры, квоты, quiet hours — лежит в своих
-- таблицах со своими агрегатами (AD-07); перенос любого из этого сюда заменил бы
-- валидируемую конфигурацию свободным текстом.
CREATE TABLE system_parameter (
    key         varchar(128) PRIMARY KEY,
    value       varchar(4000) NOT NULL,
    description varchar(512),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  varchar(128)
);

COMMENT ON TABLE system_parameter IS 'Системные параметры, редактируемые из админ-панели (§11.2, NF-06).';
COMMENT ON COLUMN system_parameter.updated_by IS
    'Кто записал последним; что было до — в audit_log, здесь только текущее значение (FR-7.3).';

-- UI-03: экран «Сообщения» ищет по внешнему идентификатору, correlationId и адресу.
-- Первые два индекса уже есть (V4), адреса не было: recipient — jsonb, и поиск
-- «покажи всё, что ушло на этот номер» без индекса читает партицию целиком.
-- Индекс по выражению, а не GIN по всему документу: спрашивают всегда точное
-- совпадение одного поля, а jsonb_path_ops по всей колонке был бы втрое больше
-- ради запросов, которых на этом экране нет.
CREATE INDEX message_recipient_msisdn_idx ON message ((recipient ->> 'msisdn'))
    WHERE recipient ->> 'msisdn' IS NOT NULL;
CREATE INDEX message_recipient_email_idx ON message ((lower(recipient ->> 'email')))
    WHERE recipient ->> 'email' IS NOT NULL;

-- §11.2 «Рассылки»: список сортируется по времени создания и фильтруется по потоку.
CREATE INDEX batch_created_idx ON batch (created_at DESC);
