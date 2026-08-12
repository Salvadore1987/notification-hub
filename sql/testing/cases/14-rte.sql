-- =====================================================================================
-- IT-RTE — маршрутизация и откат (FR-2.2, FR-6.3, PR-01, NF-07).
--
-- Канал в системе ровно один на код: channel.code — первичный ключ с CHECK по
-- перечислению Channel, второго профиля SMS завести нельзя. Поэтому кейсы, которым нужна
-- другая стратегия балансировки или статус MAINTENANCE, переписывают ту же строку.
-- Возвращать её обратно не нужно: 01-contour.sql применяется перед каждым кейсом заново.
--
-- Снимок конфигурации кэшируется на commhub.config.cache.refresh-interval (10 с,
-- потолок 30 с по NF-07). Изменения, внесённые здесь, доезжают до маршрутизатора
-- не мгновенно — это не дефект скрипта, а поведение, которое проверяет IT-RTE-014.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-RTE-001  PRIMARY_ONLY выбирает первый в порядке отката
-- @arrange
-- @assert
SELECT count(*) = 5 AS ok, 'приняты все пять сообщений' AS check FROM message;
SELECT count(*) = 5 AS ok, 'все ушли к основному провайдеру' AS check
  FROM message WHERE selected_provider_code = 'MOCK-PRIMARY';
SELECT selected_provider_code, count(*) FROM message GROUP BY 1 ORDER BY 1;


-- >>> IT-RTE-002  ROUND_ROBIN чередует провайдеров
-- @arrange
UPDATE channel SET balancing_strategy = 'ROUND_ROBIN',
                   fallback_order = '["MOCK-PRIMARY", "MOCK-RESERVE"]'::jsonb,
                   updated_at = now()
 WHERE code = 'SMS';
-- @assert
SELECT count(*) = 10 AS ok, 'приняты все десять' AS check FROM message;
SELECT count(*) = 2 AS ok, 'задействованы оба провайдера' AS check
  FROM (SELECT DISTINCT selected_provider_code FROM message) s;
SELECT bool_and(n = 5) AS ok, 'нагрузка разошлась поровну' AS check
  FROM (SELECT count(*) AS n FROM message GROUP BY selected_provider_code) s;
SELECT selected_provider_code, count(*) FROM message GROUP BY 1 ORDER BY 1;


-- >>> IT-RTE-003  WEIGHTED соблюдает веса
-- @arrange
UPDATE provider SET weight = 3, updated_at = now() WHERE code = 'MOCK-PRIMARY';
UPDATE provider SET weight = 1, updated_at = now() WHERE code = 'MOCK-RESERVE';
UPDATE channel SET balancing_strategy = 'WEIGHTED',
                   fallback_order = '["MOCK-PRIMARY", "MOCK-RESERVE"]'::jsonb,
                   updated_at = now()
 WHERE code = 'SMS';
-- @assert
SELECT count(*) = 40 AS ok, 'приняты все сорок' AS check FROM message;
-- Веса 3:1 на сорока сообщениях дают 30/10; допуск — один полный цикл (4 сообщения).
SELECT count(*) BETWEEN 26 AND 34 AS ok, 'основной получил примерно три четверти' AS check
  FROM message WHERE selected_provider_code = 'MOCK-PRIMARY';
SELECT count(*) BETWEEN 6 AND 14 AS ok, 'резерв получил примерно четверть' AS check
  FROM message WHERE selected_provider_code = 'MOCK-RESERVE';
SELECT selected_provider_code, count(*) FROM message GROUP BY 1 ORDER BY 1;


-- >>> IT-RTE-004  LEAST_COST выбирает дешёвого
-- @arrange
UPDATE channel SET balancing_strategy = 'LEAST_COST',
                   fallback_order = '["MOCK-PRIMARY", "MOCK-CHEAP"]'::jsonb,
                   updated_at = now()
 WHERE code = 'SMS';
-- @assert
SELECT selected_provider_code = 'MOCK-CHEAP' AS ok,
       'выбран провайдер с тарифом 90.0000 против 120.5000' AS check
  FROM message;
