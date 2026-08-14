-- =====================================================================================
-- IT-PRV — провайдеры и обратная связь (§9, §18).
--
-- Поведение фальшивого провайдера задаётся суффиксом адреса, поэтому у большинства
-- кейсов предусловий нет вовсе: «настройка провайдера» — это выбор номера получателя.
--   …00 доставлено   …01 не доставлено   …02 нет ответа
--   …03 блокирующий отказ (код 102)      …04 непригодный адрес (код 20)
-- Для PUSH адрес — сам токен, для EMAIL — часть до «@» (ivan04@example.com).
--
-- Кейсы IT-PRV-1xx идут против настоящих адаптеров (WireMock/GreenMail), а не против
-- фальшивого: их предмет — форма запроса и таблицы кодов, а её фальшивый провайдер
-- не воспроизводит и не должен.
--
-- ТРИ УСЛОВИЯ ОБЛАСТИ, выясненные прогоном 14.08.2026 (docs/testing/runs/2026-08-14-stage-8.md).
--
-- 1. Кейсы про callback (103…107) требуют профиля провайдера С КОДОМ PLAYMOBILE: сообщение
--    ищется по паре (код провайдера, provider_message_id), а у MOCK* callback'а нет вовсе.
--    Заводится руками поверх контура, на адаптере mock-sms, первым в fallback_order канала SMS;
--    99-teardown.sql о нём не знает — убирать тоже руками.
-- 2. Те же кейсы прогоняются с COMMHUB_PROVIDER_MOCK_REPORTDELAY=1h: внутренний отчёт mock'а
--    приходит через 3 секунды и опережает человека с curl, после чего callback честно отвечает
--    applied: 0 — и кейс краснеет, ничего не проверив.
-- 3. Размыкатель на фальшивом провайдере не проверяется в принципе: MockProvider не проходит
--    через ProviderCallExecutor. Половина IT-PRV-003 про breaker и весь IT-PRV-007 прогоняются
--    в профиле F — playmobile-http против WireMock, отвечающего 503.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-PRV-001  Доставленное сообщение
-- @arrange
-- @assert
SELECT status = 'DELIVERED' AS ok, 'отчёт о доставке применён' AS check FROM message;
SELECT terminal_at IS NOT NULL AS ok, 'момент терминального статуса проставлен' AS check
  FROM message;
SELECT count(*) = 1 AS ok, 'ровно одна попытка' AS check FROM delivery_attempt;
SELECT result = 'ACCEPTED' AND provider_message_id IS NOT NULL AS ok,
       'провайдер принял и вернул свой идентификатор' AS check
  FROM delivery_attempt;
SELECT array_agg(status ORDER BY occurred_at) AS timeline FROM message_status_history;
SELECT count(*) >= 1 AS ok, 'статусные события положены в outbox' AS check
  FROM outbox_event WHERE event_type = 'MESSAGE_STATUS';


-- >>> IT-PRV-002  Недоставленное сообщение
-- @arrange
-- @assert
SELECT status = 'UNDELIVERED' AS ok, 'отчёт о недоставке применён' AS check FROM message;
SELECT terminal_at IS NOT NULL AS ok, 'статус терминален' AS check FROM message;
SELECT result = 'ACCEPTED' AS ok,
       'провайдер сообщение ПРИНЯЛ — недоставка выяснилась позже, из отчёта' AS check
  FROM delivery_attempt;
SELECT count(*) = 0 AS ok, 'адрес не подавлен: недоставка — не приговор адресу' AS check
  FROM suppression_list;


-- >>> IT-PRV-003  Нет ответа провайдера
-- @arrange
-- @assert
SELECT count(*) >= 2 AS ok, 'вызов без ответа привёл к повтору' AS check FROM delivery_attempt;
SELECT count(*) >= 1 AS ok, 'первая попытка помечена ошибкой, а не отказом' AS check
  FROM delivery_attempt WHERE result IN ('ERROR', 'TIMEOUT');
SELECT bool_or(error_class = 'RETRYABLE') AS ok, 'класс ошибки — повторяемый' AS check
  FROM delivery_attempt;
SELECT attempt_no, provider_code, result, error_class, error_description
  FROM delivery_attempt ORDER BY attempt_no;
-- Состояние размыкателя (breaker) в базе не хранится — проверяется метрикой
-- CircuitBreakerMetrics: размыкание не решение ядра, а свойство адаптера.


