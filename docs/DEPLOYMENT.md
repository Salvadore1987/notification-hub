# Развёртывание и запуск Notification Hub

Инструкция покрывает два сценария: локальный запуск для разработки и проверки, и развёртывание
в контуре Банка (K8s/OpenShift). Операционные процедуры после запуска — в `docs/RUNBOOK.md`;
правила разработки и ветвления — в `CONTRIBUTING.md`.

> **Важно (состояние на 2026-08-09).** Открыты два долга Phase 15, оба описаны в
> `docs/IMPLEMENTATION-PLAN.md`: (1) ничего не вызывает `DispatchMessage` — в
> `adapter/in/scheduler` нет диспетчера, поэтому развёрнутый Hub **принимает сообщения, но не
> отправляет их** (то же для `ExpireMessages`, FR-3.4); (2) `Batch.registerSent/registerDelivered/
> registerFailed` никто не вызывает — карточка батча показывает нули во всех счётчиках, кроме
> `processed`. До закрытия этих долгов развёртывание пригодно для приёма, конфигурирования,
> админ-панели и интеграционных проверок, но не для сквозной доставки.

## 1. Состав поставки

| Артефакт | Что это | Как собирается |
|---|---|---|
| `bootstrap/build/libs/notification-hub.jar` | исполняемый Spring Boot jar (backend целиком, включая Flyway-миграции и admin BFF) | `./gradlew :bootstrap:bootJar` |
| Docker-образ | jar в two-stage образе (Temurin 25 JRE, non-root uid 10001) | `docker build` от корня репозитория |
| `web/dist/` | статика админ-панели (SPA) | `cd web && npm run build` |
| `deploy/k8s/deployment.yaml` | манифест-образец: пробы, graceful shutdown, секреты каталогом | правится под контур |
| `deploy/observability/` | правила алертов Prometheus (OBS-04) и дашборды Grafana (OBS-05) | подключаются в мониторинг контура |

Схема БД создаётся и версионируется самим приложением (Flyway, схема `comm_hub`) — отдельного
SQL-пакета в поставке нет.

## 2. Требования к окружению

| Инструмент | Версия | Примечание |
|---|---|---|
| JDK | 25 (LTS) | toolchain объявлен в сборке; при отсутствии Gradle скачает JDK сам (foojay) — локально может стоять и более новый |
| Gradle | не нужен | wrapper `./gradlew` (9.7.0) |
| Docker | актуальный | локальный стек (`docker compose`) и интеграционные тесты (Testcontainers) |
| Node.js + npm | Node 20+ | только для `web/` (админ-панель) |
| PostgreSQL | 16+ | в контуре — кластер Банка; локально — из compose |
| Kafka | — | в контуре — кластер Банка; локально — из compose (KRaft) |

## 3. Локальный запуск

### 3.1. Инфраструктура

```bash
docker compose up -d      # PostgreSQL, Kafka (KRaft), Schema Registry, WireMock, GreenMail
```

| Сервис | Адрес | Учётные данные |
|---|---|---|
| PostgreSQL | `localhost:5432/commhub` | `commhub` / `commhub` |
| Kafka | `localhost:9092` | без auth |
| Schema Registry | `http://localhost:8081` | — |
| WireMock (стабы Playmobile/SMS Gate/FCM/APNs) | `http://localhost:8089` | — |
| GreenMail SMTP / IMAP / UI | `3025` / `3143` / `http://localhost:8085` | без auth |