SELECT cost IS NOT NULL AND cost_currency = 'UZS' AS ok, 'стоимость посчитана' AS check
  FROM message;
SELECT selected_provider_code, segments, cost, cost_currency FROM message;


-- >>> IT-RTE-005  LEAST_COST при разных валютах
-- @arrange
UPDATE provider
   SET tariff = '{"perMessage": null, "perSegment": {"amount": "0.0100", "currency": "USD"}}'::jsonb,
       updated_at = now()
 WHERE code = 'MOCK-CHEAP';
UPDATE channel SET balancing_strategy = 'LEAST_COST',
                   fallback_order = '["MOCK-PRIMARY", "MOCK-CHEAP"]'::jsonb,
                   updated_at = now()
 WHERE code = 'SMS';
-- @assert
-- Провайдер в другой валюте пропускается: сравнивать 90 USD с 120 UZS маршрутизатор
-- не имеет права, а отказывать в отправке из-за валюты — тем более.
SELECT status_reason IS DISTINCT FROM 'NO_ROUTE_AVAILABLE' AS ok, 'отказа нет' AS check
  FROM message;
SELECT selected_provider_code = 'MOCK-PRIMARY' AS ok,
       'выбран провайдер в валюте текущего дешёвого' AS check
  FROM message;


-- >>> IT-RTE-006  Политика маршрутизации важнее умолчания потока
-- @arrange
-- Поток email-stream по умолчанию шлёт в EMAIL; политика уводит его в SMS.
-- Получатель кейса обязан иметь и email, и msisdn — иначе SMS отфильтруется как
-- недоставляемый канал, и проверка ничего не докажет.
INSERT INTO routing_policy (id, scope, match, action, priority, enabled)
VALUES ('01940000-0000-7000-8000-000000000006', 'STREAM',
        '{"streamId": "email-stream", "trafficClass": null, "minPriority": null, "channel": null}'::jsonb,
        '{"channel": "SMS", "providerOrder": null, "balancingStrategy": null}'::jsonb,
        100, true);
-- @assert
SELECT selected_channel = 'SMS' AS ok, 'победила политика, а не умолчание потока' AS check
  FROM message WHERE stream_id = 'email-stream';


-- >>> IT-RTE-007  NoRoute: нет пригодного адреса
-- @arrange
-- @assert
SELECT status_reason = 'NO_ROUTE_AVAILABLE' AS ok, 'причина отказа названа' AS check FROM message;
SELECT count(*) = 0 AS ok, 'маршрут не выбран' AS check
  FROM message WHERE selected_provider_code IS NOT NULL;
-- Текст отказа: «recipient has no usable address for the planned channels».


-- >>> IT-RTE-008  NoRoute: канал не настроен
-- @arrange
DELETE FROM channel WHERE code = 'PUSH';
-- @assert
SELECT count(*) = 0 AS ok, 'профиля канала PUSH действительно нет' AS check
  FROM channel WHERE code = 'PUSH';
SELECT status_reason = 'NO_ROUTE_AVAILABLE' AS ok, 'отказ маршрутизации' AS check
  FROM message WHERE stream_id = 'push-stream';
-- Текст отказа: «channel PUSH is not configured».


-- >>> IT-RTE-009  NoRoute: канал в обслуживании
-- @arrange
UPDATE channel SET status = 'MAINTENANCE', updated_at = now() WHERE code = 'SMS';
-- @assert
SELECT status = 'MAINTENANCE' AS ok, 'канал действительно в обслуживании' AS check
  FROM channel WHERE code = 'SMS';
SELECT status_reason = 'NO_ROUTE_AVAILABLE' AS ok, 'отказ маршрутизации' AS check FROM message;
-- Текст отказа: «channel SMS is MAINTENANCE».


-- >>> IT-RTE-010  NoRoute: нет выбираемого провайдера
-- @arrange
UPDATE provider SET enabled = false, updated_at = now()
 WHERE code IN ('MOCK-PRIMARY', 'MOCK-RESERVE');
-- @assert
SELECT count(*) = 0 AS ok, 'выбираемых SMS-провайдеров в цепочке не осталось' AS check
  FROM provider WHERE channel = 'SMS' AND enabled AND NOT maintenance
   AND code IN ('MOCK-PRIMARY', 'MOCK-RESERVE');
