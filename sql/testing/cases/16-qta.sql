-- =====================================================================================
-- IT-QTA — квоты (FR-2.4).
--
-- Три измерения и строгий порядок: поток → канал → провайдер. Первое исчерпанное
-- побеждает и называется в тексте отказа; check и register обязаны ходить по одним и тем
-- же scope, иначе списанное и проверенное разойдутся.
--
-- quota_counter — не «строка на поток», а строка на (stream_id, channel, provider_id,
-- window_type, period_start), причём незадействованные измерения хранятся как пустая
-- строка и нулевой UUID, а не как NULL: уникальный индекс по NULL не работает.
-- Поэтому scope потока — это (stream_id='…', channel='', provider_id=00000000-…).
--
-- Единицы: для SMS — сегменты (max(1, segments)), для остальных каналов — 1.
-- =====================================================================================

\set ON_ERROR_STOP on
SET search_path TO comm_hub, public;


-- >>> IT-QTA-001  Суточная квота потока исчерпана
-- @arrange
-- Квота 3/сутки уже стоит на потоке quota-day в 01-contour.sql. Предусловий нет:
-- четыре отправки делает действие кейса.
-- @assert
SELECT quota_config ->> 'dailyCount' = '3'
   AND quota_config ->> 'behavior' = 'BLOCK_AND_ALERT' AS ok,
       'квота потока настроена как ожидается' AS check
  FROM stream WHERE id = 'quota-day';
SELECT count(*) = 3 AS ok, 'первые три приняты' AS check
  FROM message WHERE stream_id = 'quota-day' AND status_reason IS DISTINCT FROM 'QUOTA_EXCEEDED';
SELECT count(*) = 1 AS ok, 'четвёртое отклонено квотой' AS check
  FROM message WHERE stream_id = 'quota-day' AND status_reason = 'QUOTA_EXCEEDED';
SELECT counter = 3 AS ok, 'счётчик измерения «поток» списал ровно три' AS check
  FROM quota_counter
 WHERE stream_id = 'quota-day' AND channel = ''
   AND provider_id = '00000000-0000-0000-0000-000000000000'
   AND window_type = 'DAY' AND period_start = current_date;
SELECT stream_id, channel, provider_id, window_type, period_start, counter, cost_counter
  FROM quota_counter ORDER BY stream_id, channel, window_type;


-- >>> IT-QTA-002  Поведение ALERT_ONLY
-- @arrange
-- @assert
SELECT quota_config ->> 'behavior' = 'ALERT_ONLY' AS ok, 'поток только алертит' AS check
  FROM stream WHERE id = 'quota-alert';
SELECT count(*) = 3 AS ok, 'все три отправлены несмотря на исчерпание' AS check
  FROM message WHERE stream_id = 'quota-alert' AND status_reason IS NULL;
SELECT count(*) = 0 AS ok, 'ни одного отказа по квоте' AS check
  FROM message WHERE status_reason = 'QUOTA_EXCEEDED';
SELECT counter = 3 AS ok, 'списаны все три: алерт не отменяет учёт' AS check
  FROM quota_counter
 WHERE stream_id = 'quota-alert' AND window_type = 'DAY' AND period_start = current_date;
-- Рост метрики quotaBreached проверяется вне SQL (MetricsPort).


-- >>> IT-QTA-003  Квота канала
-- @arrange
UPDATE channel
   SET quota_config = '{"dailyCount": 2, "monthlyCount": null, "dailyCost": null,
                        "monthlyCost": null, "behavior": "BLOCK_AND_ALERT"}'::jsonb,
       updated_at = now()
 WHERE code = 'SMS';
-- @assert
-- Три сообщения из РАЗНЫХ потоков: квота потока не сработает, сработает канальная.
SELECT count(*) = 1 AS ok, 'третье отклонено' AS check
  FROM message WHERE status_reason = 'QUOTA_EXCEEDED';
SELECT counter = 2 AS ok, 'счётчик измерения «канал» списал два' AS check
  FROM quota_counter
 WHERE stream_id = '' AND channel = 'SMS'
   AND provider_id = '00000000-0000-0000-0000-000000000000'
   AND window_type = 'DAY' AND period_start = current_date;
-- В тексте отказа должно быть названо измерение «channel».
SELECT id, status, status_reason FROM message WHERE status_reason = 'QUOTA_EXCEEDED';


-- >>> IT-QTA-004  Квота провайдера
-- @arrange
UPDATE provider
   SET quota_config = '{"dailyCount": 2, "monthlyCount": null, "dailyCost": null,
                        "monthlyCost": null, "behavior": "BLOCK_AND_ALERT"}'::jsonb,
       updated_at = now()
 WHERE code = 'MOCK-PRIMARY';
-- @assert
SELECT count(*) = 1 AS ok, 'третье отклонено' AS check
  FROM message WHERE status_reason = 'QUOTA_EXCEEDED';
SELECT counter = 2 AS ok, 'счётчик измерения «провайдер» списал два' AS check
  FROM quota_counter
 WHERE stream_id = '' AND channel = 'SMS'
   AND provider_id = '01900000-0000-7000-8000-000000000001'
   AND window_type = 'DAY' AND period_start = current_date;


