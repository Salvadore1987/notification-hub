-- =====================================================================================
-- IT-BAT — батчи (FR-1.6, FR-3.1, ADR-0040).
--
-- Знаменатель прогресса — правило, которое легко сломать и трудно заметить:
-- total = max(announced, uploaded), НЕ их сумма. Заголовок может объявить, сколько
-- элементов придёт, а чанки потом сказать, сколько пришло; сложение удваивало total
-- у всех, кто делает и то и другое (панель делает всегда), и processed >= total
-- становилось недостижимым — батч навсегда оставался в PROCESSING.
-- Отсюда batch.uploaded (V18) и отдельные кейсы IT-BAT-002 и IT-BAT-003.
--
-- Элементы батча — обычные строки message с batch_id; отдельной таблицы элементов нет.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-BAT-001  Создание батча и загрузка элементов
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'батч создан' AS check FROM batch;
SELECT status IN ('ACCEPTED', 'PROCESSING') AS ok, 'батч виден с момента приёма' AS check
  FROM batch;
SELECT uploaded = 100 AS ok, 'загружено сто элементов' AS check FROM batch;
SELECT count(*) = 100 AS ok, 'сто сообщений созданы' AS check
  FROM message WHERE batch_id IS NOT NULL;
SELECT id, status, total, uploaded, processed, sent, delivered, failed FROM batch;


-- >>> IT-BAT-002  Знаменатель прогресса: загружено больше объявленного
-- @arrange
-- @assert
-- Заголовок объявил 100, загружено 150 → total = 150, а не 250.
SELECT uploaded = 150 AS ok, 'загружено сто пятьдесят' AS check FROM batch;
SELECT total = 150 AS ok,
       'total = max(объявлено, загружено); сумма удвоила бы знаменатель' AS check
  FROM batch;
SELECT total, uploaded, processed FROM batch;


-- >>> IT-BAT-003  Объявлено больше, чем загружено
-- @arrange
-- @assert
-- Заголовок объявил 200, загружено 150 → total = 200: источник обещал ещё элементы.
SELECT uploaded = 150 AS ok, 'загружено сто пятьдесят' AS check FROM batch;
SELECT total = 200 AS ok, 'знаменатель — объявленное, оно больше' AS check FROM batch;
SELECT processed < total AS ok, 'батч ещё не завершён' AS check FROM batch;


-- >>> IT-BAT-004  Батч завершается
-- @arrange
-- @assert
SELECT processed = total AS ok, 'обработаны все элементы' AS check FROM batch;
SELECT status = 'COMPLETED' AS ok, 'батч завершён' AS check FROM batch;
SELECT delivered + failed = processed AS ok, 'счётчики сходятся' AS check FROM batch;
SELECT total, uploaded, processed, sent, delivered, failed FROM batch;


-- >>> IT-BAT-005  Поэлементные отказы при общем 202
-- @arrange
-- @assert
-- Чанк из пяти, два элемента без адреса: ответ 202, в теле — две записи об отказе,
-- три сообщения приняты. Отказ элемента не отменяет приём чанка.
SELECT count(*) = 3 AS ok, 'три пригодных элемента приняты' AS check
  FROM message WHERE batch_id IS NOT NULL;
SELECT uploaded = 3 AS ok, 'счётчик загруженных учёл только принятые' AS check FROM batch;
-- Указатели items[i] на отклонённые элементы проверяются в теле ответа, а не в базе.


-- >>> IT-BAT-006  Потолок размера чанка
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'чанк из 10 001 элемента отвергнут целиком' AS check
  FROM message WHERE batch_id IS NOT NULL;
SELECT uploaded = 0 AS ok, 'счётчик не сдвинулся' AS check FROM batch;
-- Ожидается отказ с указателем на поле items.


-- >>> IT-BAT-007  Пустой чанк
-- @arrange
-- @assert
SELECT uploaded = 0 AS ok, 'пустой чанк ничего не добавил' AS check FROM batch;
SELECT count(*) = 0 AS ok, 'сообщений не создано' AS check
  FROM message WHERE batch_id IS NOT NULL;
-- Ожидается отказ «missing items»: пустой список — это ошибка вызывающего,
-- а не разрешённая пустая загрузка.


