-- -------------------------------------------------------------------------------------
-- То, что элемент наследует от рассылки: класс трафика, метка TEST и шаблон (FR-1.6).
--
-- POST /api/v1/batches принимает эти три поля с самого начала (§8.2), но SubmitBatchService
-- их выбрасывал: агрегат Batch их не имел, а элементы разворачивались с жёстко
-- прописанными Delivery(null, …, false, null). То есть опубликованный контракт молча терял
-- часть заголовка, а рассылка по шаблону из панели была невозможна.
--
-- template_variables шифруется тем же ContentCodec, что и message.template_variables:
-- дефолтный набор merge-полей может нести данные клиента (DB-04).
-- -------------------------------------------------------------------------------------

ALTER TABLE batch
    ADD COLUMN traffic_class      text,
    ADD COLUMN test               boolean NOT NULL DEFAULT false,
    ADD COLUMN template_code      varchar(64),
    ADD COLUMN template_locale    text,
    ADD COLUMN template_variables jsonb;

ALTER TABLE batch ADD CONSTRAINT batch_traffic_class_ck
    CHECK (traffic_class IS NULL OR traffic_class IN ('CRITICAL_OTP', 'TRANSACTIONAL', 'NOTIFICATION'));

ALTER TABLE batch ADD CONSTRAINT batch_template_ck
    CHECK ((template_code IS NULL) = (template_locale IS NULL));

COMMENT ON COLUMN batch.traffic_class IS
    'Класс трафика по умолчанию для элементов рассылки; NULL — решает поток (FR-1.6).';
COMMENT ON COLUMN batch.test IS
    'Рассылка помечена как тестовая: её сообщения не попадают в бизнес-статистику (FR-7.4).';
COMMENT ON COLUMN batch.template_code IS
    'Шаблон по умолчанию; элемент вправе указать свой (FR-4.1).';
COMMENT ON COLUMN batch.template_variables IS
    'Merge-поля по умолчанию; шифруются как message.template_variables (DB-04).';