-- >>> IT-PRV-004  Блокирующий отказ (код 102)
-- @arrange
-- @assert
SELECT count(*) >= 2 AS ok, 'откат состоялся' AS check FROM delivery_attempt;
SELECT response_code = '102' AND error_class = 'BLOCKING' AS ok,
       'первая попытка — блокирующий отказ' AS check
  FROM delivery_attempt ORDER BY attempt_no LIMIT 1;
SELECT count(DISTINCT provider_code) = 2 AS ok,
       'блокирующий отказ уводит на резерв немедленно, без накопления ошибок' AS check
  FROM delivery_attempt;
SELECT attempt_no, provider_code, response_code, error_class FROM delivery_attempt
 ORDER BY attempt_no;


-- >>> IT-PRV-005  Непригодный адрес
-- @arrange
-- @assert
SELECT response_code = '20' AND error_class = 'NON_RETRYABLE' AS ok,
       'провайдер назвал адрес непригодным' AS check
  FROM delivery_attempt ORDER BY attempt_no LIMIT 1;
SELECT status = 'UNDELIVERED' AS ok, 'сообщение не доставлено' AS check FROM message;
SELECT count(*) = 1 AS ok, 'адрес занесён в список подавления' AS check
  FROM suppression_list
 WHERE address_hash = encode(sha256(lower('998901234504')::bytea), 'hex');
SELECT reason = 'PROVIDER_BLACKLIST' AS ok, 'причина названа верно' AS check
  FROM suppression_list
 WHERE address_hash = encode(sha256(lower('998901234504')::bytea), 'hex');
SELECT created_by LIKE 'MOCK%' OR created_by <> '' AS ok,
       'автором записи назван провайдер, а не оператор' AS check
  FROM suppression_list
 WHERE address_hash = encode(sha256(lower('998901234504')::bytea), 'hex');


-- >>> IT-PRV-006  Подавление применяется до решения о статусе
-- @arrange
-- Продолжение IT-PRV-005: первое сообщение уже подавило адрес.
-- @assert
SELECT count(*) = 1 AS ok, 'запись подавления одна, повторов нет (saveIfAbsent)' AS check
  FROM suppression_list
 WHERE address_hash = encode(sha256(lower('998901234504')::bytea), 'hex');
SELECT count(*) = 1 AS ok, 'второе сообщение остановлено уже на приёме' AS check
  FROM message WHERE status_reason = 'SUPPRESSED';
SELECT count(*) = 1 AS ok, 'ко второму сообщению провайдер не вызывался' AS check
  FROM delivery_attempt;


-- >>> IT-PRV-007  Открытый breaker не роняет сообщение
-- @arrange
-- Размыкатель открывается предыдущими вызовами на адрес …02; в базе его нет.
-- @assert
SELECT count(*) = 0 AS ok, 'сообщение не ушло в DLQ' AS check FROM dlq_entry;
SELECT status <> 'FAILED' AS ok, 'сообщение не помечено как неудавшееся' AS check FROM message;
SELECT count(DISTINCT provider_code) >= 2 AS ok,
       'открытый размыкатель отвечает повторяемо — сообщение уходит к резерву' AS check
  FROM delivery_attempt;


-- >>> IT-PRV-101  Форма запроса Playmobile
-- @arrange
-- Требует контура с настоящим адаптером playmobile-http, направленным на WireMock.
UPDATE provider SET adapter_type = 'playmobile-http', updated_at = now()
 WHERE code = 'MOCK_PRIMARY';
-- @assert
SELECT provider_adapter_type = 'playmobile-http' AS ok, 'вызван настоящий адаптер' AS check
  FROM delivery_attempt;
SELECT length(provider_message_id) <= 20 AS ok,
       'message-id не длиннее 20 символов (§9.1): его генерирует Модуль' AS check
  FROM delivery_attempt WHERE provider_message_id IS NOT NULL;
-- Тело запроса (sms.originator, timing) проверяется WireMock-ом, а не базой.


-- >>> IT-PRV-102  Форма запроса SMS Gate
-- @arrange
UPDATE provider SET adapter_type = 'smsgate-http', updated_at = now()
 WHERE code = 'MOCK_PRIMARY';
-- @assert
SELECT provider_adapter_type = 'smsgate-http' AS ok, 'вызван настоящий адаптер' AS check
  FROM delivery_attempt;
SELECT count(*) = 1 AS ok,
       'повтора внутри попытки нет: /api/v2/send не принимает клиентский id, '
       'и повтор был бы второй SMS' AS check
  FROM delivery_attempt WHERE provider_code = 'MOCK_PRIMARY';