-- >>> IT-BAT-008  Пауза останавливает отправку
-- @arrange
-- @assert
SELECT status = 'PAUSED' AS ok, 'батч приостановлен' AS check FROM batch;
SELECT sent AS отправлено_до_паузы, processed, total FROM batch;
-- Уже отправленные не отзываются — их отзывать не у кого.
SELECT count(*) = 0 AS ok, 'после паузы новых попыток не появилось' AS check
  FROM delivery_attempt a JOIN message m ON m.id = a.message_id
 WHERE m.batch_id IS NOT NULL
   AND a.request_at > (SELECT updated_at FROM batch);


-- >>> IT-BAT-009  Возобновление продолжает с того же места
-- @arrange
-- @assert
SELECT status IN ('PROCESSING', 'COMPLETED') AS ok, 'батч снова в работе' AS check FROM batch;
SELECT count(*) = 0 AS ok, 'ни одно сообщение не отправлено дважды' AS check
  FROM (SELECT a.message_id FROM delivery_attempt a JOIN message m ON m.id = a.message_id
         WHERE m.batch_id IS NOT NULL
         GROUP BY a.message_id HAVING count(*) > 1) d;
SELECT processed <= total AS ok, 'прогресс не перескочил знаменатель' AS check FROM batch;


-- >>> IT-BAT-010  Остановка терминальна
-- @arrange
-- @assert
SELECT status = 'STOPPED' AS ok, 'батч остановлен' AS check FROM batch;
-- Последующий resume обязан быть отвергнут: STOPPED — терминальное состояние,
-- из него переходов нет. Если статус стал PROCESSING — это дефект.
SELECT status <> 'PROCESSING' AS ok, 'resume не поднял остановленный батч' AS check FROM batch;


-- >>> IT-BAT-011  Неизвестное действие
-- @arrange
-- @assert
SELECT status AS статус_не_изменился FROM batch;
-- Ожидается отказ с указателем на поле action, а не 500: неизвестное действие —
-- ошибка вызывающего, и он должен узнать, какое поле неверно.


-- >>> IT-BAT-012  Порядок CREATE → PAUSE в Kafka
-- @arrange
-- @assert
-- Оба документа отправлены в comm.inbound.batch-control.v1 с одним ключом batchId,
-- значит попали в один раздел и прочитаны по порядку.
SELECT count(*) = 1 AS ok, 'батч создан первым документом' AS check FROM batch;
SELECT status = 'PAUSED' AS ok, 'второй документ применён после первого' AS check FROM batch;
SELECT id, status, created_at, updated_at FROM batch;


-- >>> IT-BAT-013  Повторная загрузка того же файла
-- @arrange
-- @assert
-- У элементов нет externalId, идентичность берётся из sha256(файл) + номер строки,
-- поэтому повторная загрузка того же файла — это повтор, а не вторая рассылка.
SELECT count(*) = (SELECT uploaded FROM batch) AS ok,
       'сообщений столько же, сколько принятых элементов' AS check
  FROM message WHERE batch_id IS NOT NULL;
SELECT count(*) = count(DISTINCT dedup_key) AS ok, 'ключи дедупликации уникальны' AS check
  FROM message WHERE batch_id IS NOT NULL;
SELECT count(*) = 0 AS ok, 'ни одной второй попытки отправки на тот же адрес' AS check
  FROM (SELECT a.message_id FROM delivery_attempt a JOIN message m ON m.id = a.message_id
         WHERE m.batch_id IS NOT NULL GROUP BY a.message_id HAVING count(*) > 1) d;


-- >>> IT-BAT-014  Класс трафика батча из заголовка
-- @arrange
-- @assert
-- Единственное место, где класс трафика решает документ, а не топик: заголовок батча.
-- Элементы при этом приезжают в comm.inbound.notification.v1.
SELECT count(*) > 0 AS ok, 'элементы созданы' AS check FROM message WHERE batch_id IS NOT NULL;
SELECT count(DISTINCT traffic_class) = 1 AS ok, 'класс у всех элементов один' AS check
  FROM message WHERE batch_id IS NOT NULL;
SELECT DISTINCT traffic_class AS класс_из_заголовка_батча
  FROM message WHERE batch_id IS NOT NULL;
