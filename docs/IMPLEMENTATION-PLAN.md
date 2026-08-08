# План реализации — Notification Hub

Источник требований: [`sms-notification-hub-spec.md`](./sms-notification-hub-spec.md).
Порядок: **сначала полностью backend, затем frontend**. Внутри backend — по слоям гексагональной
архитектуры и этапам SRS §16 (ядро+SMS → Email → Push → расширения).

**Легенда:** `[ ]` — не начато, `[~]` — в работе, `✅` — завершено (по правилу проекта отмечаем зелёной галочкой).
Пункты ссылаются на ID требований SRS (FR-*, AR-*, AD-*, PU-* …).

---

## ЧАСТЬ A. BACKEND

### A0. Каркас проекта и инфраструктура разработки

- [ ] Инициализировать Gradle multi-module (`domain`, `application`, `adapter`, `bootstrap`) — AR-01
- [ ] Настроить Java 25 toolchain, Spring Boot 4.x BOM, включить preview/Loom при необходимости
- [ ] Подключить MapStruct (`mapstruct` + `mapstruct-processor`) во все модули с мапперами — правило проекта
- [ ] Настроить форматирование/линт (Spotless), Checkstyle, запрет `var` (правило проекта)
- [ ] `docker-compose` для локали: PostgreSQL 16, Kafka + Schema Registry, WireMock, MailHog/GreenMail
- [ ] Базовый CI-пайплайн: build + unit + ArchUnit + integration (Testcontainers) + SAST/dependency-scan (SEC-09)
- [ ] Настроить Flyway, каталог миграций, воспроизведение схемы с нуля (DB-01)
- [ ] Git-репозиторий, ветвление, PR-шаблон

### A1. Домен (`domain/`) — чистая Java, без Spring/JPA/Kafka/Jackson (AR-02)

- [ ] Value objects и идентификаторы: `MessageId` (UUIDv7), `ExternalMessageId`, `StreamId`, `BatchId`, `DedupKey`, `CorrelationId`, `Recipient`, `EmailAddress`, `Msisdn`
- [ ] Enum'ы: `TrafficClass`, `Priority`, `Channel`, `MessageStatus` (§6.3), `TemplateStatus`, `BatchStatus`
- [ ] Sealed `MessageContent`: `SmsContent`, `EmailContent`, `PushContent` (§5.2, MP-02)
- [ ] Агрегат `Message` (envelope + content + channelPlan + status) — §5.2, §6.1
- [ ] `ChannelPlan` с режимами: явный канал / выбор Модулем / fallback-цепочка (MP-03)
- [ ] Агрегаты: `Batch`, `Stream`, `Channel`, `Provider`, `RoutingPolicy`, `Template`+`TemplateVersion`, `SuppressionEntry`, `DeliveryAttempt`, `DlqEntry` (§6.1)
- [ ] Статусная машина `Message` с валидацией переходов и терминальными статусами (ST-01…ST-03)
- [ ] Доменный сервис `SegmentCalculator` (GSM-7 160/153, UCS-2 70/67, escape-символы) — MP-06, §18.3
- [ ] Доменный сервис `Router` (выбор канала/провайдера, балансировка round-robin/вес/least-cost) — MP-05, FR-2.3
- [ ] Доменный сервис `FallbackChain` (порядок резерва) — FR-2.2
- [ ] Unit-тесты домена ≥80% строк, ≥90% критической логики (QA-01, AAA-паттерн)

### A2. Порты приложения (`application/port`) и use cases