Топики Kafka создаются автоматически (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` в compose) — до
первой публикации в логе приложения будут одиночные предупреждения `UNKNOWN_TOPIC_OR_PARTITION`,
это штатно.

Остановка с удалением данных: `docker compose down -v`.

### 3.2. Backend

Единственная обязательная переменная без значения по умолчанию — ключ шифрования контента (DB-04).
Без него контекст не поднимется; тихого отката на хранение открытым текстом нет — это намеренно.

```bash
export CONTENT_ENCRYPTION_KEY=$(openssl rand -base64 32)   # AES-256, 32 байта в base64

./gradlew :bootstrap:bootRun
# либо из jar:
./gradlew :bootstrap:bootJar
java -jar bootstrap/build/libs/notification-hub.jar
```

Приложение слушает `:8080`, миграции Flyway применяются на старте. Проверка:

```bash
curl http://localhost:8080/actuator/health            # {"status":"UP", ...}
curl http://localhost:8080/actuator/health/readiness  # 200
curl http://localhost:8080/api/admin/v1/dashboard     # 200 (админ-BFF в open mode)
```

Ожидаемые предупреждения в логе локального запуска (и только локального): аутентификация
систем-источников выключена (SEC-01), авторизация admin BFF неактивна (SEC-03), security
Kafka-клиента не настроена. Это осознанные локальные умолчания — см. §5.3.

Провайдеры по умолчанию выключены (`PLAYMOBILE_ENABLED=false` и т.д.). Для проверки отправки
против WireMock-стабов включайте нужный и указывайте кредам-ссылкам схему `env:`, например:

```bash
export PLAYMOBILE_ENABLED=true
export PLAYMOBILE_USERNAME_REF=env:PLAYMOBILE_STUB_USER
export PLAYMOBILE_PASSWORD_REF=env:PLAYMOBILE_STUB_PASSWORD
export PLAYMOBILE_STUB_USER=stub PLAYMOBILE_STUB_PASSWORD=stub
```

(база URL уже смотрит в WireMock: `http://localhost:8089/broker-api`).

### 3.3. Схема исходящих статусов в Schema Registry (NF-08, опционально)

Приложение в реестр не ходит — субъект регистрируется отдельно:

```bash
jq -Rs '{schemaType:"JSON", schema:.}' \
  adapter/src/main/resources/schema/comm.outbound.status.v1.json \
  | curl -s -X POST -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d @- \
    http://localhost:8081/subjects/comm.outbound.status.v1-value/versions
```

### 3.4. Frontend (админ-панель)

```bash
cd web
npm install        # однократно
npm run dev        # http://localhost:5173, прокси /api → localhost:8080
```

`public/config.json` с пустым `oidc.authority` — это open mode: панель доступна со всеми ролями
и предупреждающей плашкой, та же позиция, что у backend'а без OIDC-издателя.

### 3.5. Проверки перед PR

```bash
./gradlew build            # компиляция + Spotless + Checkstyle + unit-тесты + ArchUnit
./gradlew integrationTest  # Testcontainers + WireMock + GreenMail (нужен Docker)
cd web && npm test         # Vitest (unit/component)
cd web && npm run test:e2e # Playwright; однократно перед этим: npx playwright install chromium
```

## 4. Сборка артефактов поставки

```bash
# Backend jar
./gradlew :bootstrap:bootJar        # -> bootstrap/build/libs/notification-hub.jar

# Docker-образ (тесты в образе не гоняются — их гоняет пайплайн, SEC-09)
docker build -t registry.hamkorbank.uz/commhub/notification-hub:<версия> .
# базовые образы выведены в ARG — в контуре подменяются на согласованные:
#   --build-arg BUILD_IMAGE=... --build-arg RUNTIME_IMAGE=...

# Frontend
cd web && npm ci && npm run build   # -> web/dist/
```

`web/dist/` раздаётся любым статическим сервером/ingress'ом контура; перед выкладкой в контур
подменяется `config.json` (issuer и clientId OIDC, `rolesClaim`, карта SSO-групп → роли §10.1).
`apiBaseUrl` должен указывать на `/api/admin/v1` того же origin'а либо ingress должен
маршрутизировать `/api/admin/v1` на backend.

## 5. Развёртывание в контуре (K8s/OpenShift)

Основа — `deploy/k8s/deployment.yaml`. Имена namespace, образа, секретов и ingress'а в контуре
свои; неизменными при переносе должны остаться состав проб, порядок остановки и источники секретов.

### 5.1. Что обязательно задать

| Переменная | Назначение |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL (`currentSchema=comm_hub`) |
| `CONTENT_ENCRYPTION_KEY` | ключ шифрования контента; из секрет-хранилища платформы, отсутствие — отказ старта |
| `KAFKA_BOOTSTRAP_SERVERS` | кластер Kafka Банка |
| `SECRETS_DIR` | каталог секретов, примонтированный в под (`/etc/commhub/secrets`) |
| `COMMHUB_ENVIRONMENT` | метка контура в метриках (`prod`, `test`, …) |

Топики (входящие `comm.inbound.*.v1`, исходящие `comm.outbound.*.v1`) заводит эксплуатация —
партиции, retention, ACL; `KAFKA_CREATE_TOPICS` остаётся `false`. Субъект схемы статусов
регистрируется в Schema Registry контура (см. §3.3), режим совместимости `BACKWARD`.

### 5.2. Секреты (SEC-04)

Секреты приходят **каталогом файлов** (Vault-agent рендерит в примонтированный том), приложение
читает их по ссылкам вида `playmobile/password` относительно `SECRETS_DIR`; TTL-кэш (30с) даёт
ротацию без рестарта. Поддерживаются также схемы `env:`/`file:`/`prop:`. Hub сам в Vault не ходит.

### 5.3. Безопасность — включить обязательно

Оба механизма аутентификации **выключены по умолчанию** (осознанно: локально нет ни издателя,
ни CA), и инстанс без единого включённого пишет предупреждение на старте. В контуре включается
то, что предписывает стандарт Банка:

```bash
# OAuth2 для систем-источников и admin BFF (SEC-01/SEC-02/SEC-03):
COMMHUB_OAUTH2_ENABLED=true
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://sso.hamkorbank.uz/realms/commhub
# и/или mTLS: COMMHUB_MTLS_ENABLED=true (CN сертификата = streamId по соглашению)
# Kafka: KAFKA_SECURITY_PROTOCOL / KAFKA_SASL_* / KAFKA_*STORE_* (SASL/SCRAM или mTLS + ACL)
```

Секреты callback'ов провайдеров — `PLAYMOBILE_CALLBACK_SECRET_REF` / `SMSGATE_CALLBACK_SECRET_REF`
(SEC-07), плюс списки разрешённых IP, согласованные с провайдерами.

### 5.4. Провайдеры

Каждый включается своим флагом (`PLAYMOBILE_ENABLED`, `SMSGATE_ENABLED`, `SMTP_ENABLED`,
`FCM_ENABLED`, `APNS_ENABLED`) и кредами-ссылками в каталог секретов. В yaml/окружении живёт
только топология деплоя (base-url, таймауты, окна breaker'а); лимиты и настройки отправки
(originator/sender, приоритеты, веса, TTL) читаются из БД (`provider.rate_limit_config`,
`provider.endpoint_config`) и правятся из админ-панели без рестарта (AD-07, NF-07).

После первого старта конфигурация маршрутизации пуста — потоки, каналы, провайдеры и политики
маршрутизации заводятся через админ-панель (§11.2) до подачи трафика.

### 5.5. Пробы и остановка (NF-05)

Три пробы — три группы actuator'а, состав у них разный и это принципиально:

- `startup` → `/actuator/health/startup` (только БД; даёт время миграциям Flyway);
- `liveness` → `/actuator/health/liveness` (ничего внешнего: рестарт пода из-за упавшей БД —
  это crash loop во время аварии);
- `readiness` → `/actuator/health/readiness` (плюс БД; брокер и провайдеры намеренно снаружи —
  для того и outbox).

`terminationGracePeriodSeconds: 60` ≥ `spring.lifecycle.timeout-per-shutdown-phase` (30s) + запас:
relay outbox'а и курсор выгрузки дорабатывают текущий проход (AD-03, FR-6.4).

Масштабирование горизонтальное без остановки: инстансы stateless, outbox разбирается через
`FOR UPDATE SKIP LOCKED`, consumer group переразбивает партиции сама.

### 5.6. Наблюдаемость

- Метрики: `/actuator/prometheus`; имена и метки — контракт в `MetricNames`, по ним написаны
  `deploy/observability/prometheus-alerts.yaml` (алерты ссылаются якорями в `docs/RUNBOOK.md`)
  и дашборды `deploy/observability/grafana/*.json` — подключить в мониторинг контура.
- Логи: `COMMHUB_LOG_FORMAT=ecs` (в Docker-образе уже установлено) — JSON для ELK/Loki.
- Трассировка: выключена по умолчанию; `COMMHUB_TRACING_SAMPLING` + `OTEL_EXPORTER_OTLP_ENDPOINT`.

## 6. Проверка после развёртывания

1. Под прошёл `startup` и `readiness`; в логе нет предупреждений о выключенной security.
2. Смок приёма: `POST /api/v1/messages` с тестовым сообщением (`test=true`, FR-7.4) от имени
   потока, заведённого в конфигурации, — ответ `202` с `messageId`.
3. Тестовая отправка из админ-панели по каждому включённому каналу.
4. Статус ушёл в `comm.outbound.status.v1` (следить за возрастом старейшей неопубликованной
   строки outbox — метрика backlog'а).
5. Дашборды Grafana видят инстанс; алерты Prometheus загружены.

Дальше — `docs/RUNBOOK.md` (по одному разделу на симптом, OBS-06).
