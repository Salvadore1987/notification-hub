#!/usr/bin/env bash
# =====================================================================================
# run-case.sh — полный цикл одного тест-кейса: очистка → контур → предусловия →
# [действие кейса] → проверки → очистка.
#
# Действие кейса скрипт не выполняет и не может: оно приходит снаружи — REST-запрос из
# http/, документ из kafka/ или экран панели. Скрипт готовит контур, останавливается и
# ждёт, пока действие выполнят, затем показывает проверочные запросы.
#
# Использование:
#   ./run-case.sh IT-FLT-006                 # интерактивно: пауза перед проверками
#   ./run-case.sh IT-FLT-006 --no-teardown   # оставить состояние для разбора
#   ./run-case.sh IT-FLT-006 --arrange-only  # только очистка + контур + предусловия
#   ./run-case.sh IT-FLT-006 --assert-only   # только проверки (после ручного действия)
#   ./run-case.sh --list                     # все известные идентификаторы кейсов
#
# Переменные окружения:
#   COMMHUB_DB   строка подключения psql (по умолчанию — локальный compose)
#   BOOTSTRAP    брокер Kafka (по умолчанию localhost:9092)
#   SKIP_TOPICS  =1, чтобы не трогать Kafka (кейс только про БД и REST)
# =====================================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
CASES_DIR="$ROOT/cases"

COMMHUB_DB="${COMMHUB_DB:-postgresql://commhub:commhub@localhost:5432/commhub}"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

# psql либо локальный, либо из контейнера compose — на машине разработчика клиента
# PostgreSQL может не быть вовсе, а контейнер и так поднят.
if command -v psql >/dev/null 2>&1; then
    psql_run() { psql "$COMMHUB_DB" -v ON_ERROR_STOP=1 --quiet "$@"; }
elif docker compose ps --status running --services 2>/dev/null | grep -qx postgres; then
    PGDB="${PGDB:-commhub}"
    PGUSER_="${PGUSER_:-commhub}"
    psql_run() {
        docker compose exec -T postgres psql -U "$PGUSER_" -d "$PGDB" -v ON_ERROR_STOP=1 --quiet "$@"
    }
else
    die "не найден ни psql в PATH, ни запущенный сервис postgres в docker compose.
Поднимите стенд: docker compose up -d postgres"
fi

list_cases() {
    grep -ho '^-- >>> IT-[A-Z]*-[0-9]*' "$CASES_DIR"/*.sql | sed 's/^-- >>> //' | sort
}

if [[ "${1:-}" == "--list" ]]; then
    list_cases
    exit 0
fi

CASE_ID="${1:-}"
[[ -n "$CASE_ID" ]] || die "не указан идентификатор кейса. Список: $0 --list"
shift || true

MODE=full
TEARDOWN=1
for arg in "$@"; do
    case "$arg" in
        --arrange-only) MODE=arrange ;;
        --assert-only)  MODE=assert ;;
        --no-teardown)  TEARDOWN=0 ;;
        *) die "неизвестный ключ: $arg" ;;
    esac
done

# Файл области выводится из идентификатора: IT-FLT-006 → cases/*-flt.sql.
AREA="$(cut -d- -f2 <<<"$CASE_ID" | tr '[:upper:]' '[:lower:]')"
CASE_FILE="$(ls "$CASES_DIR"/*-"$AREA".sql 2>/dev/null | head -1)"
[[ -n "$CASE_FILE" ]] || die "не найден файл области для $CASE_ID (искал $CASES_DIR/*-$AREA.sql)"
grep -q "^-- >>> $CASE_ID\b" "$CASE_FILE" || die "$CASE_ID нет в $CASE_FILE"

# Извлекает секцию (@arrange или @assert) блока кейса. Блок начинается с «-- >>> ID»
# и заканчивается следующим «-- >>>» или концом файла.
extract() {
    local section="$1"
    awk -v id="$CASE_ID" -v want="-- @$section" '
        $0 ~ "^-- >>> " id "([^0-9]|$)" { inblock = 1; next }
        inblock && /^-- >>> / { exit }
        inblock && /^-- @/ { insec = ($0 ~ "^" want) ? 1 : 0; next }
        inblock && insec { print }
    ' "$CASE_FILE"
}

run_sql_text() {
    local label="$1" sql="$2"
    if [[ -z "${sql//[[:space:]]/}" ]]; then
        echo "  ($label: пусто — кейсу хватает эталонного контура)"
        return
    fi
    # Блок извлекается без шапки файла, а search_path задан именно там: без этой
    # строки любой запрос кейса отвечает «relation … does not exist».
    printf 'SET search_path TO comm_hub, public;\n%s\n' "$sql" | psql_run -f -
}

banner() { printf '\n\033[1m%s\033[0m\n' "$*"; }

if [[ "$MODE" != "assert" ]]; then
    banner "[1/5] Очистка состояния — $CASE_ID"
    psql_run -f - < "$ROOT/00-reset.sql"

    if [[ "${SKIP_TOPICS:-0}" != "1" ]]; then
        banner "[2/5] Очистка топиков Kafka"
        "$HERE/purge-topics.sh"
    else
        banner "[2/5] Топики пропущены (SKIP_TOPICS=1)"
    fi

    banner "[3/5] Эталонный контур"
    psql_run -f - < "$ROOT/01-contour.sql"

    banner "[4/5] Предусловия кейса $CASE_ID"
    run_sql_text "предусловия" "$(extract arrange)"
fi

if [[ "$MODE" == "arrange" ]]; then
    banner "Контур готов. Выполните действие кейса, затем: $0 $CASE_ID --assert-only"
    exit 0
fi

if [[ "$MODE" == "full" ]]; then
    banner "Выполните действие кейса $CASE_ID (http/, kafka/ или панель) и нажмите Enter"
    read -r
fi

banner "[5/5] Проверки кейса $CASE_ID"
run_sql_text "проверки" "$(extract assert)"

if [[ "$MODE" != "assert" && "$TEARDOWN" -eq 1 ]]; then
    banner "Очистка после прогона"
    psql_run -f - < "$ROOT/99-teardown.sql"
elif [[ "$TEARDOWN" -eq 0 ]]; then
    banner "Состояние оставлено (--no-teardown). Убрать: psql -f $ROOT/99-teardown.sql"
fi
