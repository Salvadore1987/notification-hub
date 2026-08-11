-- -------------------------------------------------------------------------------------
-- Планирование отправки: аренда строки и время следующей попытки (AD-04, PR-01, ADR-0039).
--
-- Три колонки — бухгалтерия очереди, а не состояние агрегата: их не пишет UPSERT
-- сообщения, иначе ProcessProviderStatus или ExpireMessages, сохраняя ту же строку,
-- затирали бы чужую аренду, ничего о ней не зная.
--
-- next_attempt_at материализует backoff саги. Без него диспетчер, увидев RETRYING,
-- забирает строку тем же проходом и расходует бюджет из пяти попыток за секунду,
-- формально исполняя PR-01 и отменяя его по существу.
-- -------------------------------------------------------------------------------------

ALTER TABLE message
    ADD COLUMN next_attempt_at        timestamptz,
    ADD COLUMN dispatch_claimed_until timestamptz,
    ADD COLUMN dispatch_owner         varchar(64);

COMMENT ON COLUMN message.next_attempt_at IS
    'Не раньше этого момента сообщение снова берётся диспетчером; NULL — можно сейчас (PR-01).';
COMMENT ON COLUMN message.dispatch_claimed_until IS
    'До какого момента строка арендована инстансом. Аренда, а не только блокировка: умерший под не держит сообщение вечно.';
COMMENT ON COLUMN message.dispatch_owner IS
    'Кто арендовал — для дежурной смены; корректность держит аренда, а не это поле.';

-- Индекс под захват диспетчера. message_pending_idx (status, accepted_at) для него не
-- годится: отбор начинается с traffic_class, у каждого класса свой диспетчер (TC-01).
--
-- На живом кластере CREATE INDEX берёт ACCESS EXCLUSIVE на каждую секцию — боевой рецепт
-- (ON ONLY + CONCURRENTLY по секциям + ATTACH) описан в docs/RUNBOOK.md и выполняется
-- вне Flyway.
CREATE INDEX message_dispatch_idx
    ON message (traffic_class, next_attempt_at NULLS FIRST, accepted_at)
    WHERE status IN ('ACCEPTED', 'VALIDATED', 'ROUTED', 'QUEUED', 'RETRYING');
