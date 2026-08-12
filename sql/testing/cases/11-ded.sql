-- =====================================================================================
-- IT-DED — дедупликация (FR-1.5).
--
-- Ключ дедупликации: явный dedupKey либо «streamId:externalMessageId». Окно по умолчанию
-- 24 ч (commhub.dedup.window). Кейсы, требующие «прошло больше суток», сдвигают не часы,
-- а саму запись реестра: истекший ключ — это строка с expires_at в прошлом, и на живом
-- стенде это единственный способ проверить окно, не дожидаясь суток.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-DED-001  Повтор по (streamId, externalMessageId)
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'вторая попытка новой строки не создала' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT count(*) = 1 AS ok, 'ключ дедупликации захвачен ровно один раз' AS check
  FROM dedup_registry;
SELECT count(*) <= 1 AS ok, 'к провайдеру ушла максимум одна попытка' AS check
  FROM delivery_attempt;
-- Источнику на второй запрос отвечает статус DUPLICATE; отдельной строки под него нет.


-- >>> IT-DED-002  Повтор по явному dedupKey
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'принято одно сообщение из двух с общим dedupKey' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT dedup_key, message_id FROM dedup_registry;


-- >>> IT-DED-003  Повтор через другой транспорт
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok,
       'ключ считается в конвейере, а не в адаптере: REST и Kafka видят один ключ' AS check
  FROM message WHERE stream_id = 'core-banking';


-- >>> IT-DED-004  За пределами окна дедупликации
-- @arrange
-- Действие кейса делится надвое: (1) отправить документ, (2) выполнить этот сдвиг,
-- (3) отправить тот же документ снова. Если запускается через run-case.sh, шаг 2
-- выполняется вручную между двумя отправками — предусловие здесь пустое намеренно.
-- Сдвиг: UPDATE dedup_registry SET registered_at = now() - interval '25 hours',
--                                  expires_at    = now() - interval '1 hour';
-- @assert
SELECT count(*) = 2 AS ok, 'за окном документ принят как новое сообщение' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT count(*) = 1 AS ok, 'в реестре остался ключ последнего сообщения' AS check
  FROM dedup_registry;


-- >>> IT-DED-005  Одинаковый externalMessageId в разных потоках
-- @arrange
-- @assert
SELECT count(*) = 2 AS ok, 'оба сообщения приняты: ключ включает streamId' AS check
  FROM message WHERE external_id = (SELECT external_id FROM message LIMIT 1);
SELECT count(DISTINCT dedup_key) = 2 AS ok, 'ключи различаются потоком' AS check
  FROM dedup_registry;
SELECT dedup_key FROM dedup_registry ORDER BY dedup_key;


-- >>> IT-DED-006  Гонка на захвате ключа
-- @arrange
-- @assert
-- Два одновременных запроса с одним ключом: ровно одно принято, ровно одно DUPLICATE.
SELECT count(*) = 1 AS ok, 'создана ровно одна строка сообщения' AS check FROM message;
SELECT count(*) = 1 AS ok, 'ключ захвачен ровно один раз (атомарный claim)' AS check
  FROM dedup_registry;
SELECT count(*) <= 1 AS ok, 'провайдер вызван не более одного раза' AS check
  FROM delivery_attempt;
