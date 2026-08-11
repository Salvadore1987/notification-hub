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
docker compose up -d      # PostgreSQL, Kafka (KRaft), Schema Registry, Kafka UI, Keycloak, WireMock, GreenMail
```

| Сервис | Адрес | Учётные данные |
|---|---|---|
| PostgreSQL | `localhost:5432/commhub` | `commhub` / `commhub` |
| Kafka | `localhost:9092` | без auth |
| Schema Registry | `http://localhost:8081` | — |
| Kafka UI (топики, сообщения, лаг групп) | `http://localhost:8090` | без auth |
| Keycloak (realm `commhub`) | `http://localhost:8180` | консоль `admin` / `admin`; панель `demo` / `demo` |
| WireMock (стабы Playmobile/SMS Gate/FCM/APNs) | `http://localhost:8089` | — |
| GreenMail SMTP / IMAP / UI | `3025` / `3143` / `http://localhost:8085` | без auth |

Топики Kafka создаются автоматически (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` в compose) — до
первой публикации в логе приложения будут одиночные предупреждения `UNKNOWN_TOPIC_OR_PARTITION`,
это штатно.

Kafka UI (`http://localhost:8090`) — то место, где локально видно топики §8.1: содержимое
`comm.outbound.status.v1` и `comm.outbound.dlq.v1`, ошибки разбора в `comm.inbound.parse-error.v1`,
лаг консьюмер-групп по классам трафика (TC-01) и зарегистрированные в Schema Registry субъекты.
Оттуда же можно опубликовать сообщение в `comm.inbound.*` руками — вместо REST. Инструмент только
для локального стенда; в контуре Банка доступ к брокеру даёт эксплуатация.

Keycloak поднимается в режиме `start-dev` и импортирует `docker/keycloak/commhub-realm.json` при каждом
запуске — тома у него нет намеренно: realm демонстрационный, и правка файла применяется рестартом
(`docker compose up -d --force-recreate keycloak`). Тот же файл копируется в Testcontainer интеграционных
тестов, поэтому расходиться копиям негде.

Остановка с удалением данных: `docker compose down -v`.

### 3.2. Backend

```bash
./gradlew :bootstrap:bootRun     # запускается без предварительной настройки
```

Из IDE — Spring Boot run configuration на `NotificationHubApplication`, тоже без настройки;
в репозитории лежит готовая `.run/Notification Hub (local).run.xml`, IntelliJ показывает её
в списке сам.

Обязательных значений два, и оба локально подставляет `config/application.yml`: издатель OIDC
(`spring.security.oauth2.resourceserver.jwt.issuer-uri` → локальный Keycloak; без него инстанс не стартует,
ADR-0037) и ключ шифрования контента (DB-04):
без него контекст не поднимется, тихого отката на хранение открытым текстом нет. Локально ключ
подставляет **`config/application.yml`** в корне репозитория (плюс `bootstrap/config/application.yml`,
который его импортирует — рабочий каталог у Gradle и у IDE разный). Шифрование при этом включено,
просто ключ известный и годится только для одноразовой локальной базы. В поставке этих файлов нет
(`config/` — не каталог ресурсов, runtime-стадия Dockerfile копирует только jar), поэтому образ
и запуск jar'а вне дерева исходников ключ по-прежнему требуют — проверять деплой нужно так:

```bash
export CONTENT_ENCRYPTION_KEY=$(openssl rand -base64 32)   # AES-256, 32 байта в base64
./gradlew :bootstrap:bootJar
java -jar bootstrap/build/libs/notification-hub.jar
```

Приложение слушает `:8080`, миграции Flyway применяются на старте. Проверка:

```bash
curl http://localhost:8080/actuator/health            # {"status":"UP", ...}
curl http://localhost:8080/actuator/health/readiness  # 200
curl -i http://localhost:8080/api/admin/v1/dashboard  # 401 — панель за OIDC на любом контуре
```

Админ-BFF требует токен всегда (ADR-0037), поэтому проверять его нужно с токеном демо-администратора
локального Keycloak:

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=commhub-admin \
  -d username=demo -d password=demo \
  http://localhost:8180/realms/commhub/protocol/openid-connect/token | jq -r .access_token)
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/v1/dashboard        # 200
```

Инстанс без `spring.security.oauth2.resourceserver.jwt.issuer-uri` **не стартует**: локально значение
подставляет `config/application.yml` (см. §3.1), в контуре — переменная окружения.

Ожидаемые предупреждения в логе локального запуска (и только локального): аутентификация
систем-источников выключена (SEC-01) и security Kafka-клиента не настроена. Это осознанные локальные
умолчания — см. §5.3.

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
  adapter/out/kafka/src/main/resources/schema/comm.outbound.status.v1.json \
  | curl -s -X POST -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d @- \
    http://localhost:8081/subjects/comm.outbound.status.v1-value/versions
```

### 3.4. Frontend (админ-панель)

```bash
cd web
npm install        # однократно
npm run dev        # http://localhost:5173, прокси /api → localhost:8080
```

`public/config.json` указывает на локальный Keycloak (`http://localhost:8180/realms/commhub`), вход —
`demo/demo`. Пустой `oidc.authority` открытым режимом больше не является: панель покажет «не настроена»
и внутрь не пустит (ADR-0037).

Другие роли §10.1 — тем же паролем, что и логин: `operator`, `template-manager`, `analyst`, `viewer`,
`auditor`. Ими проверяется, что видит каждая роль, без правки конфигурации.

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
| креды включённых провайдеров | переменные окружения, см. §5.2 (`PLAYMOBILE_PASSWORD`, `SMSGATE_KEY`, …) |
| `COMMHUB_ENVIRONMENT` | метка контура в метриках (`prod`, `test`, …) |

Топики (входящие `comm.inbound.*.v1`, исходящие `comm.outbound.*.v1`) заводит эксплуатация —
партиции, retention, ACL; `KAFKA_CREATE_TOPICS` остаётся `false`. Субъект схемы статусов
регистрируется в Schema Registry контура (см. §3.3), режим совместимости `BACKWARD`.

### 5.2. Секреты (SEC-04, ADR-0036)

Секреты приходят **переменными окружения**. В БД и в yaml лежат только ссылки; ссылка называет свой
источник: `env:ИМЯ` (основная схема), `prop:ключ` (свойство Spring) или без схемы — литерал из
`commhub.secrets.values`, который существует для локального стенда и тестов. Каталога секретов и
схемы `file:` больше нет. Hub сам в Vault не ходит: значение в окружение пода кладёт платформа
(K8s Secret, инжектор Vault, CSI-драйвер).

Многострочные блобы — JSON сервис-аккаунта FCM и `.p8`-ключ APNs — переносятся в base64, и ссылка на
них объявляется с модификатором: `env:base64:FCM_SERVICE_ACCOUNT`. Значение, объявленное `base64:`,
но им не являющееся, не разрешается вовсе (отказ отправки вместо отправки с искажённым ключом).

Умолчания ссылок в `application.yml` уже указывают на окружение, поэтому в контуре достаточно задать
сами переменные:

| Провайдер | Переменные |
|---|---|
| Playmobile | `PLAYMOBILE_USERNAME`, `PLAYMOBILE_PASSWORD` |
| SMS Gate | `SMSGATE_LOGIN`, `SMSGATE_KEY` |
| FCM | `FCM_SERVICE_ACCOUNT` (base64 JSON) |
| APNs | `APNS_PRIVATE_KEY` (base64 PEM) |

Там, где умолчание ссылки пустое (пустое = «без аутентификации»), задаётся и она сама, и переменная:
`SMTP_USERNAME_REF=env:SMTP_USERNAME`, `SMTP_PASSWORD_REF=env:SMTP_PASSWORD`,
`SMTP_BOUNCE_USERNAME_REF` / `SMTP_BOUNCE_PASSWORD_REF` (EM-02),
`SMTP_DKIM_KEY_REF=env:base64:SMTP_DKIM_KEY` (EM-03), `KAFKA_SASL_PASSWORD_REF`,
`KAFKA_TRUSTSTORE_PASSWORD_REF`, `KAFKA_KEYSTORE_PASSWORD_REF`.

**Ротация требует rolling restart** — переменную окружения живого процесса сменить нельзя. Простоя при
этом нет (`maxUnavailable: 0`), но и мгновенной ротация не будет; процедура — в
[RUNBOOK](RUNBOOK.md#ротация-секретов).

### 5.3. Безопасность — включить обязательно

Издатель OIDC **обязателен на любом контуре** — без него инстанс не стартует: админ-панель за SSO
всегда (SEC-02, ADR-0037). Выключателя у неё нет; выключателем контур располагает только для
систем-источников на `/api/v1`.

```bash
# Обязательно (SEC-02): издатель, против которого проверяются токены панели.
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://sso.hamkorbank.uz/realms/commhub
# SEC-01: требовать токен и от систем-источников. В контуре Банка — true.
COMMHUB_REQUIRE_SOURCE_SYSTEM_TOKEN=true
# Kafka: KAFKA_SECURITY_PROTOCOL / KAFKA_SASL_* / KAFKA_*STORE_* (SASL/SCRAM или mTLS + ACL)
```

**mTLS для систем-источников не поддерживается** — удалён вместе с открытым режимом (ADR-0037).

Группы SSO должны называться именами ролей §10.1 (`ADMIN`, `OPERATOR`, `TEMPLATE_MANAGER`, `ANALYST`,
`VIEWER`, `SECURITY_AUDITOR`) и приходить в claim `groups` **коротким именем, без пути**: значение
берётся как есть, в верхнем регистре, поэтому `/commhub-admin` превратится в роль, которой ни у кого
нет. Пример настройки — локальный `docker/keycloak/commhub-realm.json` (group-membership mapper,
`full.path=false`). Если имена групп Банка отличаются от имён ролей, отображение задаётся в
`groupRoles` файла `public/config.json` панели и в `COMMHUB_ROLES_CLAIM` на backend'е.

Секреты callback'ов провайдеров — `PLAYMOBILE_CALLBACK_SECRET_REF` / `SMSGATE_CALLBACK_SECRET_REF`
(SEC-07, форма `env:PLAYMOBILE_CALLBACK_SECRET`), плюс списки разрешённых IP, согласованные с
провайдерами.

### 5.4. Провайдеры

Каждый включается своим флагом (`PLAYMOBILE_ENABLED`, `SMSGATE_ENABLED`, `SMTP_ENABLED`,
`FCM_ENABLED`, `APNS_ENABLED`) и кредами из переменных окружения (§5.2). В yaml/окружении живёт
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