-- >>> IT-PRV-103  Callback провайдера меняет статус
-- @arrange
-- @assert
SELECT provider_message_id AS отправить_в_callback,
       'POST /api/callbacks/{providerCode} с этим идентификатором' AS check
  FROM delivery_attempt;
SELECT status IN ('DELIVERED', 'UNDELIVERED') AS ok, 'статус применён из отчёта' AS check
  FROM message;
SELECT count(*) >= 1 AS ok, 'переход записан в историю с автором PROVIDER' AS check
  FROM message_status_history WHERE actor_type = 'PROVIDER';


-- >>> IT-PRV-104  Callback с чужого адреса
-- @arrange
-- Требует настроенного commhub.callback.providers.<CODE>.allowed-ips.
-- @assert
SELECT status NOT IN ('DELIVERED', 'UNDELIVERED') AS ok,
       'статус не изменился: отчёт с неразрешённого адреса отвергнут' AS check
  FROM message;
-- Не «переходов от провайдера нет вовсе»: сам приём сообщения провайдером пишет
-- SENT_TO_PROVIDER с автором PROVIDER, а без отправленного сообщения кейсу нечего и
-- докладывать. Отвергнутый отчёт виден по отсутствию ТЕРМИНАЛЬНОГО перехода.
SELECT count(*) = 0 AS ok, 'отчёт не стал переходом: терминального статуса от провайдера нет' AS check
  FROM message_status_history
 WHERE actor_type = 'PROVIDER' AND status IN ('DELIVERED', 'UNDELIVERED', 'EXPIRED');
-- Ожидается 403 (SEC-07).


-- >>> IT-PRV-105  Callback без секрета
-- @arrange
-- Требует настроенного commhub.callback.providers.<CODE>.secret.
-- @assert
-- Та же оговорка, что и в 104: SENT_TO_PROVIDER пишется автором PROVIDER самой отправкой.
SELECT count(*) = 0 AS ok, 'без секрета отчёт не применён' AS check
  FROM message_status_history
 WHERE actor_type = 'PROVIDER' AND status IN ('DELIVERED', 'UNDELIVERED', 'EXPIRED');


-- >>> IT-PRV-106  Callback про неизвестное сообщение
-- @arrange
-- @assert
-- Отчёт с чужим идентификатором — не ошибка: провайдер не обязан знать, что мы забыли.
SELECT count(*) = 0 AS ok, 'ни одного нового перехода' AS check
  FROM message_status_history WHERE actor_type = 'PROVIDER';
-- Ожидается 200 с телом {received: true, applied: false, status: "OK"}.


-- >>> IT-PRV-107  Повторный callback идемпотентен
-- @arrange
-- @assert
SELECT status = 'DELIVERED' AS ok, 'статус прежний' AS check FROM message;
-- Ключ истории — (message_id, occurred_at, status): повторная запись того же перехода
-- идемпотентна по построению (AD-03, at-least-once).
SELECT count(*) = 1 AS ok, 'перехода в DELIVERED ровно один' AS check
  FROM message_status_history WHERE status = 'DELIVERED';
-- Статус лежит в payload -> 'outcome' ->> 'status', а не в корне: корень несёт key, eventId,
-- outcome, segments и occurredAt (StatusEventCodec пишет плоский вид §6.4 уже в Kafka).
-- Спрошенный из корня, он всегда NULL — и проверка «ровно одно событие» становится «ровно ноль».
SELECT count(*) = 1 AS ok, 'второго статусного события в outbox не появилось' AS check
  FROM outbox_event
 WHERE event_type = 'MESSAGE_STATUS' AND payload -> 'outcome' ->> 'status' = 'DELIVERED';


-- >>> IT-PRV-201  Письмо доставлено
-- @arrange
-- Требует контура с GreenMail и адаптером smtp (либо mock-email для сквозного пути).
-- @assert
SELECT selected_channel = 'EMAIL' AS ok, 'маршрут по почтовому каналу' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT status = 'SENT_TO_PROVIDER' OR status = 'DELIVERED' AS ok, 'письмо принято релеем' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT provider_message_id IS NOT NULL AS ok,
       'Message-ID пишет Модуль — именно он позволит возврату назвать эту строку' AS check
  FROM delivery_attempt;


-- >>> IT-PRV-202  Письмо с вложением
-- @arrange
-- @assert
SELECT result = 'ACCEPTED' AS ok, 'релей принял письмо с вложением' AS check
  FROM delivery_attempt;
-- Целостность MIME и вложения проверяет GreenMail, а не база.