- [ ] Input-порты (интерфейсы use case) с Command/Query records (AR-06): `SubmitMessage`, `SubmitBatch`, `PauseBatch`/`ResumeBatch`/`StopBatch`, `ResendDlq`, `ProcessProviderStatus`, `KillSwitch`
- [ ] Output-порты: `MessageRepository`, `BatchRepository`, `StreamRepository`, `ProviderConfigRepository`, `TemplateRepository`, `SuppressionRepository`, `DedupRegistryPort`, `OutboxPort`, `StatusPublisherPort`, `SmsProviderPort`, `EmailProviderPort`, `PushProviderPort`, `ClockPort`, `MetricsPort`, `AuditPort`, `SecretResolverPort`
- [ ] Задел-порт `AudienceResolverPort` без реализации (FR-8.11)
- [ ] Задел-порт `CustomerPreferencePort` — заглушка (FR-8.2)
- [ ] Use case `SubmitMessage`: валидация → дедуп → выбор класса трафика → шаблон → сегментация → маршрут → сохранение+outbox (FR-1.1, FR-1.4, FR-1.5)
- [ ] Use case `SubmitBatch` + загрузка элементов чанками, прогресс (FR-1.6)
- [ ] Оркестрация отправки (saga): resolve адаптера по `ProviderRef`, submit, обработка ack, retry/fallback (AD-04)
- [ ] Идемпотентность по `(streamId, externalMessageId)`/`dedupKey`, окно по умолчанию 24ч → статус `DUPLICATE` (FR-1.5)
- [ ] Фильтры доставки: Suppression, Quiet hours, frequency capping (FR-5.1…FR-5.4)
- [ ] Применение шаблона: merge-поля, строгий режим, только `PUBLISHED` (FR-4.1, FR-4.3)
- [ ] `ProcessProviderStatus`: маппинг провайдерских статусов → канонические, запись истории (AD-06, ST-01)
- [ ] TTL/`EXPIRED` авто-отмена (FR-3.4)
- [ ] Управление рассылками: пауза/возобновление/стоп батча/потока/kill switch (FR-3.2), не затрагивает `CRITICAL_OTP`
- [ ] DTO (records) в `dto/` + MapStruct-мапперы в `mapper/` для конвертаций (правило проекта)
- [ ] Unit-тесты use cases (моки портов), тесты идемпотентности и статусной машины

### A3. Персистентность (`adapter/out/persistence`) — PostgreSQL

- [ ] Flyway-миграции таблиц (§10.1): `stream`, `channel`, `provider`, `routing_policy`, `template`, `template_version`, `batch`, `message`, `message_status_history`, `delivery_attempt`, `outbox_event`, `dlq_entry`, `suppression_list`, `dedup_registry`, `quota_counter`, `audit_log`, `app_user`/`app_role`/`user_role`
- [ ] Партиционирование по времени `message`, `message_status_history`, `delivery_attempt`, `outbox_event` + авто-создание/отсоединение партиций (DB-02)
- [ ] Индексы: `(stream_id, accepted_at)`, `(external_id, stream_id)`, `(batch_id)`, `(dedup_key)`, `(correlation_id)`, частичные по нетерминальным статусам (DB-05)
- [ ] Реализация репозиториев (Spring Data JDBC/JPA) под output-порты
- [ ] Шифрование/хеширование PII (`address_hash` в suppression; контент — pgcrypto/app-level по согласованию с ИБ) (DB-04)
- [ ] Read-only реплика для аналитики (DB-06) — конфигурация datasource
- [ ] Retention/архивация (конфигурируемый срок ≥12 мес) (DB-03)
- [ ] Интеграционные тесты с Testcontainers PostgreSQL (QA-03)

### A4. Transactional Outbox + Kafka (гарантии доставки)

- [ ] Запись `outbox_event` в одной транзакции с бизнес-изменением (AD-03)
- [ ] Outbox relay (polling publisher) → Kafka, идемпотентная публикация, at-least-once
- [ ] Топики: продюсер `comm.outbound.status.v1`, `comm.outbound.dlq.v1` (§8.1)
- [ ] Формат исходящего статуса §6.4, сериализация Avro/JSON в Schema Registry (BACKWARD) (NF-08)
- [ ] Тест chaos: падение инстанса в процессе отправки → нет потерь/дублей (QA-06, AD-03)

### A5. Входящие адаптеры (`adapter/in`)

- [ ] Kafka-консьюмеры входящих топиков по классам: `critical`/`transactional`/`notification`/`batch-control` (IK-01, AD-05)
- [ ] Раздельные пулы/конкурентность на класс трафика, изоляция OTP (TC-01)
- [ ] Poison-pill/DLT: `comm.inbound.parse-error.v1` + алерт (IK-04)
- [ ] REST API систем-источников `/api/v1`: `POST /messages`, `/batches`, `/batches/{id}/items`, actions, `GET /messages`, `GET /batches/{id}` (§8.2)
- [ ] OTP-приём p99 ≤ 200 мс, приоритетная постановка (FR-1.7)
- [ ] Обработка ошибок RFC 9457 (problem+json) с кодами `VALIDATION_FAILED`, `DUPLICATE`, `STREAM_SUSPENDED`, `QUOTA_EXCEEDED`, `TEMPLATE_NOT_PUBLISHED` (IR-01)
- [ ] Rate limiting на поток + `Retry-After` (IR-02)
- [ ] Провайдерский Callback API (webhook) — driving adapter: IP allowlist + секрет, идемпотентность (PM-02, SEC-07)
- [ ] Трансляция транспортных DTO → Command (AR-06), обработчики ошибок в `handlers/` (правило проекта)
- [ ] OpenAPI 3.1 генерация из кода (IR-03)
- [ ] `@RestControllerAdvice` в `rest/handlers/` — валидация, домен-ошибки, fallback (правило проекта)

