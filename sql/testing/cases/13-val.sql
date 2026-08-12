-- =====================================================================================
-- IT-VAL — валидация содержимого и PAN (FR-1.3, SEC-05, EM-01, PU-11).
--
-- Все кейсы этой области отклоняются ДО обращения к провайдеру, поэтому основная
-- проверка везде одна и та же: строки в delivery_attempt нет, а причина отказа названа.
-- Ключевое различие между кейсами — именно причина, и её нельзя подменить общим
-- «сообщение не ушло»: PAN_DETECTED и VALIDATION_FAILED требуют разных действий
-- от системы-источника (IR-01).
--
-- Настройки, от которых зависят кейсы (bootstrap/src/main/resources/application.yml):
--   commhub.compliance.pan-blocking                    по умолчанию true
--   commhub.compliance.email.max-attachment-size       10MB
--   commhub.compliance.email.max-total-attachment-size 20MB
--   commhub.compliance.push.max-payload-size           4KB
--   commhub.compliance.push.max-tokens-per-message     10
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-VAL-001  Нет пригодного адреса для канала
-- @arrange
-- @assert
SELECT status_reason = 'VALIDATION_FAILED' AS ok, 'причина отказа названа' AS check FROM message;
SELECT count(*) = 0 AS ok, 'провайдер не вызывался' AS check FROM delivery_attempt;


-- >>> IT-VAL-002  Полный номер карты в SMS при режиме наблюдения
-- @arrange
-- Требует контура с commhub.compliance.pan-blocking=false. В SMS запрет всё равно
-- безусловен — это и есть предмет кейса.
-- @assert
SELECT status_reason = 'PAN_DETECTED' AS ok,
       'для SMS PAN блокирует независимо от режима наблюдения' AS check
  FROM message;
SELECT count(*) = 0 AS ok, 'номер карты не ушёл провайдеру' AS check FROM delivery_attempt;


-- >>> IT-VAL-003  Номер карты в Email при режиме наблюдения
-- @arrange
-- Требует контура с commhub.compliance.pan-blocking=false.
-- @assert
SELECT status_reason IS DISTINCT FROM 'PAN_DETECTED' AS ok,
       'в режиме наблюдения письмо не отклоняется' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT count(*) >= 1 AS ok, 'письмо дошло до провайдера' AS check FROM delivery_attempt;
-- Счётчик находок PAN проверяется метрикой, а не базой: текст не логируется никогда.


-- >>> IT-VAL-004  Номер карты при блокирующем режиме
-- @arrange
-- Требует контура с commhub.compliance.pan-blocking=true (значение по умолчанию).
-- @assert
SELECT status_reason = 'PAN_DETECTED' AS ok, 'письмо с PAN отклонено' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT count(*) = 0 AS ok, 'провайдер не вызывался' AS check FROM delivery_attempt;


-- >>> IT-VAL-005  Похожая на PAN, но невалидная последовательность
-- @arrange
-- @assert
SELECT status_reason IS DISTINCT FROM 'PAN_DETECTED' AS ok,
       'детектор считает контрольную сумму Луна, а не длину' AS check
  FROM message;
SELECT count(*) >= 1 AS ok, 'сообщение отправлено' AS check FROM delivery_attempt;


-- >>> IT-VAL-006  PAN не попадает в логи
-- @arrange
-- @assert
SELECT status_reason = 'PAN_DETECTED' AS ok, 'находка зафиксирована как отказ' AS check
  FROM message;
-- База знает только факт отказа. Отсутствие номера в логах проверяется вне SQL:
--   docker compose logs | grep -c '<номер карты>'   должно вернуть 0
-- Это единственная проверка набора, которую база дать не может в принципе (OBS-03).


-- >>> IT-VAL-007  Push крупнее 4 КБ
-- @arrange
-- @assert
SELECT status_reason = 'VALIDATION_FAILED' AS ok,
       'потолок платформы применён конвейером, а не APNs' AS check
  FROM message WHERE stream_id = 'push-stream';
SELECT count(*) = 0 AS ok, 'ни одного обращения к платформе' AS check FROM delivery_attempt;
SELECT count(*) = 0 AS ok, 'ни одной строки на устройство' AS check FROM push_delivery;


-- >>> IT-VAL-008  Слишком много токенов на сообщение
-- @arrange
-- @assert
SELECT status_reason = 'VALIDATION_FAILED' AS ok, 'веер сверх потолка отклонён' AS check
  FROM message WHERE stream_id = 'push-stream';
SELECT count(*) = 0 AS ok, 'ни одна отправка на устройство не началась' AS check
  FROM push_delivery;


-- >>> IT-VAL-009  Вложение письма сверх потолка
-- @arrange
-- @assert
SELECT status_reason = 'VALIDATION_FAILED' AS ok,
       'отказ несёт каноническую причину (IR-01), а не SMTP-код' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT count(*) = 0 AS ok, 'соединение с релеем не открывалось' AS check FROM delivery_attempt;


-- >>> IT-VAL-010  Суммарный размер вложений сверх потолка
-- @arrange
-- @assert
SELECT status_reason = 'VALIDATION_FAILED' AS ok,
       'считается сумма, а не только размер самого крупного файла' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT count(*) = 0 AS ok, 'провайдер не вызывался' AS check FROM delivery_attempt;