SELECT status_reason = 'NO_ROUTE_AVAILABLE' AS ok, 'отказ маршрутизации' AS check FROM message;
-- Текст отказа: «no selectable provider for channel SMS».


-- >>> IT-RTE-011  Откат на резервного при отказе основного
-- @arrange
-- Предусловий нет: поведение задаёт адрес получателя — суффикс …02 (нет ответа).
-- @assert
SELECT count(*) >= 2 AS ok, 'попыток больше одной' AS check FROM delivery_attempt;
SELECT count(DISTINCT provider_code) = 2 AS ok, 'задействованы оба провайдера' AS check
  FROM delivery_attempt;
SELECT provider_code = 'MOCK-PRIMARY' AS ok, 'первая попытка — к основному' AS check
  FROM delivery_attempt ORDER BY attempt_no LIMIT 1;
SELECT provider_code = 'MOCK-RESERVE' AS ok, 'последняя — к резервному' AS check
  FROM delivery_attempt ORDER BY attempt_no DESC LIMIT 1;
SELECT attempt_no, provider_code, result, error_class, response_code
  FROM delivery_attempt ORDER BY attempt_no;


-- >>> IT-RTE-012  Провайдер в состоянии DOWN не выбирается
-- @arrange
UPDATE provider SET health_status = 'DOWN',
                    health_detail = 'выставлено предусловием кейса',
                    health_checked_at = now(),
                    updated_at = now()
 WHERE code = 'MOCK-PRIMARY';
-- @assert
SELECT selected_provider_code = 'MOCK-RESERVE' AS ok,
       'маршрут сразу на резерв: DOWN делает провайдера невыбираемым' AS check
  FROM message;
SELECT count(*) = 0 AS ok, 'к DOWN-провайдеру не было ни одной попытки' AS check
  FROM delivery_attempt WHERE provider_code = 'MOCK-PRIMARY';


-- >>> IT-RTE-013  Испытательный срок после тишины
-- @arrange
-- Провайдер DOWN, последняя проверка — заведомо дальше recovery-after (по умолчанию 2 мин).
-- Провайдер без трафика никогда не наберёт цифр, которые его оправдают, поэтому после
-- тишины он возвращается в UNKNOWN: выбираемый, но недоверенный.
UPDATE provider SET health_status = 'DOWN',
                    health_detail = 'выставлено предусловием кейса',
                    health_checked_at = now() - interval '1 hour',
                    updated_at = now()
 WHERE code = 'MOCK-PRIMARY';
-- @assert
SELECT health_status = 'UNKNOWN' AS ok,
       'после окна тишины провайдер вернулся в испытательный срок' AS check
  FROM provider WHERE code = 'MOCK-PRIMARY';
SELECT selected_provider_code = 'MOCK-PRIMARY' AS ok, 'и снова выбирается' AS check
  FROM message;
-- После окна с трафиком и без отказов ожидается переход в UP:
SELECT health_status, health_detail, health_checked_at
  FROM provider WHERE code LIKE 'MOCK-%' ORDER BY code;


-- >>> IT-RTE-014  Изменение конфигурации применяется без перезапуска
-- @arrange
-- Меняем порядок отката, не перезапуская Модуль. Снимок обновится в течение
-- commhub.config.cache.refresh-interval (10 с; потолок 30 с проверяется при старте).
UPDATE channel SET fallback_order = '["MOCK-RESERVE", "MOCK-PRIMARY"]'::jsonb,
                   updated_at = now()
 WHERE code = 'SMS';
-- @assert
SELECT fallback_order = '["MOCK-RESERVE", "MOCK-PRIMARY"]'::jsonb AS ok,
       'новый порядок записан' AS check
  FROM channel WHERE code = 'SMS';
-- Сообщение, отправленное позднее чем через refresh-interval после правки:
SELECT selected_provider_code = 'MOCK-RESERVE' AS ok,
       'маршрутизатор увидел правку без рестарта (NF-07)' AS check
  FROM message ORDER BY accepted_at DESC LIMIT 1;