### A6. Адаптеры провайдеров — SMS (этап MVP, §16 этап 2)

- [ ] Общий каркас адаптеров: таймауты, retry+backoff+jitter, circuit breaker (Resilience4j), Virtual Threads (PR-01, AR-07)
- [ ] Секреты только из `SecretResolverPort` (Vault/K8s), маскирование в логах (SEC-04, SG-04, PR-03)
- [ ] **Playmobile** адаптер `SmsProviderPort`: маппинг `Message`→`/send` (одиночный/батч), классификация ошибок 100–411 (PM-01, §18.1)
- [ ] Playmobile приоритеты `realtime/high/normal/low` по классу трафика (PM-03)
- [ ] Playmobile callback DLR → канонические статусы (§18.1, PM-02)
- [ ] **SMS Gate** адаптер `SmsProviderPort`: `/api/v2/send`, `/send_msgs`, `weight`, коды 0–27 (SG-01, §18.2)
- [ ] SMS Gate FEEDBACK DLR → канонические (§18.2, SG-02); реконсиляция `/api/v2/search` (SG-03)
- [ ] Троттлинг с учётом лимитов (SMS Gate 50 SMS/час/номер) (FR-2.5, §18.2)
- [ ] (Опция) SMPP-адаптер — за флагом конфигурации, в MVP выключен (PM-04)
- [ ] Contract-тесты на WireMock-стабах из документации провайдеров (QA-04, PR-04)

### A7. Маршрутизация, каналы, провайдеры, квоты (конфигурация в БД)

- [ ] CRUD провайдеров/каналов, основной+резервные, порядок fallback (FR-2.1, FR-2.2)
- [ ] Балансировка round-robin/вес/least-cost на уровне канала/потока (FR-2.3)
- [ ] Дефолтные канал/провайдер/класс на входящий поток (FR-2.4, TC-02)
- [ ] Квоты/бюджеты (кол-во и стоимость) по канал/провайдер/поток день/месяц, поведение при исчерпании (FR-2.6)
- [ ] Вкл/выкл провайдера и канала «на лету», режим обслуживания (FR-2.7)
- [ ] Горячее применение конфигурации без рестарта ≤30с (AD-07, NF-07)
- [ ] Декларативные `routing_policy` (match/action/priority); dry-run «какой маршрут получит сообщение X» (FR-8.9 задел)
- [ ] Health-check провайдеров + авто failover/failback (PR-02, FR-6.3)

### A8. Шаблоны и persoнализация

- [ ] CRUD шаблонов, версии, локали RU/UZ/EN, статусы DRAFT→ON_REVIEW→PUBLISHED→ARCHIVED (FR-4.1)
- [ ] Maker/checker: автор не публикует свой шаблон (FR-4.2)
- [ ] Merge-поля, строгий режим валидации (FR-4.3)
- [ ] SMS-предпросмотр: расчёт сегментов и стоимости по тарифам (FR-4.4)
- [ ] Маппинг на провайдерские шаблоны (Playmobile `template-id`), статус согласования (FR-4.5)
- [ ] Миграция существующей базы (~470 шаблонов) — скрипт импорта (FR-4.6)

### A9. Фильтрация и compliance

- [ ] Suppression list: проверка перед отправкой, причины, API+админка, результат `REJECTED/SUPPRESSED` (FR-5.1)
- [ ] Учёт opt-in/opt-out для нетранзакционных классов (FR-5.2)
- [ ] Quiet hours (Asia/Tashkent, localtime по признаку): отложить/отклонить (FR-5.3)
- [ ] Frequency capping для `NOTIFICATION` (в MVP счётчики+алерты) (FR-5.4)
- [ ] PCI: детектор PAN по Луну, reject/alert, запрет PAN в SMS (SEC-05)
- [ ] Email hard bounce → авто-добавление в suppression (EM-02)

