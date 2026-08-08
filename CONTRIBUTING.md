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
  в том числе запрет `var` и field injection. Оба выполняются в `./gradlew build` и в CI.

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
- Прямой push в `main` запрещён: только PR с зелёным CI и минимум одним апрувом.
  Изменения в шаблонах, маршрутизации и безопасности — по правилу maker/checker (FR-4.2, SEC-03).
- Перед PR: `./gradlew build` локально, обновить `docs/IMPLEMENTATION-PLAN.md` (отметить ✅).
- Merge — squash; история `main` линейная.

## CI

`.github/workflows/ci.yml`:

1. `build` — компиляция, Spotless, Checkstyle, unit-тесты, ArchUnit (AR-02/AR-03, QA-01, QA-02);
2. `integration-test` — Testcontainers (PostgreSQL, Kafka), WireMock-стабы (QA-03, DB-01);
3. `security-scan` — CodeQL (SAST) и dependency review (SEC-09); в контуре Банка сюда подключаются
   корпоративные сканеры (SonarQube / Nexus IQ).
