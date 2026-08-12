-- =====================================================================================
-- IT-TPL — шаблоны в отправке (FR-4.1…4.5).
--
-- Все пять шаблонов §2.4 спецификации уже стоят в 01-contour.sql; предусловия здесь
-- нужны только там, где кейс меняет состояние шаблона (публикация новой версии).
--
-- Отправляемый текст в базе зашифрован (DB-04, «CH1.<keyId>.<base64url>»), поэтому
-- проверка «в тексте нет {CODE}» делается не по message.contents, а по журналу
-- outbound-контента на стенде (commhub.provider.content-log.enabled=true в корневом
-- config/application.yml) либо по запросу, который увидел фальшивый провайдер.
-- В SQL проверяется то, что в базе действительно есть: код шаблона, локаль и версия.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-TPL-001  Штатный рендер
-- @arrange
-- @assert
SELECT template_code = 'OTP_RU_UZ' AND template_locale = 'RU' AS ok,
       'сообщение отрендерено из русской версии OTP_RU_UZ' AS check
  FROM message;
SELECT m.template_version_id = v.id AS ok, 'зафиксирована именно опубликованная версия' AS check
  FROM message m
  JOIN template_version v ON v.id = m.template_version_id
 WHERE v.status = 'PUBLISHED';
SELECT status = 'REJECTED' IS NOT TRUE AS ok, 'отказа не было' AS check FROM message;


-- >>> IT-TPL-002  Выбор локали UZ
-- @arrange
-- @assert
SELECT template_locale = 'UZ' AS ok, 'применена узбекская версия' AS check FROM message;
SELECT v.locale = 'UZ' AS ok, 'версия из базы — действительно UZ' AS check
  FROM message m JOIN template_version v ON v.id = m.template_version_id;


-- >>> IT-TPL-003  Откат локали на RU
-- @arrange
-- @assert
-- Запрошена UZ, у ONLY_RU её нет — ожидается русская версия и отсутствие отказа.
SELECT template_code = 'ONLY_RU' AS ok, 'шаблон тот' AS check FROM message;
SELECT v.locale = 'RU' AS ok, 'отдана русская версия вместо отсутствующей узбекской' AS check
  FROM message m JOIN template_version v ON v.id = m.template_version_id;
SELECT status_reason IS NULL AS ok, 'отказа по локали не было' AS check FROM message;


-- >>> IT-TPL-004  Шаблон только в черновике
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'у DRAFT_ONLY нет ни одной опубликованной версии' AS check
  FROM template_version v JOIN template t ON t.id = v.template_id
 WHERE t.code = 'DRAFT_ONLY' AND v.status = 'DRAFT';
SELECT count(*) = 0 AS ok, 'сообщение не создано либо отклонено' AS check
  FROM message WHERE status <> 'REJECTED';
SELECT status_reason = 'TEMPLATE_NOT_PUBLISHED' AS ok, 'причина отказа названа верно' AS check
  FROM message;


-- >>> IT-TPL-005  Карточка архивирована
-- @arrange
-- @assert
SELECT t.status = 'ARCHIVED' AND v.status = 'PUBLISHED' AS ok,
       'карточка архивна, а версия опубликована — именно этот случай и проверяем' AS check
  FROM template t JOIN template_version v ON v.template_id = t.id
 WHERE t.code = 'ARCHIVED_CARD';
SELECT status_reason = 'TEMPLATE_NOT_PUBLISHED' AS ok,
       'архивная карточка не отправляема, хотя версия опубликована' AS check
  FROM message;


-- >>> IT-TPL-006  Незаполненная переменная
-- @arrange
-- @assert
SELECT v.variables = '["NAME", "AMOUNT", "ACCOUNT"]'::jsonb AS ok,
       'у MANY_VARS объявлены три переменные' AS check
  FROM template t JOIN template_version v ON v.template_id = t.id
 WHERE t.code = 'MANY_VARS';
SELECT status_reason = 'TEMPLATE_VARIABLE_MISSING' AS ok,
       'строгий режим отправки не подставляет пустое' AS check
  FROM message;
SELECT status = 'REJECTED' AND terminal_at IS NOT NULL AS ok, 'отказ терминален' AS check
  FROM message;


-- >>> IT-TPL-007  Лишняя переменная игнорируется
-- @arrange
-- @assert
SELECT status_reason IS NULL AS ok, 'лишняя переменная отказа не вызывает' AS check FROM message;
SELECT template_code = 'OTP_RU_UZ' AS ok, 'сообщение отрендерено' AS check FROM message;


-- >>> IT-TPL-008  Несуществующий шаблон
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'шаблона NO_SUCH в каталоге нет' AS check
  FROM template WHERE code = 'NO_SUCH';
SELECT status_reason = 'VALIDATION_FAILED' AS ok,
       'несуществующий шаблон — это VALIDATION_FAILED, а не TEMPLATE_NOT_PUBLISHED' AS check
  FROM message;


-- >>> IT-TPL-009  Публикация новой версии на лету
-- @arrange
-- Публикуем вторую версию OTP_RU_UZ (RU) и архивируем первую — ровно то, что делает
-- Template.publishVersion: у локали всегда ровно одна отправляемая версия.
UPDATE template_version SET status = 'ARCHIVED'
 WHERE id = '01920000-0000-7000-8000-000000000001';

INSERT INTO template_version (id, template_id, version, locale, subject, body, variables,
                              status, created_by, reviewed_by, published_at)
VALUES ('01920000-0000-7000-8000-000000000009', '01910000-0000-7000-8000-000000000001',
        2, 'RU', NULL, 'Версия два. Код: {CODE}', '["CODE"]'::jsonb,
        'PUBLISHED', 'qa-author', 'qa-approver', now());
-- @assert
SELECT count(*) = 1 AS ok, 'у локали RU ровно одна опубликованная версия' AS check
  FROM template_version v JOIN template t ON t.id = v.template_id
 WHERE t.code = 'OTP_RU_UZ' AND v.locale = 'RU' AND v.status = 'PUBLISHED';
SELECT m.template_version_id = '01920000-0000-7000-8000-000000000009' AS ok,
       'сообщение отрендерено второй версией' AS check
  FROM message m;
SELECT m.template_code = 'OTP_RU_UZ' AS ok,
       'сообщение хранит код шаблона, а не ссылку на карточку' AS check
  FROM message m;


-- >>> IT-TPL-010  Шаблон в заголовке батча
-- @arrange
-- @assert
SELECT count(*) > 0 AS ok, 'элементы батча созданы' AS check
  FROM message WHERE batch_id IS NOT NULL;
SELECT count(*) = 0 AS ok, 'каждый элемент отрендерен: без шаблона не осталось ни одного' AS check
  FROM message WHERE batch_id IS NOT NULL AND template_code IS NULL;
SELECT count(DISTINCT template_version_id) = 1 AS ok, 'все элементы — одной версией' AS check
  FROM message WHERE batch_id IS NOT NULL;