### A10. Email (§16 этап 3)

- [ ] `EmailProviderPort` → SMTP-адаптер: STARTTLS/TLS, пул соединений, лимит скорости (EM-01)
- [ ] HTML+plain (multipart/alternative), вложения с лимитом, заголовок `X-Comm-Message-Id` (EM-01)
- [ ] Bounce-обработка: IMAP-poller / DSN → suppression (EM-02)
- [ ] DKIM-подпись при необходимости (конфиг) (EM-03)
- [ ] Email-шаблоны, интеграционные тесты (GreenMail) (QA-03)

### A11. Push (§16 этап 4)

- [ ] `PushProviderPort`, выбор адаптера по платформе токена (§9.4)
- [ ] **FCM** адаптер HTTP v1, OAuth2 service account, автообновление токена (PU-01, PU-02)
- [ ] FCM поля: `notification`/`data`/`android.priority`/`ttl`/`collapse_key`; классификация ошибок, `UNREGISTERED`→инвалидация токена+событие `push-token.invalidated` (PU-03, PU-04)
- [ ] Режим «FCM как единый провайдер для iOS» по конфигурации (PU-05)
- [ ] **APNs** адаптер HTTP/2, JWT `.p8`, ротация JWT, заголовки `apns-*`, пул соединений, `GOAWAY` (PU-06, PU-07)
- [ ] APNs классификация ошибок, `410`→инвалидация токена (PU-08)
- [ ] Мультитокенность получателя, агрегированный+детальный статус (PU-09)
- [ ] Массовый push fan-out батчами, изоляция `NOTIFICATION`, ≥500 push/с на инстанс (PU-10)
- [ ] Валидация payload ≤4КБ (APNs/FCM) на этапе валидации (PU-11)
- [ ] Push-статус ограничен `SENT_TO_PROVIDER`; `DELIVERED` — фаза 2 по событию приложения (PU-12)
- [ ] Sandbox/тестовые токены для тестовой отправки (PU-13)

### A12. Наблюдаемость, безопасность, эксплуатация

- [ ] Метрики Micrometer→Prometheus по канал/провайдер/поток/класс, латентности этапов, OTP e2e, лаги Kafka, состояние CB, квоты (OBS-01)
- [ ] Distributed tracing OpenTelemetry, `correlationId` в baggage/логах (OBS-02, FR-8.6)
- [ ] Structured JSON logs, MDC (messageId/streamId/batchId/correlationId), маскирование PII (OBS-03)
- [ ] Алерты: SLA OTP, delivery rate, error rate, CB open, лаг консьюмера, DLQ, квоты, БД/Kafka (OBS-04)
- [ ] Аутентификация источников: REST mTLS/OAuth2, Kafka SASL/SCRAM+ACL (SEC-01)
- [ ] RBAC на API (метод×ресурс×скоуп), критичные операции с подтверждением+аудит (SEC-03)
- [ ] Секрет-хранилище + ротация без простоя (SEC-04)
- [ ] Аудит действий пользователей и доступа к ПДн, append-only, экспорт (FR-7.3, SEC-08)
- [ ] Тестовая отправка с меткой TEST, без учёта в статистике (FR-7.4)
- [ ] Выгрузка событий в витрину (Kafka-топик/batch) (FR-6.4)
- [ ] K8s: liveness/readiness/startup, graceful shutdown с дообработкой in-flight (NF-05)
- [ ] Grafana-дашборды + runbook (OBS-05, OBS-06)

### A13. Admin REST BFF (backend для будущего frontend)

- [ ] BFF-эндпоинты под все разделы админки (§11.2): дашборд, батчи, сообщения, DLQ, потоки, каналы/провайдеры, маршрутизация, шаблоны, suppression, статистика, аудит, администрирование
- [ ] OIDC-интеграция (Keycloak/AD), проверка ролей на backend (UI-02, SEC-02, FR-7.2)
- [ ] Серверная пагинация/сортировка/фильтры для больших списков (UI-03)
- [ ] SSE/polling для дашбордов (UI-03)
- [ ] Kill switch, системные параметры (§11.2 Администрирование)

### A14. Тестирование и приёмка backend

