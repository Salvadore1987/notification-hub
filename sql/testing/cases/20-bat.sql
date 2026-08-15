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
--
-- ДВА ПРАВИЛА СБОРКИ ПРЕДУСЛОВИЙ, без которых кейсы области краснеют на исправном Модуле:
--
--   1. Проверка, истинная только «пока батч в работе», недостижима: диспетчер со фиктивным
--      провайдером разбирает тысячу элементов меньше чем за четыре секунды. Поэтому все
--      проверки здесь написаны на факты, не зависящие от момента чтения (дефект D-25).
--      Предусловие «батч в работе» собирается ВТОРЫМ ЧАНКОМ, залитым после команды, —
--      скоростью нажатия его не получить.
--
--   2. Кейс, заливающий больше одного чанка (008, 009, 010, 013), ОБЯЗАН объявить в
--      заголовке expectedTotal больше, чем несёт первый чанк. Батч, чей processed дошёл
--      до total, становится COMPLETED, а терминальный батч элементов не принимает вовсе:
--      второй чанк получит «400 cannot add items to a batch in status COMPLETED». Это
--      дефект Модуля D-29 (прогон этапа 10), а не свойство обвязки.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-BAT-001  Создание батча и загрузка элементов
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'батч создан' AS check FROM batch;
-- «Виден сразу» проверяется порядком отметок времени, а не текущим статусом (дефект
-- обвязки D-25): статус ACCEPTED/PROCESSING истинен считанные секунды — сто элементов
-- на фиктивном провайдере доезжают до COMPLETED быстрее, чем человек доходит до проверок,
-- и правильный Модуль краснел. Строка батча заведена заголовком, до первого элемента.
SELECT b.created_at <= min(m.accepted_at) AS ok,
       'батч виден с момента приёма — строка заведена раньше первого элемента' AS check
  FROM batch b JOIN message m ON m.batch_id = b.id
 GROUP BY b.created_at;
SELECT status IN ('ACCEPTED', 'PROCESSING', 'COMPLETED') AS ok,
       'батч в штатном состоянии жизненного цикла' AS check FROM batch;
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
-- Чанк из пяти, два элемента без обязательной переменной шаблона: ответ 202, в теле —
-- две записи об отказе, три сообщения приняты. Отказ элемента не отменяет приём чанка.
--
-- Раздражитель кейса исправлен (дефект набора D-26): «элемент без адреса» до конвейера
-- не доходит — это нарушение контракта, и кодек отвергает им ВЕСЬ чанк, отвечая 400 с
-- указателем items[i].recipient (то же семейство, что IT-BAT-006 и IT-BAT-007).
-- Поэлементный отказ рождает только вердикт конвейера, и опознаётся он не указателем,
-- а externalMessageId: у ItemRejectionResponse полей ровно три — externalMessageId,
-- reason, detail.
SELECT count(*) = 3 AS ok, 'три пригодных элемента приняты' AS check
  FROM message WHERE batch_id IS NOT NULL;
-- uploaded считает ВЕСЬ чанк, а не принятую его часть (Batch.addItems): знаменатель
-- обязан включать отказанные элементы, иначе processed их посчитает, а total — нет.
SELECT uploaded = 5 AS ok, 'счётчик загруженных учёл весь чанк' AS check FROM batch;
SELECT processed >= 2 AS ok, 'отказанные элементы засчитаны обработанными сразу' AS check
  FROM batch;
-- Отклонённый элемент строки message не оставляет — как и одиночная отправка с тем же
-- отказом (422 и ничего в базе). Единственная его запись — rejections[] в ответе.
SELECT total, uploaded, processed, sent, delivered, failed FROM batch;


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
-- «Новых попыток не появилось» проверяется остатком работы, а не отметками времени
-- (дефект обвязки D-25): сравнение request_at с batch.updated_at ложно зеленеет, потому
-- что отчёт о доставке уже отправленного двигает updated_at вперёд сам. Пока батч на
-- паузе, залитый на ней чанк обязан стоять без единой попытки — и стоять сколько угодно
-- долго, тогда как диспетчер разбирает тысячу сообщений за секунды.
SELECT count(*) > 0 AS ok, 'на паузе осталась неотправленная работа' AS check
  FROM message m
 WHERE m.batch_id IS NOT NULL
   AND m.status = 'QUEUED'
   AND NOT EXISTS (SELECT 1 FROM delivery_attempt a WHERE a.message_id = m.id);
-- Уже отправленные не отзываются — их отзывать не у кого.
SELECT count(*) > 0 AS ok, 'отправленное до паузы осталось отправленным' AS check
  FROM message m
 WHERE m.batch_id IS NOT NULL
   AND m.status IN ('SENT_TO_PROVIDER', 'DELIVERED')
   AND EXISTS (SELECT 1 FROM delivery_attempt a WHERE a.message_id = m.id);


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
-- Раздражитель кейса исправлен (дефект набора D-28): идентичность из sha256(файл) +
-- номер строки живёт ТОЛЬКО в панельной заливке CSV (RecipientListCsvCodec,
-- POST /api/admin/v1/send/batch), а админ-BFF лежит за границей набора. На входе §8.2
-- элемент без externalMessageId отвергается кодеком («items[0].externalMessageId: is
-- required»), поэтому повтор проверяется штатным ключом FR-1.5 — (streamId,
-- externalMessageId) в окне дедупликации: тот же чанк заливается дважды.
--
-- Заголовок обязан объявить больше, чем несёт один чанк: батч, у которого processed
-- дошёл до total, становится COMPLETED, а терминальный батч элементов не принимает
-- вовсе — вторая заливка получит 400 вместо ответа о повторах (см. отчёт этапа, §6).
SELECT (SELECT count(*) FROM message WHERE batch_id IS NOT NULL) < (SELECT uploaded FROM batch) AS ok,
       'повторно залитые элементы новых сообщений не завели' AS check;
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
--
-- Решает документ именно ДЛЯ БАТЧА: топик comm.inbound.batch-control.v1 общий для всех
-- классов и своего класса не несёт, поэтому batch.traffic_class взять неоткуда, кроме
-- тела заголовка. У элементов, приехавших из Kafka, класс по-прежнему топиковый — они
-- идут мимо SubmitBatchService обычными IK-03, и это TC-01 как задумано: payload,
-- умеющий себя переклассифицировать, вышел бы из пула, в который его положили.
SELECT count(*) > 0 AS ok, 'элементы созданы' AS check FROM message WHERE batch_id IS NOT NULL;
SELECT traffic_class = 'NOTIFICATION' AS ok,
       'класс батча взят из тела заголовка — у топика управления своего класса нет' AS check
  FROM batch;
SELECT count(DISTINCT traffic_class) = 1 AS ok, 'класс у всех элементов один' AS check
  FROM message WHERE batch_id IS NOT NULL;
SELECT DISTINCT traffic_class AS класс_элементов_из_топика
  FROM message WHERE batch_id IS NOT NULL;