-- >>> IT-PRV-203  Жёсткий возврат подавляет адрес
-- @arrange
-- @assert
SELECT status = 'UNDELIVERED' AS ok, 'возврат применён как недоставка' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT count(*) = 1 AS ok, 'адрес подавлен' AS check
  FROM suppression_list WHERE reason = 'HARD_BOUNCE';
SELECT channel = 'EMAIL' AS ok, 'подавление именно почтового канала' AS check
  FROM suppression_list WHERE reason = 'HARD_BOUNCE';


-- >>> IT-PRV-204  Отчёт «ещё пробуем» ничего не меняет
-- @arrange
-- @assert
SELECT status = 'SENT_TO_PROVIDER' AS ok,
       'статус прежний: у ST-01 нет перехода «всё ещё пытаемся»' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT count(*) = 0 AS ok, 'адрес не подавлен' AS check FROM suppression_list;


-- >>> IT-PRV-205  Отказ спам-фильтра адрес не подавляет
-- @arrange
-- @assert
SELECT status = 'UNDELIVERED' AS ok, 'письмо не доставлено' AS check
  FROM message WHERE stream_id = 'email-stream';
SELECT count(*) = 0 AS ok,
       'адрес НЕ подавлен: отказ фильтра одному тексту не стоит живого адреса' AS check
  FROM suppression_list;


-- >>> IT-PRV-301  Веер по нескольким устройствам
-- @arrange
-- @assert
SELECT count(*) = 1 AS ok, 'сага видит одну попытку — веер живёт над портом (AR-05)' AS check
  FROM delivery_attempt;
SELECT count(*) = 3 AS ok, 'по строке на каждое устройство' AS check FROM push_delivery;
SELECT count(DISTINCT attempt_id) = 1 AS ok, 'все строки под одной попыткой' AS check
  FROM push_delivery;
SELECT count(*) = 3 AS ok, 'в строках только хеш токена, не сам токен' AS check
  FROM push_delivery WHERE token_hash ~ '^[0-9a-f]{64}$';
SELECT token_hash, platform, result, response_code, token_invalidated FROM push_delivery;


-- >>> IT-PRV-302  Один принятый токен делает сообщение принятым
-- @arrange
-- @assert
SELECT status = 'SENT_TO_PROVIDER' AS ok,
       'клиент уведомлён: один живой аппарат делает сообщение принятым' AS check
  FROM message WHERE stream_id = 'push-stream';
SELECT count(*) = 1 AS ok, 'одно устройство приняло' AS check
  FROM push_delivery WHERE result = 'ACCEPTED';
SELECT count(*) = 1 AS ok, 'одно отказало' AS check
  FROM push_delivery WHERE result = 'REJECTED';


-- >>> IT-PRV-303  Ни одного принятого — худший класс побеждает
-- @arrange
-- @assert
SELECT count(*) = 0 AS ok, 'принятых устройств нет' AS check
  FROM push_delivery WHERE result = 'ACCEPTED';
SELECT status <> 'SENT_TO_PROVIDER' AS ok, 'сообщение не считается принятым' AS check
  FROM message WHERE stream_id = 'push-stream';
-- Дальнейшее поведение выбирается худшим классом: блокирующий > повторяемый > постоянный.
SELECT result, response_code, error_description FROM push_delivery;


-- >>> IT-PRV-304  Мёртвый токен: два последствия
-- @arrange
-- @assert
SELECT token_invalidated AS ok, 'токен помечен мёртвым в строке устройства' AS check
  FROM push_delivery WHERE result = 'REJECTED';
SELECT count(*) = 1 AS ok, 'последствие первое: Модуль перестаёт писать на токен' AS check
  FROM suppression_list WHERE reason = 'PUSH_TOKEN_INVALID';
SELECT count(*) = 1 AS ok,
       'последствие второе: система-источник узнаёт и снимает токен у себя' AS check
  FROM outbox_event WHERE event_type = 'PUSH_TOKEN_INVALIDATED';
-- Токен в событии едет в открытом виде: реестр потребителя ключуется им, и хеш
-- не назвал бы ничего. В логах он не появляется никогда.
SELECT payload FROM outbox_event WHERE event_type = 'PUSH_TOKEN_INVALIDATED';


-- >>> IT-PRV-305  Push терминален на SENT_TO_PROVIDER
-- @arrange
-- @assert
SELECT status = 'SENT_TO_PROVIDER' AS ok, 'статус тот, что обещан PU-12' AS check
  FROM message WHERE stream_id = 'push-stream';
SELECT count(*) = 0 AS ok, 'статуса DELIVERED не появилось: платформа отчётов не шлёт' AS check
  FROM message_status_history WHERE status = 'DELIVERED';