- [ ] ArchUnit: правила гексагона AR-02/AR-03 (QA-02)
- [ ] Integration Testcontainers: PostgreSQL, Kafka, WireMock (Playmobile/SMS Gate/FCM/APNs), GreenMail (QA-03)
- [ ] Contract-тесты адаптеров и совместимости Kafka-схем (QA-04)
- [ ] Нагрузочные Gatling/k6: NF-01 (≥100 msg/s, батч 1млн + OTP), TC-01 (батч ≥500k без деградации OTP) (QA-05)
- [ ] Chaos: падение инстанса, недоступность провайдера (failover ≤60с), БД/Kafka (QA-06)
- [ ] Приёмочные сценарии: OTP-поток + транзакционный + массовый на тестовых контурах (QA-08)

---

## ЧАСТЬ B. FRONTEND (React SPA админ-панель)

Начинается после готовности backend Admin BFF (A13) и стабилизации контрактов.

### B0. Каркас

- [ ] Vite + React 18 + TypeScript, структура проекта, ESLint/Prettier (UI-01)
- [ ] UI-kit (Ant Design или MUI — согласовать с Банком) (UI-01)
- [ ] i18n RU/UZ/EN (минимум RU), формат дат Asia/Tashkent, хранение UTC (UI-01, UI-04)
- [ ] OIDC-аутентификация (Authorization Code + PKCE), хранение токена, refresh (UI-02, SEC-02)
- [ ] Гейтинг по ролям RBAC на клиенте (дублирует backend) (FR-7.2)
- [ ] API-клиент к Admin BFF (типы из OpenAPI), обработка ошибок/`Retry-After`
- [ ] Общие компоненты: серверные таблицы (пагинация/сортировка/фильтр), виртуализация, маскирование PII (UI-03, DB-04)

### B1. Разделы (по §11.2)

- [ ] Дашборд: объёмы по каналам, delivery rate, латентность OTP, health провайдеров, активные батчи, алерты; автообновление (все роли)
- [ ] Рассылки (батчи): список/карточка, прогресс, стоимость, пауза/возобновление/стоп, drill-down (OPERATOR+)
- [ ] Сообщения: поиск по externalMessageId/msisdn/correlationId/периоду, таймлайн статусов и попыток (OPERATOR+/VIEWER маскировано)
- [ ] DLQ: просмотр, фильтры, ручной повтор (единично/пакетно), архивирование (OPERATOR+)
- [ ] Входящие потоки: CRUD, интеграция, дефолты, квоты, quiet hours, статус подключения, приостановка (ADMIN)
- [ ] Каналы и провайдеры: CRUD, лимиты, тарифы, fallback, балансировка, вкл/выкл, тестовая отправка, health-история (ADMIN)
- [ ] Маршрутизация: просмотр/редактирование политик, приоритеты, dry-run проверка маршрута (ADMIN)
- [ ] Шаблоны: CRUD, версии, локали, merge-поля, предпросмотр сегментов/стоимости, workflow ревью/публикации (TEMPLATE_MANAGER + вторая роль)
- [ ] Suppression list: просмотр/добавление/удаление, импорт CSV, причины (ADMIN/OPERATOR)
- [ ] Статистика/отчёты: по каналам/провайдерам/потокам/батчам, стоимость, экспорт CSV/XLSX (ANALYST+)
- [ ] Аудит: журнал, фильтры, экспорт (SECURITY_AUDITOR/ADMIN)
- [ ] Администрирование: пользователи/роли (или маппинг SSO-групп), системные параметры, kill switch (ADMIN)

### B2. Тестирование frontend

- [ ] Unit/компонентные тесты (Vitest/RTL) ключевых компонентов
- [ ] E2E Playwright критических сценариев: пауза батча, повтор из DLQ, публикация шаблона, тестовая отправка (QA-07)
- [ ] Проверка доступности и i18n

---

## Порядок и вехи

1. **A0–A2** — каркас + домен + use cases (ядро).
2. **A3–A5** — персистентность, outbox/Kafka, входящие адаптеры.
3. **A6–A9** — SMS-провайдеры, маршрутизация, шаблоны, фильтры → **MVP SMS готов** (SRS этап 2).
4. **A10** Email → **A11** Push (SRS этапы 3–4).
5. **A12–A14** — наблюдаемость/безопасность/тесты (сквозные, ведутся параллельно, финализируются здесь).
6. **B0–B2** — frontend после стабилизации Admin BFF.

> После завершения каждого пункта — отмечать ✅ (не `[x]`).