-- >>> IT-QTA-005  Первое исчерпанное измерение побеждает
-- @arrange
-- Исчерпаны заранее и поток, и канал. Ожидается измерение «stream»: порядок
-- поток → канал → провайдер, и первое BLOCKED прекращает обход.
UPDATE channel
   SET quota_config = '{"dailyCount": 1, "monthlyCount": null, "dailyCost": null,
                        "monthlyCost": null, "behavior": "BLOCK_AND_ALERT"}'::jsonb,
       updated_at = now()
 WHERE code = 'SMS';

INSERT INTO quota_counter (id, stream_id, channel, provider_id, window_type, period_start, counter)
VALUES ('01960000-0000-7000-8000-000000000051', 'quota-day', '',
        '00000000-0000-0000-0000-000000000000', 'DAY', current_date, 3),
       ('01960000-0000-7000-8000-000000000052', '', 'SMS',
        '00000000-0000-0000-0000-000000000000', 'DAY', current_date, 1)
ON CONFLICT (stream_id, channel, provider_id, window_type, period_start)
DO UPDATE SET counter = EXCLUDED.counter;
-- @assert
SELECT status_reason = 'QUOTA_EXCEEDED' AS ok, 'сообщение отклонено' AS check
  FROM message WHERE stream_id = 'quota-day';
-- В тексте отказа обязано стоять «stream»: если там «channel», порядок обхода нарушен.
SELECT id, status_reason FROM message WHERE stream_id = 'quota-day';


-- >>> IT-QTA-006  Единицы SMS считаются сегментами
-- @arrange
-- Квота 3, текст на два сегмента (UCS-2, 100 символов кириллицей): 2 + 2 > 3.
-- @assert
SELECT count(*) = 1 AS ok, 'первое сообщение принято' AS check
  FROM message WHERE stream_id = 'quota-day' AND status_reason IS NULL;
SELECT segments = 2 AS ok, 'сегментов действительно два' AS check
  FROM message WHERE stream_id = 'quota-day' AND status_reason IS NULL;
SELECT count(*) = 1 AS ok, 'второе отклонено: списываются сегменты, а не сообщения' AS check
  FROM message WHERE stream_id = 'quota-day' AND status_reason = 'QUOTA_EXCEEDED';
SELECT counter = 2 AS ok, 'в счётчике два сегмента, а не одно сообщение' AS check
  FROM quota_counter
 WHERE stream_id = 'quota-day' AND window_type = 'DAY' AND period_start = current_date;


-- >>> IT-QTA-007  Отклонённое сообщение квоту не тратит
-- @arrange
-- Первое сообщение уйдёт на адрес …04 (непригодный) — провайдер его отвергнет.
-- Квота при этом уже списана на приёме, поэтому предмет кейса тонкий: списывается
-- то, что было ПРИНЯТО, а не то, что доставлено. Отказ конвейера (до registerSend)
-- не списывает ничего — его и проверяем: подавляем адрес заранее.
INSERT INTO suppression_list (id, channel, address_hash, client_id, reason, valid_until, created_by, created_at)
VALUES ('01960000-0000-7000-8000-000000000071', 'SMS',
        encode(sha256(lower('998901234504')::bytea), 'hex'), NULL,
        'OPT_OUT', NULL, 'qa-arrange', now());
-- @assert
SELECT count(*) = 1 AS ok, 'одно сообщение отклонено конвейером' AS check
  FROM message WHERE status_reason = 'SUPPRESSED';
SELECT count(*) = 3 AS ok, 'все три штатных приняты — отказ квоту не съел' AS check
  FROM message WHERE stream_id = 'quota-day' AND status_reason IS NULL;
SELECT counter = 3 AS ok, 'списаны только принятые' AS check
  FROM quota_counter
 WHERE stream_id = 'quota-day' AND window_type = 'DAY' AND period_start = current_date;


-- >>> IT-QTA-008  Безлимитная квота ничего не считает
-- @arrange
-- core-banking квоты не имеет — это и проверяем.
-- @assert
SELECT quota_config IS NULL AS ok, 'у потока квоты нет' AS check
  FROM stream WHERE id = 'core-banking';
SELECT count(*) = 50 AS ok, 'все пятьдесят приняты' AS check
  FROM message WHERE stream_id = 'core-banking';
SELECT count(*) = 0 AS ok,
       'безлимитное измерение не читается и не пишется — OTP-путь за него не платит' AS check
  FROM quota_counter WHERE stream_id = 'core-banking';


-- >>> IT-QTA-009  Месячная квота при свободной суточной
-- @arrange
UPDATE stream
   SET quota_config = '{"dailyCount": null, "monthlyCount": 5, "dailyCost": null,
                        "monthlyCost": null, "behavior": "BLOCK_AND_ALERT"}'::jsonb,
       updated_at = now()
 WHERE id = 'quota-day';
-- @assert
SELECT count(*) = 5 AS ok, 'первые пять приняты' AS check
  FROM message WHERE stream_id = 'quota-day' AND status_reason IS NULL;
SELECT count(*) = 1 AS ok, 'шестое отклонено месячной квотой' AS check
  FROM message WHERE stream_id = 'quota-day' AND status_reason = 'QUOTA_EXCEEDED';
SELECT counter = 5 AS ok, 'месячное окно сосчитано отдельно от суточного' AS check
  FROM quota_counter
 WHERE stream_id = 'quota-day' AND window_type = 'MONTH'
   AND period_start = date_trunc('month', current_date)::date;
SELECT window_type, period_start, counter FROM quota_counter
 WHERE stream_id = 'quota-day' ORDER BY window_type;
