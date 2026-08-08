# Разработка Notification Hub

## Требования к окружению

| Инструмент | Версия | Примечание |
|---|---|---|
| JDK | **25 (LTS)** | Toolchain задан в `build.gradle.kts`; при отсутствии локального JDK 25 Gradle скачает его сам (foojay-resolver) |
| Gradle | не нужен | используется wrapper: `./gradlew` (9.7.0) |
| Docker | любой актуальный | нужен для `docker compose` и интеграционных тестов (Testcontainers) |

## Сборка и проверки

```bash
./gradlew build            # компиляция + spotlessCheck + checkstyle + unit-тесты + ArchUnit
./gradlew spotlessApply    # автоформатирование (palantir-java-format)
./gradlew integrationTest  # интеграционные тесты (Testcontainers, требуется Docker)
./gradlew :bootstrap:bootRun
```

Тесты с тегом `integration` исключены из задачи `test` и запускаются только через `integrationTest`.
Полный контекст приложения поднимают `ApplicationContextIT` и `ProviderAdaptersContextIT` (модуль
`bootstrap`) — если сборка падает на них, сломана не логика, а wiring или конфигурация.

Нагрузочные сценарии (QA-05) живут в `load/k6` и Gradle'ом не запускаются: им нужен контур, а не
ноутбук. Предусловия и что прикладывать к приёмке — в `load/README.md`.

## Локальное окружение

```bash
docker compose up -d      # PostgreSQL, Kafka (KRaft), Schema Registry, WireMock, GreenMail
docker compose down -v    # останов с удалением данных
```

| Сервис | Адрес | Учётные данные |
|---|---|---|
| PostgreSQL | `localhost:5432/commhub` | `commhub` / `commhub` |
| Kafka | `localhost:9092` | без auth (локально) |
| Schema Registry | `http://localhost:8081` | — |
| WireMock (стабы провайдеров) | `http://localhost:8089` | — |
| GreenMail SMTP / IMAP / UI | `3025` / `3143` / `http://localhost:8085` | без auth |

Приложение читает настройки из `bootstrap/src/main/resources/application.yml`; значения по умолчанию
совпадают с `docker-compose.yml` и переопределяются переменными окружения (`DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, …).

### Схема исходящих статусов в Schema Registry (NF-08)

Приложение публикует статусы как JSON и в реестр само не ходит — иначе реестр окажется на пути
отправки каждого статуса. Субъект регистрируется отдельно (в контуре Банка — эксплуатацией):

```bash
jq -Rs '{schemaType:"JSON", schema:.}' \
  adapter/src/main/resources/schema/comm.outbound.status.v1.json \
  | curl -s -X POST -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d @- \
    http://localhost:8081/subjects/comm.outbound.status.v1-value/versions
```

Режим совместимости субъекта — `BACKWARD` (в локальном `docker-compose.yml` это дефолт кластера):
поля можно добавлять (nullable/с default), переименовывать и удалять — нельзя. Тот же формат
и та же схема у `comm.outbound.dlq.v1`.

### Ключ шифрования контента

Одна переменная обязательна и значения по умолчанию не имеет — ключ шифрования контента (DB-04):

```bash
export CONTENT_ENCRYPTION_KEY=$(openssl rand -base64 32)   # AES-256, 32 байта в base64
```

Без него контекст не поднимется: тихого отката на хранение контента открытым текстом нет.
В контуре Банка ключ приходит из секрет-хранилища платформы, в репозитории его быть не должно.
Ротация: добавить новый ключ в `commhub.persistence.encryption.keys`, перевести на него
`active-key-id`, старый держать, пока живы секции со строками под ним (DB-03).

## Структура модулей (AR-01)

```
domain/       — чистая Java: модель и доменные сервисы. Без Spring/JPA/Kafka/Jackson (AR-02)
application/  — port/in (use cases), port/out (репозитории, провайдеры, паблишеры), оркестрация, saga
adapter/      — in: rest, kafka, admin, callback; out: persistence, kafka, provider/*, notification
                (здесь же Flyway-миграции: adapter/src/main/resources/db/migration)
bootstrap/    — Spring Boot приложение, конфигурация, wiring, ArchUnit- и интеграционные тесты
```

Направление зависимостей — только внутрь: `adapter → application → domain` (AR-03).

## Правила кода

- Без `var`; только constructor injection; UUIDv7 для первичных ключей; тесты по AAA.
- Четыре слоя приложения разделены: use case (оркестрация) · `XxxCommand`/`XxxQuery` records ·
  DTO-records в `dto/` · **только MapStruct-мапперы** в `mapper/` (`@Mapper(componentModel = "spring")`).
- `@RestControllerAdvice` — в отдельном пакете `handlers/`, по одному классу на предмет.
- Документация проекта — на русском; код, идентификаторы, логи и контракты API — на английском.
- Форматирование обеспечивает Spotless, смысловые правила — Checkstyle (`config/checkstyle/checkstyle.xml`),
  в том числе запрет `var` и field injection. Оба выполняются в `./gradlew build`.

## Ветвление и PR

Trunk-based с короткоживущими ветками от `main`:

| Префикс | Назначение |
|---|---|
| `feature/<phase>-<кратко>` | новая функциональность (например `feature/phase2-message-aggregate`) |
| `fix/<кратко>` | исправление дефекта |
| `chore/<кратко>` | инфраструктура, сборка, документация |
| `release/<x.y>` | стабилизация релиза, только cherry-pick фиксов |

- Коммиты — в стиле Conventional Commits: `feat(domain): add SegmentCalculator (MP-06)`.
  В теле или заголовке указывается ID требования SRS.
- Прямой push в `main` запрещён: только PR с локально зелёной сборкой и минимум одним апрувом.
  Изменения в шаблонах, маршрутизации и безопасности — по правилу maker/checker (FR-4.2, SEC-03).
- Перед PR: `./gradlew build` локально, обновить `docs/IMPLEMENTATION-PLAN.md` (отметить ✅).
- Merge — squash; история `main` линейная.

## Проверки перед PR

Пайплайна в репозитории нет — сборка и проверки запускаются локально, а в контуре Банка
подключаются к корпоративному конвейеру (Jenkins/GitLab CI, SonarQube, Nexus IQ, SEC-09):

1. `./gradlew build` — компиляция, Spotless, Checkstyle, unit-тесты, ArchUnit (AR-02/AR-03, QA-01, QA-02);
2. `./gradlew integrationTest` — Testcontainers (PostgreSQL, Kafka), WireMock-стабы и GreenMail: полный
   контекст, приёмочные сценарии QA-08 и chaos QA-06 (`docker pause` настоящих контейнеров) (QA-03, DB-01);
3. `k6 run load/k6/...` на тестовом контуре — NF-01 и TC-01 (QA-05), отчёт прикладывается к приёмке.
