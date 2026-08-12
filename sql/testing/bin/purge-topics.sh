#!/usr/bin/env bash
# =====================================================================================
# purge-topics.sh — очистка топиков Kafka между тест-кейсами.
#
# SQL здесь бессилен: Kafka — не таблица, и «удалить строки» в ней не бывает. Топик
# либо удаляется и создаётся заново, либо очищается сдвигом retention до нуля. Мы удаляем
# и создаём: это единственный способ гарантировать, что offset 0 — это первая запись
# текущего кейса, а не хвост предыдущего.
#
# Модуль создаёт исходящие топики сам, когда включено commhub.kafka.outbound.create-topics
# (на локальном стенде включено); входящие тоже создаются автоматически при первой записи,
# если брокер это разрешает. Поэтому после удаления скрипт их пересоздаёт явно — чтобы
# консьюмеры Модуля не ждали первой публикации.
#
# ВАЖНО: перед очисткой Модуль должен быть остановлен или его консьюмеры отписаны.
# Удаление топика под работающим консьюмером даёт UNKNOWN_TOPIC_OR_PARTITION в логах
# и переподписку с непредсказуемой позицией — это не «шум», это испорченный кейс.
#
# Использование:
#   ./purge-topics.sh                 # локальный брокер (docker compose), все топики набора
#   BOOTSTRAP=host:9092 ./purge-topics.sh
#   ./purge-topics.sh --list          # показать, что будет удалено, ничего не делая
# =====================================================================================
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"
PARTITIONS="${PARTITIONS:-12}"
REPLICATION="${REPLICATION:-1}"

# Топики набора — те же значения, что в KafkaInboundProperties и KafkaOutboundProperties
# по умолчанию. Контур с переопределёнными именами задаёт их через TOPICS.
DEFAULT_TOPICS=(
    comm.inbound.critical.v1
    comm.inbound.transactional.v1
    comm.inbound.notification.v1
    comm.inbound.batch-control.v1
    comm.inbound.parse-error.v1
    comm.outbound.status.v1
    comm.outbound.dlq.v1
    comm.outbound.push-token.invalidated.v1
    comm.outbound.events.v1
)
read -r -a TOPIC_LIST <<<"${TOPICS:-${DEFAULT_TOPICS[*]}}"

# kafka-topics.sh внутри контейнера compose, если он не установлен локально.
if command -v kafka-topics.sh >/dev/null 2>&1; then
    kafka_topics() { kafka-topics.sh --bootstrap-server "$BOOTSTRAP" "$@"; }
elif docker compose ps --status running --services 2>/dev/null | grep -qx kafka; then
    kafka_topics() {
        docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9092 "$@"
    }
else
    echo "Не найден ни kafka-topics.sh в PATH, ни запущенный сервис kafka в docker compose." >&2
    echo "Поднимите стенд: docker compose up -d kafka" >&2
    exit 1
fi

if [[ "${1:-}" == "--list" ]]; then
    printf 'Будут удалены и созданы заново (%s):\n' "$BOOTSTRAP"
    printf '  %s\n' "${TOPIC_LIST[@]}"
    exit 0
fi

echo "Очистка топиков на $BOOTSTRAP"

existing="$(kafka_topics --list 2>/dev/null || true)"

for topic in "${TOPIC_LIST[@]}"; do
    if grep -qx "$topic" <<<"$existing"; then
        kafka_topics --delete --topic "$topic" >/dev/null
        echo "  удалён   $topic"
    fi
done

# Удаление асинхронно: брокер снимает топик после того, как отработают реплики.
# Пересоздание сразу же иногда получает TOPIC_ALREADY_EXISTS, поэтому ждём исчезновения.
for _ in $(seq 1 30); do
    existing="$(kafka_topics --list 2>/dev/null || true)"
    still_there=0
    for topic in "${TOPIC_LIST[@]}"; do
        grep -qx "$topic" <<<"$existing" && still_there=1
    done
    [[ $still_there -eq 0 ]] && break
    sleep 1
done

for topic in "${TOPIC_LIST[@]}"; do
    kafka_topics --create --topic "$topic" \
        --partitions "$PARTITIONS" --replication-factor "$REPLICATION" \
        --if-not-exists >/dev/null
    echo "  создан   $topic"
done

echo "Готово: ${#TOPIC_LIST[@]} топиков."
