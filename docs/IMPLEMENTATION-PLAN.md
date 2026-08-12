# План реализации — Notification Hub

Источник требований: [`sms-notification-hub-spec.md`](./sms-notification-hub-spec.md).
Порядок: **сначала полностью backend, затем frontend**. Внутри backend — по слоям гексагональной
архитектуры и этапам SRS §16 (ядро+SMS → Email → Push → расширения).

**Легенда:** `[ ]` — не начато, `[~]` — в работе, `✅` — завершено (по правилу проекта отмечаем зелёной галочкой).
Пункты ссылаются на ID требований SRS (FR-*, AR-*, AD-*, PU-* …).

---

## ЧАСТЬ A. BACKEND

### Phase 1. Каркас проекта и инфраструктура разработки ✅

- ✅ Инициализировать Gradle multi-module (`domain`, `application`, `adapter`, `bootstrap`) — AR-01
  — Gradle wrapper 9.7.0, базовый пакет `uz.hamkorbank.commhub`, зависимости `bootstrap → adapter → application → domain`
- ✅ Настроить Java 25 toolchain, Spring Boot 4.x BOM, включить preview/Loom при необходимости
  — toolchain Java 25 (+ foojay-resolver для автозагрузки JDK), BOM Spring Boot 4.1.0, `spring.threads.virtual.enabled=true`;
  preview-флаги не нужны — Virtual Threads в Java 25 GA (AR-07)
- ✅ Подключить MapStruct (`mapstruct` + `mapstruct-processor`) во все модули с мапперами — правило проекта
  — подключён в `application` и `adapter`, генерация `componentModel = "spring"` проверена
- ✅ Настроить форматирование/линт (Spotless), Checkstyle, запрет `var` (правило проекта)
  — Spotless + palantir-java-format, `config/checkstyle/checkstyle.xml` (запрет `var` через MatchXpath,
  запрет field injection, запрет `System.out/err`), suppressions-файл; входит в `./gradlew build`
- ✅ `docker-compose` для локали: PostgreSQL 16, Kafka + Schema Registry, WireMock, MailHog/GreenMail
  — `docker-compose.yml`: postgres:16, apache/kafka:4.2.1 (KRaft), cp-schema-registry (BACKWARD), wiremock, greenmail
- ✅ Проверки сборки: `./gradlew build` (Spotless, Checkstyle, unit, ArchUnit) и `./gradlew integrationTest`
  — пайплайн в репозитории не держим: сборка запускается локально, SAST/анализ зависимостей (SEC-09)
  подключаются корпоративным конвейером Банка (Jenkins/GitLab CI, SonarQube, Nexus IQ)
- ✅ Настроить Flyway, каталог миграций, воспроизведение схемы с нуля (DB-01)
  — `adapter/out/persistence/src/main/resources/db/migration/V1__baseline.sql`, схема `comm_hub`, тест `FlywayMigrationIT`
- ✅ Git-репозиторий, ветвление, PR-шаблон
  — `.gitignore`, `CONTRIBUTING.md` (trunk-based, Conventional Commits), `.github/pull_request_template.md`

> Каркас ArchUnit-теста (`HexagonalArchitectureTest`: AR-02/AR-03) заведён уже сейчас; полный набор правил — Phase 15.

### Phase 2. Домен (`domain/`) — чистая Java, без Spring/JPA/Kafka/Jackson (AR-02) ✅

- ✅ Value objects и идентификаторы: `MessageId` (UUIDv7), `ExternalMessageId`, `StreamId`, `BatchId`, `DedupKey`, `CorrelationId`, `Recipient`, `EmailAddress`, `Msisdn`
  — пакет `model/vo`; плюс `ClientId`, `PushToken`, `AddressHash` (SHA-256 для suppression, DB-04), `ProviderId`/`ProviderCode`/`AdapterType`/`ProviderRef`/`ProviderMessageId`,
  `TemplateId`/`TemplateVersionId`/`TemplateCode`, `AttemptId`, `SuppressionEntryId`, `RoutingPolicyId`, `Money`.
  Все — records с проверкой инвариантов в каноническом конструкторе (`DomainValidationException`), у PII — `masked()` (`99890***4567`).
  Генератор UUIDv7 (RFC 9562, монотонный внутри миллисекунды) — `support/UuidV7`; хелперы инвариантов — `support/Guard`
- ✅ Enum'ы: `TrafficClass`, `Priority`, `Channel`, `MessageStatus` (§6.3), `TemplateStatus`, `BatchStatus`
  — пакет `model/type`; дополнительно `ChannelSelectionMode`, `BalancingStrategy`, `ChannelStatus`, `StreamStatus`, `ConnectionStatus`,
  `ProviderHealthStatus`, `SuppressionReason`, `RejectionReason` (коды причин для IR-01), `SmsEncoding`, `PushPlatform`, `ContentLocale`, `ActorType`,
  `ErrorClass` (retryable/non-retryable/blocking, §18.1), `AttemptResult`, `QuietHoursBehavior`, `QuotaExhaustionBehavior`, `QuotaVerdict`
- ✅ Sealed `MessageContent`: `SmsContent`, `EmailContent`, `PushContent` (§5.2, MP-02)
  — пакет `model/content`; плюс `Attachment` (метаданные вложения, EM-01) и `MessageContents` — контент по каналам:
  MP-02 разрешает одно уведомление для нескольких каналов, без этого fallback-цепочка Push→SMS нерабочая (одиночный случай — `MessageContents.of(content)`)
- ✅ Агрегат `Message` (envelope + content + channelPlan + status) — §5.2, §6.1
  — `MessageEnvelope` (канало-независимый конверт, MP-01) + `MessageContents` + `ChannelPlan` + статус с полной историей (`StatusChange`, `Actor`) + попытки доставки;
  `Timing` (TTL/окно отправки/localtime/send-evenly), `TemplateRef`; сегменты, стоимость, признак TEST (FR-7.4), ссылка на оригинал при `DUPLICATE`
- ✅ `ChannelPlan` с режимами: явный канал / выбор Модулем / fallback-цепочка (MP-03)
  — `EXPLICIT` / `MODULE_CHOICE` (со списком кандидатов или без) / `FALLBACK_CHAIN` (≥2 канала, `nextAfter`)
- ✅ Агрегаты: `Batch`, `Stream`, `Channel`, `Provider`, `RoutingPolicy`, `Template`+`TemplateVersion`, `SuppressionEntry`, `DeliveryAttempt`, `DlqEntry` (§6.1)
  — база `AggregateRoot<ID>` (равенство по идентификатору). Агрегат `Channel` из §6.1 назван `ChannelConfig`, чтобы имя `Channel` осталось за enum'ом канала (§6.4);
  вспомогательные value objects: `Batch.Progress`, `Stream.Defaults`, `QuietHours` (Asia/Tashkent, окно через полночь), `QuotaConfig`+`Usage`, `Tariff`, `RateLimit`,
  `Provider.Settings`, `RoutingPolicy.Match`/`Action`, `TemplateVersion.Body`/`Rendered`, `Template.ProviderMapping` (Playmobile `template-id`, FR-4.5).
  Реализованы: maker/checker публикации шаблона (FR-4.2), подстановка merge-полей `{NAME}` со строгим режимом (FR-4.3), состояние батча (FR-3.2),
  connection status потока по последней активности (FR-1.3), квоты/бюджеты (FR-2.6), single-retry DLQ (FR-3.3)
- ✅ Статусная машина `Message` с валидацией переходов и терминальными статусами (ST-01…ST-03)
  — таблица переходов в `MessageStatus` (+`BatchStatus`, `TemplateStatus`), нарушение → `InvalidStatusTransitionException`;
  каждая смена статуса пишется в историю с актором и деталями провайдера (ST-01); терминальные статусы по ST-02, `FAILED → QUEUED` только ручным повтором из DLQ
- ✅ Доменный сервис `SegmentCalculator` (GSM-7 160/153, UCS-2 70/67, escape-символы) — MP-06, §18.3
  — алфавит GSM 03.38 + таблица расширения (`^ { } \ [ ~ ] | €` = 2 символа), один не-GSM символ переводит всё сообщение в UCS-2; результат — `SmsSegmentation`
- ✅ Доменный сервис `Router` (выбор канала/провайдера, балансировка round-robin/вес/least-cost) — MP-05, FR-2.3
  — вход `RoutingRequest` (+ `rotation` для round-robin/веса, исключённые провайдеры для failover) и снапшот `RoutingConfiguration` (каналы/провайдеры/политики/дефолты потока, AD-07),
  выход — sealed `RoutingResult` (`Routed` с порядком попыток / `NoRoute` с причиной). Порядок выбора канала: политика → план сообщения → дефолт потока → доступный адрес
- ✅ Доменный сервис `FallbackChain` (порядок резерва) — FR-2.2
  — цепочка провайдеров канала (без выключенных / в обслуживании / `DOWN`), следующий провайдер после отказа, следующий непробованный, следующий канал цепочки (MP-03)
- ✅ Unit-тесты домена ≥80% строк, ≥90% критической логики (QA-01, AAA-паттерн)
  — 268 тестов, покрытие домена 97.1% строк; критическая логика: `MessageStatus` 100%, `SegmentCalculator` 100%, `FallbackChain` 100%, `Router` 95.5%, `Message` 93.5%.
  Порог проверяется в сборке: JaCoCo `jacocoTestCoverageVerification` (LINE ≥ 0.80) подключён к `:domain:check`

### Phase 3. Порты приложения (`application/port`) и use cases ✅

- ✅ Input-порты (интерфейсы use case) с Command/Query records (AR-06): `SubmitMessage`, `SubmitBatch`, `PauseBatch`/`ResumeBatch`/`StopBatch`, `ResendDlq`, `ProcessProviderStatus`, `KillSwitch`
  — плюс `SuspendStream`/`ResumeStream` (FR-3.2 на уровне потока), `DispatchMessage` (saga отправки, AD-04) и `ExpireMessages` (TTL-свип, FR-3.4);
  команды — records в `port/in/command`, результаты — records в `dto/`
- ✅ Output-порты: `MessageRepository`, `BatchRepository`, `StreamRepository`, `ProviderConfigRepository`, `TemplateRepository`, `SuppressionRepository`, `DedupRegistryPort`, `OutboxPort`, `StatusPublisherPort`, `SmsProviderPort`, `EmailProviderPort`, `PushProviderPort`, `ClockPort`, `MetricsPort`, `AuditPort`, `SecretResolverPort`
  — дополнительно `DlqRepository`, `QuotaCounterPort` (+`QuotaScope`/`QuotaWindow`), `FrequencyCounterPort`, `KillSwitchPort` (+`KillSwitchState`);
  канальные порты в `port/out/provider` с контрактами `SmsSubmission`/`EmailSubmission`/`PushSubmission` → `ProviderAck` (классификация ошибок адаптером, PR-01)
- ✅ Задел-порт `AudienceResolverPort` без реализации (FR-8.11)
- ✅ Задел-порт `CustomerPreferencePort` — заглушка (FR-8.2)
  — фильтры трактуют «нет записи» как «нет ограничения», так что заглушка ничего не блокирует
- ✅ Use case `SubmitMessage`: валидация → дедуп → выбор класса трафика → шаблон → сегментация → маршрут → сохранение+outbox (FR-1.1, FR-1.4, FR-1.5)
  — `SubmitMessageService`; отклонения — не исключения, а результат с канонической причиной (IR-01)
- ✅ Use case `SubmitBatch` + загрузка элементов чанками, прогресс (FR-1.6)
  — каждый элемент разворачивается в обычную команду `SubmitMessage`, ошибки элементов возвращаются поэлементно и не роняют чанк
- ✅ Оркестрация отправки (saga): resolve адаптера по `ProviderRef`, submit, обработка ack, retry/fallback (AD-04)
  — `DispatchMessageService` + `ProviderGateway` (резолв адаптера по `adapterType`), `SendingPolicy` (бюджет попыток и backoff);
  blocking-ошибка (Playmobile 102) → немедленный failover, исчерпание бюджета → `FAILED` + `DlqEntry` + событие в `comm.outbound.dlq.v1`
- ✅ Идемпотентность по `(streamId, externalMessageId)`/`dedupKey`, окно по умолчанию 24ч → статус `DUPLICATE` (FR-1.5)
  — быстрый предпроверочный поиск + атомарный claim ключа (гонка at-least-once консьюмеров разрешается в пользу первого)
- ✅ Фильтры доставки: Suppression, Quiet hours, frequency capping (FR-5.1…FR-5.4)
  — `DeliveryFilters`; quiet hours с поведением `DEFER` оставляют сообщение в `ROUTED`, диспетчер перепроверяет окно на каждом ходе;
  frequency capping в MVP только считает и алертит (`FrequencyCapPolicy.blocking = false`)
- ✅ Применение шаблона: merge-поля, строгий режим, только `PUBLISHED` (FR-4.1, FR-4.3)
  — `TemplateApplier`; допускается сообщение без контента (контент строится из тела шаблона, FR-1.2), fallback локали на RU
- ✅ `ProcessProviderStatus`: маппинг провайдерских статусов → канонические, запись истории (AD-06, ST-01)
  — идемпотентно: повтор колбэка и запрещённый переход возвращают `applied = false`, адаптер всё равно отвечает 200 (PM-02)
- ✅ TTL/`EXPIRED` авто-отмена (FR-3.4)
  — `ExpireMessagesService` (пакетный свип) + проверка дедлайна перед каждым вызовом провайдера
- ✅ Управление рассылками: пауза/возобновление/стоп батча/потока/kill switch (FR-3.2), не затрагивает `CRITICAL_OTP`
  — операции O(1): меняется состояние батча/потока/переключателя, сообщения отменяются или откладываются сагой при разборе очереди
- ✅ DTO (records) в `dto/` + MapStruct-мапперы в `mapper/` для конвертаций (правило проекта)
  — `MessageMapper` (в т.ч. формат статуса §6.4), `BatchMapper`, `ProviderSubmissionMapper`
- ✅ Unit-тесты use cases (моки портов), тесты идемпотентности и статусной машины
  — 59 тестов, покрытие `application` 86% строк (use case–сервисы 96%); порог JaCoCo LINE ≥ 0.80 подключён к `:application:check`

> Реализации use case помечены `@Service`/`@Transactional`, но бинов output-портов ещё нет — контекст Spring поднимется
> после Phase 4 (персистентность) и явного wiring в `bootstrap`. Компиляция, unit-тесты и ArchUnit от этого не зависят.
>
> После Phase 4 закрыты порты персистентности и `ClockPort`, после Phase 5 — `StatusPublisherPort`, после Phase 7 —
> `SecretResolverPort`, после Phase 8 — `ProviderStatsPort`, после Phase 10 — `FrequencyCounterPort` и
> `CustomerPreferencePort` (заглушкой, как предписывает FR-8.2), после Phase 12 — `PushDeliveryLogPort`, после
> Phase 13 — `MetricsPort`, `AuditQueryPort`, `EventExportRepository` и `AnalyticsPublisherPort`, после
> Phase 14 — `KillSwitchPort` и новые `MessageSearchPort`, `StatisticsPort`, `SystemParameterPort`.
> Без реализации остались ровно два интерфейса, и оба по замыслу: `ProviderProbePort` (задел, инжектится
> `ObjectProvider`'ом, для SMS реализации не будет) и `AudienceResolverPort` (FR-8.11 вне объёма — он
> нигде не инжектится, это зарезервированный контракт). Полный старт контекста как таковой ещё не
> проверен тестом — это Phase 15 (QA-03).

### Phase 4. Персистентность (`adapter/out/persistence`) — PostgreSQL

- ✅ Flyway-миграции таблиц (§10.1): `stream`, `channel`, `provider`, `routing_policy`, `template`, `template_version`, `batch`, `message`, `message_status_history`, `delivery_attempt`, `outbox_event`, `dlq_entry`, `suppression_list`, `dedup_registry`, `quota_counter`, `audit_log`, `app_user`/`app_role`/`user_role` (V2…V7)
- ✅ Партиционирование по времени `message`, `message_status_history`, `delivery_attempt`, `outbox_event` + авто-создание/отсоединение партиций (DB-02) — `comm_hub.ensure_partitions` / `detach_partitions_before` + `PartitionMaintenanceJob`
- ✅ Индексы: `(stream_id, accepted_at)`, `(external_id, stream_id)`, `(batch_id)`, `(dedup_key)`, `(correlation_id)`, частичные по нетерминальным статусам (DB-05)
- ✅ Реализация репозиториев под output-порты — JdbcClient + ручные row-мапперы (обоснование в `persistence/package-info.java`)
- ✅ Хеширование PII: `suppression_list` хранит только `address_hash` (SHA-256), адресов в таблице нет (DB-04)
- ✅ Шифрование контента сообщений (DB-04) — app-level AES-256-GCM (`persistence/crypto`: `ContentCipher`,
  `ContentCodec`, `ContentEncryptionProperties`), а не pgcrypto: ключ не уходит в SQL, в `pg_stat_statements`
  и в реплику (DB-06). Шифруются `message.contents` и `message.template_variables`; `recipient` остаётся
  открытым — на нём GIN-индекс и поиск (DB-05), его PII закрыта хешем и маскированием. Формат — JSON-скаляр
  `"CH1.<keyId>.<base64url(nonce‖ciphertext)>"`, чтение принимает и открытые строки (включение на живой базе),
  ключи ротируются по `active-key-id` (миграция V8 — контракт хранения в комментариях схемы)
- ✅ Read-only реплика для аналитики (DB-06) — `ReadReplicaConfig`, включается заданием `commhub.persistence.read-replica.url`
- ✅ Retention/архивация (конфигурируемый срок ≥12 мес) (DB-03) — `commhub.persistence.retention-months`; отцепление секций за флагом `detach-old-partitions`, включается вместе с процедурой архивации
- ✅ Интеграционные тесты с Testcontainers PostgreSQL (QA-03) — конфигурация, сообщения, гарантии доставки,
  шаблоны, обслуживание секций; контекст тестов поднимается с включённым шифрованием контента (DB-04)
- ✅ `FrequencyCounterPort` — таблицы под него в §10.1 нет, заведена вместе с функциональностью:
  `frequency_counter` + адаптер (V10, Phase 10, FR-5.4)
- ✅ `PushDeliveryLogPort` — таблицы под него в §10.1 нет, заведена вместе с функциональностью:
  `push_delivery` + адаптер (V12, Phase 12, PU-09)
- ✅ Порт `KillSwitchPort` — таблицы под него в §10.1 нет, заведена вместе со своей функциональностью:
  `kill_switch` (одна строка, CHECK на константный PK) + адаптер с тем же TTL-кэшем, что у конфигурации
  маршрутизации (V14, Phase 14, FR-3.2)

### Phase 5. Transactional Outbox + Kafka (гарантии доставки) ✅

- ✅ Запись `outbox_event` в одной транзакции с бизнес-изменением (AD-03) — `MessageStatusNotifier` пишет через
  `OutboxPort`, адаптер требует `Propagation.MANDATORY`: вызов без транзакции падает, а не теряет гарантию
- ✅ Outbox relay (polling publisher) → Kafka, идемпотентная публикация, at-least-once
  — use case `PublishOutboxEvents`/`PublishOutboxEventsService` + планировщик `adapter/in/scheduler/OutboxRelayScheduler`;
  выборка `FOR UPDATE SKIP LOCKED` (инстансы делят очередь), пометка `published_at` только после ack брокера,
  продюсер с `acks=all` и `enable.idempotence=true`; сбой публикации останавливает проход — порядок статусов
  по сообщению важнее, чем протолкнуть следующее событие (`attempts`/`last_error` на строке видны оператору)
- ✅ Топики: продюсер `comm.outbound.status.v1`, `comm.outbound.dlq.v1` (§8.1) — `KafkaOutboundProperties`,
  ключ партиционирования — `messageId`, заголовки `commhub-event-id`/`-event-type`/`-stream-id`/`-schema-version`
- ✅ Формат исходящего статуса §6.4 — `StatusEventCodec` (JSON, поля §6.4 + `schemaVersion`, отсутствующие
  значения как явные `null`), схема `adapter/out/kafka/src/main/resources/schema/comm.outbound.status.v1.json`
  — ⚠️ регистрация субъекта в Schema Registry (BACKWARD, NF-08) остаётся операционным шагом: сериализатор
  намеренно не ходит в реестр, иначе реестр окажется на пути отправки каждого статуса. Avro не берём —
  контракт JSON, как у входящего IK-03. Команда регистрации — в `CONTRIBUTING.md`
- ✅ Тест chaos: падение инстанса в процессе отправки → нет потерь/дублей (QA-06, AD-03) — `OutboxRelayIT`
  (Testcontainers PostgreSQL + Kafka): падение между ack брокера и коммитом, недоступный брокер, конкурентная
  выборка двумя relay

### Phase 6. Входящие адаптеры (`adapter/in`)

- ✅ Kafka-консьюмеры входящих топиков по классам: `critical`/`transactional`/`notification`/`batch-control`
  (IK-01, AD-05) — `adapter/in/kafka`: `InboundMessageListener` (три топика сообщений) и `BatchControlListener`
  (заголовки и команды батчей, ключ `batchId` — create и следующий за ним pause попадают в одну партицию).
  Класс трафика берётся **из топика**, а не из поля документа: изоляция построена на топиках, и payload не
  должен уметь себя переклассифицировать
- ✅ Раздельные пулы/конкурентность на класс трафика, изоляция OTP (TC-01) — четыре
  `ConcurrentKafkaListenerContainerFactory` в `KafkaConsumerConfig`, у каждого свой consumer group, client-id и
  пул потоков (`commhub.kafka.inbound.concurrency`, по умолчанию 2/4/8/1). Критичному классу намеренно дано
  меньше потоков: ему нужны не многие, а свободные
- ✅ Poison-pill/DLT: `comm.inbound.parse-error.v1` + алерт (IK-04) — `InboundErrorHandlerConfig`: нарушения
  контракта не ретраятся (документ неверен, а не момент) и сразу уезжают в parse-error с заголовками
  `commhub-origin-topic`/`commhub-failed-field` и ERROR-логом; транзиентные сбои — экспоненциальный backoff
  до минуты. Записи читаются как строки и парсятся в листенере: упавший десериализатор унёс бы весь poll
- ✅ REST API систем-источников `/api/v1` (§8.2) — `MessageController` (`POST /messages`, `GET /messages/{id}`,
  `GET /messages?streamId=&externalMessageId=`) и `BatchController` (`POST /batches`,
  `POST /batches/{id}/items`, `POST /batches/{id}/actions/{start|pause|resume|stop}`, `GET /batches/{id}`).
  Под чтение статусов добавлены query-use case'ы `GetMessage`/`GetBatch` (`port/in/query`, `MessageView`/`BatchView`),
  под `actions/start` — входной порт `StartBatch` (реализован тем же `BatchControlService`)
- ✅ OTP-приём: приоритетная постановка (FR-1.7) — изоляция пулов по классам трафика (см. TC-01) плюс короткий
  синхронный путь приёма на виртуальных потоках: один разбор документа, ответ из той же транзакции, никаких
  очередей перед use case'ом. ⚠️ Сам порог p99 ≤ 200 мс — предмет нагрузочных тестов (Phase 15, NF-01/TC-01),
  контроллер его гарантировать не может
- ✅ Обработка ошибок RFC 9457 (problem+json) с кодами IR-01 — `rest/problem`: каталог `ProblemType`
  (`VALIDATION_FAILED`, `DUPLICATE`, `STREAM_SUSPENDED`, `QUOTA_EXCEEDED`, `TEMPLATE_NOT_PUBLISHED` и остальные
  `RejectionReason`) + `ProblemFactory`. Отказ конвейера никогда не приходит как 202 с отказным статусом внутри:
  клиент, проверяющий только HTTP-код, не должен принять отказ за приём
- ✅ Rate limiting на поток + `Retry-After` (IR-02) — `rest/ratelimit`: token bucket на поток, in-memory на
  инстанс (общий счётчик поставил бы сетевой вызов перед каждым приёмом OTP); лимиты в
  `commhub.rest.rate-limit`; пер-стримовые переопределения переехали в реестр потоков в Phase 8
  (`stream.rate_limit_config`, AD-07) — yaml остался запасным вариантом для потоков без собственного лимита
- ✅ Провайдерский Callback API (webhook) — `adapter/in/callback`: `POST /api/callbacks/{providerCode}`,
  `CallbackGuard` (IP allowlist + общий секрет, сравнение секрета в постоянном времени, конфигурация на
  провайдера), ответ на отказ — голый 403 без причины. Идемпотентность обеспечивает `ProcessProviderStatus`
  (AD-06): отчёт, ничего не изменивший, тоже отвечает 200, иначе провайдер будет ретраить бесконечно.
  Сами трансляторы payload'ов (`ProviderCallbackTranslator`) живут в пакетах адаптеров провайдеров (Phase 7);
  настроенный провайдер без транслятора получает 404
- ✅ Трансляция транспортных DTO → Command (AR-06), обработчики ошибок в `handlers/` — `adapter/in/contract`
  (общий для REST и Kafka: §8.2 говорит «тело = IK-03», поэтому парсер один) + MapStruct-мапперы
  `InboundPayloadMapper` и `RestResponseMapper`
- ✅ OpenAPI 3.1 (IR-03) — `adapter/in/rest/src/main/resources/openapi/comm-hub-api-v1.yaml`, отдаётся по
  `GET /api/v1/openapi.yaml`. ⚠️ Не генерируется springdoc'ом: его актуальная ветка 2.8.x собрана под Spring 6 и
  Jackson 2, релиза под Boot 4 нет. Вместо генерации — `OpenApiContractTest`: обходит `@RequestMapping`
  контроллеров и валит сборку, если эндпоинт не описан в документе. Заменить на генерацию, когда springdoc
  выпустит совместимую версию
- ✅ `@RestControllerAdvice` в `rest/handlers/` — по классу на концерн: `SubmissionRejectionHandler` (вердикт
  конвейера → код IR-01), `ContractViolationHandler` (400 с указанием поля), `NotFoundHandler` (404),
  `StateConflictHandler` (409 на запрещённый переход), `RateLimitHandler` (429 + `Retry-After`),
  `UnexpectedFailureHandler` (голый 500, стек — только в лог: в сообщении исключения может быть адрес
  получателя или секрет провайдера)

### Phase 7. Адаптеры провайдеров — SMS (этап MVP, §16 этап 2)

- ✅ Общий каркас адаптеров: таймауты, retry+backoff+jitter, circuit breaker (Resilience4j), Virtual Threads (PR-01, AR-07)
  — `adapter/out/provider/support`: `ProviderRestClients` (JDK-клиент на виртуальных потоках, connect/read таймауты
  обязательны), `ProviderCallExecutor` (Retry + CircuitBreaker на провайдера, реестры Resilience4j заводятся явным
  `@Configuration`, без AOP), `ProviderThrottle`, `Masking`. Контракт каркаса: вызов **возвращает** `ProviderAck` на
  любой ответ провайдера и **бросает** `ProviderCallException` только когда ответа не было — ретрай и breaker видят
  исключение, поэтому поток отказов по контенту никогда не открывает breaker
- ✅ Секреты только из `SecretResolverPort` (Vault/K8s), маскирование в логах (SEC-04, SG-04, PR-03)
  — `adapter/out/secret`: схемы `env:` (основная) и `prop:`, после схемы допустим модификатор `base64:`
  для многострочных блобов (JSON сервис-аккаунта FCM, `.p8` APNs); без схемы — литерал из
  `commhub.secrets.values` для локального стенда и тестов. Кэш с TTL держит разрешение ссылки подальше от
  каждого сообщения; ротация — обновление Secret'а плюс rolling restart (10.08.2026: ADR-0036 заменил
  каталог секретов и схему `file:`, см. пункт SEC-04 в Phase 13). В Vault Hub не ходит сам: токен Vault — ещё один
  секрет, а реестр на пути каждой отправки — ещё одна точка отказа; значение в окружение пода кладёт платформа.
  Заодно закрыт долг Phase 6: секрет callback'а (`commhub.callback.providers.<code>.secret-ref`) тоже идёт через
  резолвер, а нерезолвящаяся ссылка отклоняет вызов, а не отключает проверку молча
- ✅ **Playmobile** адаптер `SmsProviderPort`: маппинг `Message`→`/send` (одиночный/батч), классификация ошибок 100–411 (PM-01, §18.1)
  — `PlaymobileSendCodec` (документ §9.1 по полям, включая `timing` и `template-id`/`variables` FR-4.5),
  `PlaymobileErrorCatalog` (100 → retryable, 102 → blocking + breaker, остальное → non-retryable; неизвестный код
  тоже non-retryable — HTTP 400 уже сказал, что запрос отвергнут). Ретрай внутри попытки разрешён: `message-id`
  генерирует Hub, повтор дедуплицируется провайдером
- ✅ Playmobile приоритеты `realtime/high/normal/low` по классу трафика (PM-03)
  — класс трафика задаёт нижнюю границу, сообщение может её поднять, но не в классе `NOTIFICATION`: полоса OTP
  принадлежит классу (он приходит из топика), а не документу (TC-01)
- ✅ Playmobile callback DLR → канонические статусы (§18.1, PM-02)
  — `PlaymobileCallbackTranslator` + `PlaymobileStatusCatalog`. Отчёт без `message-id`/`status` — нарушение
  контракта (отказ с указанием поля); отчёт с незнакомым словом статуса **отбрасывается с WARN и отвечается 200**:
  §18.1 сам говорит, что точный перечень фиксируется на интеграции, а отказ заставит провайдера ретраить вечно
- ✅ **SMS Gate** адаптер `SmsProviderPort`: `/api/v2/send`, `/send_msgs`, `weight`, коды 0–27 (SG-01, §18.2)
  — `SmsGateSendCodec` + `SmsGateResponseCatalog`: 10–13 → blocking, 27 → retryable, 1 (спам-лимит) → retryable
  **без** записи в счётчик отказов провайдера, 20 → non-retryable + инвалидация адреса. Ретрая внутри попытки нет:
  `/api/v2/send` не принимает клиентский id, повтор после потерянного ответа — второе SMS клиенту
- ✅ SMS Gate FEEDBACK DLR → канонические (§18.2, SG-02); реконсиляция `/api/v2/search` (SG-03)
  — `SmsGateCallbackTranslator` (тело JSON или form-поля), `SmsGateStatusCatalog`, `SmsGateReconciler` (планировщик,
  выключен по умолчанию) + новый generic-метод порта `MessageRepository.findAwaitingDeliveryReport`.
  Два осознанных отступления от таблицы §18.2: код 6 (Unknown) не применяется вовсе — «неизвестно» не исход, его
  разбирает реконсиляция; код 7 (InBlackList) даёт `UNDELIVERED`, а не `REJECTED` — из `SENT_TO_PROVIDER` перехода
  в `REJECTED` нет (ST-01), кандидатура в suppression несётся отдельным признаком
- ✅ Троттлинг с учётом лимитов (SMS Gate 50 SMS/час/номер) (FR-2.5, §18.2)
  — `ProviderThrottle`: TPS, лимит в минуту и почасовой потолок на номер; in-memory на инстанс (как лимитер IR-02),
  поэтому по умолчанию целимся в 45/час, а не в 50. Задержанное сообщение получает retryable-ack и уходит к
  резервному провайдеру, а не в DLQ
- [ ] (Опция) SMPP-адаптер — за флагом конфигурации, в MVP выключен (PM-04)
  — **сознательно не реализован.** §9.1 фиксирует MVP на HTTP, PM-04 помечает SMPP как опцию. Это второй транспорт
  (bind/enquire_link, оконность, своя склейка сегментов), а не вариация текущего: пустышка за флагом хуже отсутствия.
  Обоснование продублировано в `adapter/out/provider/package-info.java`
- ✅ Contract-тесты на WireMock-стабах из документации провайдеров (QA-04, PR-04)
  — `PlaymobileSmsAdapterIT` (6) и `SmsGateSmsAdapterIT` (7) на WireMock: форма запроса, Basic auth / login+key,
  классификация кодов, открытие breaker'а, read timeout, поэлементные ответы батча, троттлинг.
  Плюс 99 unit-тестов на кодеки, таблицы §18.1/§18.2, каркас и секрет-резолвер

### Phase 8. Маршрутизация, каналы, провайдеры, квоты (конфигурация в БД) ✅

- ✅ CRUD провайдеров/каналов, основной+резервные, порядок fallback (FR-2.1, FR-2.2)
  — входные порты `ManageProviders`/`ManageChannels`/`ManageStreams`/`ManageRoutingPolicies` + read-side
  `GetRoutingConfiguration`; по интерфейсу на агрегат, а не по одному методу на файл: операции делят
  транзакцию, аудит-запись и сброс снапшота. Порядок fallback правится кодами провайдеров (код переживает
  строку, на которую ссылается), неизвестный код — ошибка, а не молча выброшенный резерв. Удалить
  провайдера, пока он есть в цепочке канала, нельзя: молча укоротившаяся цепочка выглядит как исправная
- ✅ Балансировка round-robin/вес/least-cost на уровне канала/потока (FR-2.3)
  — `Stream.Defaults.balancingStrategy` + порядок разрешения в `Router`: политика → дефолт потока → канал
- ✅ Дефолтные канал/провайдер/класс на входящий поток (FR-2.4, TC-02)
  — редактируются через `ManageStreams`; сам разбор дефолтов был в домене с Phase 2 (`Stream.effective*`)
- ✅ Квоты/бюджеты (кол-во и стоимость) по канал/провайдер/поток день/месяц, поведение при исчерпании (FR-2.6)
  — `QuotaConfig` теперь и на `Provider`, и на `ChannelConfig`; `QuotaGuard` считает три измерения и
  называет в отказе то, чья квота исчерпана. Заодно исправлено расхождение Phase 3: проверка читала
  scope потока, а регистрация писала scope «поток+канал» — счётчик потока не накапливался вовсе
- ✅ Вкл/выкл провайдера и канала «на лету», режим обслуживания (FR-2.7)
  — `ProviderStateCommand`/`ChannelStateCommand`; обслуживание — не «выключено другим словом»:
  выключенный провайдер оставлен Банком, провайдер в обслуживании ожидается обратно
- ✅ Горячее применение конфигурации без рестарта ≤30с (AD-07, NF-07)
  — `CachingProviderConfigRepository` (снапшот в памяти + `ConfigurationRefreshScheduler`): кэшируется
  **только** `routingConfiguration` — остальные методы обслуживают админ-путь, который грузит агрегат,
  чтобы его изменить, и отдавать туда общий изменяемый объект нельзя. Запись на этом инстансе сбрасывает
  снапшот сразу, соседние подхватывают за `refresh-interval`; межинстансной инвалидации намеренно нет —
  это ещё один канал связи, который может отказать, а NF-07 разрешает 30с (потолок проверяется на старте).
  Сюда же переехали пер-стримовые лимиты IR-02 (`stream.rate_limit_config`, `StreamLimits`) и
  runtime-часть профиля провайдера — лимиты и `provider.endpoint_config` (`ProviderRuntimeSettings`):
  originator/sender, приоритеты, веса и TTL правятся без рестарта. Не переехали и не переедут: креды
  (ссылки в секрет-хранилище, SEC-04) и параметры HTTP-клиента (base-url, таймауты, окна breaker'а) —
  это топология деплоя, а смена клиента на пути отправки стоит дороже, чем даёт
- ✅ Декларативные `routing_policy` (match/action/priority); dry-run «какой маршрут получит сообщение X» (FR-8.9 задел)
  — `ManageRoutingPolicies` (правило сохраняется целиком: половина правила — это способ получить
  противоречивую таблицу маршрутизации) + `EvaluateRoute`/`RouteEvaluationService`: запрос превращается в
  временное сообщение и идёт через тот же `Router` и тот же снапшот. Отдельная «симуляция» правил — это
  вторая реализация, и в первый же день расхождения она соврёт ровно тогда, когда ей поверили
- ✅ Health-check провайдеров + авто failover/failback (PR-02, FR-6.3)
  — `CheckProviderHealth`/`ProviderHealthService` + `ProviderHealthPolicy` по пассивным цифрам
  `delivery_attempt` (`ProviderStatsPort`): `DOWN` → провайдер перестаёт быть selectable, и роутер берёт
  резерв на следующем же сообщении. Failback — сложная половина: провайдер в `DOWN` не получает трафика,
  поэтому новых цифр не будет; после `recovery-after` молчания статус переводится в `UNKNOWN`
  (испытательный срок), и первое чистое окно возвращает `UP`. Синтетический probe описан портом
  `ProviderProbePort`, но **у SMS-провайдеров MVP реализации нет**: ни §9.1, ни §9.2 не дают health-эндпоинта,
  а единственный синтетический вызов — платное SMS на реальный номер

### Phase 9. Шаблоны и персонализация ✅

- ✅ CRUD шаблонов, версии, локали RU/UZ/EN, статусы DRAFT→ON_REVIEW→PUBLISHED→ARCHIVED (FR-4.1)
  — входной порт `ManageTemplates` (по интерфейсу на агрегат, как конфигурация Phase 8) + read-side
  `GetTemplates` (карточка, страница каталога без текстов, предпросмотр). Правила остались в домене:
  таблица переходов — в `TemplateStatus`, «у локали ровно одна отправляемая версия» — в новом
  `Template.publishVersion` (публикация v2 архивирует v1; если публикацию отклонили, предыдущая версия
  остаётся опубликованной). Правка текста разрешена только в `DRAFT` (`TemplateVersion.updateBody`):
  версия на ревью — это то, что смотрит проверяющий, а опубликованная — то, из чего собраны отправленные
  сообщения. «D» в CRUD — не удаление: карточка уходит в `ARCHIVED` (новый `TemplateCatalogStatus`), потому
  что код шаблона стоит в истории каждого отправленного по нему сообщения (FR-7.3); архивная карточка
  перестаёт быть отправляемой (`TemplateApplier` отклоняет её с `TEMPLATE_NOT_PUBLISHED`)
- ✅ Maker/checker: автор не публикует свой шаблон (FR-4.2)
  — автор версии и ревьюер публикации берутся **из актора**, а не из поля команды: контроль, который
  доверяет имени, введённому в форму, — не контроль. Безымянный актор (сам Hub) шаблоны не пишет и не
  публикует. Правило доменное (`TemplateVersion.publish`), поэтому оно живо и на чтении из БД
- ✅ Merge-поля, строгий режим валидации (FR-4.3)
  — отправка идёт строгим режимом с Phase 3 (`TemplateApplier` → `TEMPLATE_VARIABLE_MISSING`). Добавлены
  `TemplateVersion.renderPreview`/`missingVariables` для админки: незаполненное поле остаётся видимым как
  `{NAME}` и перечисляется отдельно, а не молча превращается в пустую строку. Порядок объявленных полей
  теперь сохраняется — оператор вычитывает текст в порядке текста
- ✅ SMS-предпросмотр: расчёт сегментов и стоимости по тарифам (FR-4.4)
  — `GetTemplates.preview`: тем же доменным `SegmentCalculator`, которым конвейер считает сегменты (§18.3),
  и теми же тарифами провайдеров (FR-2.1), чтобы предпросмотр и счёт не расходились. Цена — по каждому
  провайдеру канала с признаком `selectable`: цена выключенного или `DOWN`-провайдера не та, которую
  заплатит отправка. Предпросмотр намеренно доступен и для `DRAFT` — он нужен именно во время написания,
  поэтому в ответе есть статус показанной версии
- ✅ Маппинг на провайдерские шаблоны (Playmobile `template-id`), статус согласования (FR-4.5)
  — `mapProviderTemplate`/`unmapProviderTemplate`: провайдер должен существовать и обслуживать канал
  шаблона (SMS-шаблон на email-провайдера — ошибка оператора, а не запись в таблице). Согласование
  (`approved`) — факт, который фиксирует оператор: согласование у оператора связи организационное, Hub его
  узнать не может. Снятый маппинг теперь действительно удаляется из БД (`syncMappings`), а не только из
  агрегата
- ✅ Миграция существующей базы (~470 шаблонов) — скрипт импорта (FR-4.6)
  — use case `ImportTemplates` + адаптер `adapter/in/importer` (CSV с шапкой, колонки по именам, RFC 4180
  кавычки — SMS-тексты переносятся, поэтому многострочное поле здесь норма; формат в `package-info.java`).
  Не SQL-скрипт: правила валидности шаблона живут в домене, а `INSERT` обошёл бы их все. Идемпотентно по
  формулировке — строка, текст которой уже опубликован в этой локали, пропускается, поэтому файл можно
  перезапустить после правки нескольких строк. Плохая строка попадает в отчёт, остальной файл заходит:
  470 строк, собранных руками, содержат несколько битых, и падать на первой — значит искать их по одной за
  прогон. `approver` публикует импортированное и обязан отличаться от `author` (maker/checker для миграции:
  автор — импорт, проверяющий — тот, кто его запустил); без `approver` всё ложится черновиками. Запуск —
  за флагом `commhub.import.templates.enabled`, по умолчанию выключен

> Попутно закрыт дефект Phase 8: `audit_log.before_state`/`after_state` — это `jsonb`, а порт передаёт
> отрендеренный текст, поэтому `CAST(... AS jsonb)` падал на любой записи аудита конфигурации. Значение
> теперь оборачивается `to_jsonb` в JSON-строку, и на это есть `AuditPersistenceIT` — именно отсутствие
> теста на адаптер аудита и позволило дефекту дожить до Phase 9.

### Phase 10. Фильтрация и compliance ✅

- ✅ Suppression list: проверка перед отправкой, причины, API+админка, результат `REJECTED/SUPPRESSED` (FR-5.1)
  — проверка перед отправкой была с Phase 3 (`DeliveryFilters` → `SUPPRESSED`); здесь закрыто управление:
  входной порт `ManageSuppressions` (адрес / клиент / снятие) + read-side `GetSuppressions` (страница списка и
  точечная проверка «можно ли писать этому получателю»), как конфигурация Phase 8 и шаблоны Phase 9 —
  по интерфейсу на агрегат, отказы исключениями (409/404), каждое изменение с аудит-записью (FR-7.3).
  Адрес приходит в открытом виде (у оператора на руках номер, а не хеш), проверяется value object'ом канала
  и хешируется до записи (DB-04): мимо-набранный номер отклоняется здесь, а не превращается в хеш, который
  потом ничего не совпадает. В списке фильтра по адресу нет и не будет — в таблице лежат хеши, «покажи этот
  номер» это `check`, а не фильтр. Повторная запись того же адреса — конфликт, а не вторая строка: две записи
  различались бы причиной и сроком, и применялась бы та, которую вернул индекс.
  ✅ REST/BFF-эндпоинты приехали в Phase 14 вместе с остальной админкой (§11.2), включая импорт CSV
- ✅ Учёт opt-in/opt-out для нетранзакционных классов (FR-5.2)
  — фильтр согласий работает с Phase 3, здесь появился бин под `CustomerPreferencePort` —
  `StubCustomerPreferenceAdapter` (пусто = «ограничений нет»), как и предписывает SRS: мастер-система согласий
  ещё не выбрана (§7.8 FR-8.2, открытый вопрос 8 §17), а Hub — транспорт, и заводить в нём второй реестр
  согласий Банка нельзя. Реально исполняемый сегодня opt-out — запись в suppression с причиной `OPT_OUT`
  (FR-5.1); когда мастер-система появится, меняется только этот адаптер (AR-04)
- ✅ Quiet hours (Asia/Tashkent, localtime по признаку): отложить/отклонить (FR-5.3)
  — окно ищется в порядке «клиент → поток → канал»: персональное окно из `CustomerPreferences` идёт первым,
  потому что только оно выражено в часовом поясе получателя — а это и есть то, что просит признак `localtime`.
  Отдельного разрешения зоны по номеру не делаем: `Msisdn` допускает только `9989xxxxxxxx`, то есть
  «местное время получателя» и `Asia/Tashkent` — одни и те же часы, а сам признак всё равно уезжает в
  Playmobile (§9.1), который применяет его на своей стороне по каждому номеру
- ✅ Frequency capping для `NOTIFICATION` (в MVP счётчики+алерты) (FR-5.4)
  — закрыт порт `FrequencyCounterPort`: таблица `frequency_counter` (V10) и адаптер. Считаем часовыми
  ведрами, а не строкой на сообщение: при прогнозных объёмах (~17,2 млн push/мес, §12.2) строка на отправку —
  это вторая таблица размером с `message` ради одного числа. Огрубление работает в безопасную сторону —
  ведро с левым краем окна учитывается целиком, поэтому кап скорее сработает, чем промолчит. Блокировка
  выключена (`commhub.compliance.frequency-cap.blocking=false`): кап на живом трафике сначала измеряют,
  иначе первым, что он отрежет, окажется штатная рассылка. Заодно появился `RetentionSweepScheduler` —
  чистит `frequency_counter` и `dedup_registry`: обе таблицы живут в объёме окна, не секционированы, и
  размер им держит только удаление (у `purgeExpired` до сих пор не было вызывающего — DB-03)
- ✅ PCI: детектор PAN по Луну, reject/alert, запрет PAN в SMS (SEC-05)
  — детектор был с Phase 3, добавлен настраиваемый режим (`PanPolicy`, `commhub.compliance.pan-blocking`) и
  метрика `MetricsPort.panDetected`. Режим «только алерт» нужен для миграции: систему-источник, которая
  кладёт PAN в текст, надо сначала найти, а Hub, молча роняющий её трафик в первый день, не говорит, кого
  править. На SMS режим не распространяется — `PanPolicy.blocksOn(SMS)` всегда `true`: SEC-05 запрещает
  полный номер карты в SMS без оговорок. В лог найденный текст не пишется — это тот же PAN, только в другом
  хранилище; алерт по OBS-04 строится на метрике. Тело шаблона проверяется безусловно
  (`ManageTemplates.saveVersion` и импорт FR-4.6): шаблон пишет человек в форме, номер карты место которому —
  merge-поле, а не формулировка
- ✅ Email hard bounce → авто-добавление в suppression (EM-02)
  — механизм: `SuppressionRegistrar` (идемпотентно, `saveIfAbsent` с `ON CONFLICT DO NOTHING` — колбэки
  повторяются, реконсиляция SG-03 спрашивает снова, массовая рассылка бьёт в мёртвый адрес раз на сообщение)
  плюс поле `ProviderStatusCommand.suppressAs`: канонический статус описывает, что стало с *этим* сообщением,
  а suppression — что будет со всеми следующими, и код 7 §18.2 требует обоих сразу. Подключено к двум путям:
  ответ провайдера с `invalidRecipient` в саге отправки (SMS Gate 20) и отчёт о доставке через
  `ProcessProviderStatus` (SMS Gate FEEDBACK 7, реконсиляция). Suppression применяется до проверок перехода и
  независимо от них: «адрес мёртв» — утверждение об адресе, а не о сообщении, поэтому отчёт, не сменивший
  статус, всё равно должен остановить следующую отправку. Разбор самих NDR/DSN (IMAP-poller) — Phase 11,
  ему остаётся сложить `ProviderStatusCommand` с `HARD_BOUNCE`; тем же путём пойдёт инвалидация push-токенов
  (PU-04, PU-08)

> Тесты: 40 новых unit-тестов (`SuppressionUseCasesTest`, `DeliveryFiltersTest`, `MessageValidatorTest`,
> `SuppressionRegistrarTest` + дополнения в тестах саги, статусов и шаблонов) — покрытие `application`
> 88,3% строк; интеграционный `CompliancePersistenceIT` (Testcontainers) на административные выборки
> suppression и на счётчики частоты.

### Phase 11. Email (§16 этап 3) ✅

- ✅ `EmailProviderPort` → SMTP-адаптер: STARTTLS/TLS, пул соединений, лимит скорости (EM-01)
  — `adapter/out/provider/smtp`: `SmtpEmailAdapter` на том же каркасе, что и SMS-адаптеры, и с тем же
  контрактом с `ProviderCallExecutor` (ответ релея → `ProviderAck`, отсутствие ответа → исключение).
  Единица стоимости здесь не запрос, а соединение: `SmtpTransportPool` держит их открытыми, и его размер
  и есть предел параллелизма канала — на виртуальных потоках (AR-07) ничего не стоит предложить релею
  десять тысяч одновременных соединений, а релей, которому их предложили, перестаёт быть релеем.
  Отправитель, не дождавшийся свободного соединения, получает retryable-ack и уходит на резерв, а не в DLQ.
  STARTTLS требуется, а не «по возможности»: молчаливый откат в открытый канал — это письмо клиента,
  ушедшее по сети незашифрованным. Разбор ответов — `SmtpResponseCatalog` по RFC 5321/3463: 4xx и отказ
  аутентификации бросаются (их видят retry и breaker), 5xx про конкретное сообщение возвращается отказом,
  потому что рассылка по списку с мёртвыми ящиками — это ровный поток `550`, который не говорит о здоровье
  релея ничего. Отказ в аутентификации открывает breaker сразу — это email-версия Playmobile 102
- ✅ HTML+plain (multipart/alternative), вложения с лимитом, заголовок `X-Comm-Message-Id` (EM-01)
  — `SmtpMessageCodec`: два тела → `multipart/alternative` (plain первым, HTML вторым — последняя
  альтернатива предпочтительная, RFC 2046 §5.1.4), вложения оборачивают его в `multipart/mixed`.
  **`Message-ID` пишет Hub, а не релей** — это одно решение и делает возможным EM-02: очередной id релея
  локален и никогда не возвращается, а `Message-ID` цитирует каждый отчёт о недоставке; тот же идентификатор
  едет в `X-Comm-Message-Id` для отчётов, возвращающих исходные заголовки. Потолки вложений — не в адаптере,
  а на валидации (`EmailPolicy`, `commhub.compliance.email`): сообщение, отклонённое конвейером, несёт
  системе-источнику каноническую причину (IR-01), а отклонённое релеем — SMTP-код, который наверху никто не
  читает; и узнавать о превышении после того, как тело уже уехало по проводу, дорого. Байты вложений даёт
  `AttachmentStore` (примонтированный каталог с проверкой выхода за него): объектное хранилище Банк ещё не
  выбрал, и заводить зависимость от S3 ради нескольких PDF — не тот порядок действий
- ✅ Bounce-обработка: IMAP-poller / DSN → suppression (EM-02)
  — у email нет колбэка: ответ на «дошло ли» приходит письмом на адрес конверта. `EmailBouncePoller` читает
  выделенный ящик и применяет отчёты через тот же `ProcessProviderStatus`, что и DLR провайдеров (AD-06), —
  поэтому повторное чтение ящика после перезапуска безопасно. `BounceCodec` разбирает и настоящий DSN
  (RFC 3464), и человекочитаемый NDR легаси-шлюза; отчёт, который не удалось сопоставить с сообщением,
  остаётся в ящике уликой, а не угадывается. `BounceCatalog` держит врозь два ответа: статус сообщения
  (`failed` → `UNDELIVERED`, `delivered` → `DELIVERED`, `delayed` — **никак**: «ещё пробую» не исход, и
  перехода ST-01 для него нет, как и у кода 6 SMS Gate) и судьбу адреса — suppression только на
  `5.1.x`/`5.2.1` («ящика нет»). Отказ спам-фильтра по одной формулировке не должен стоить Банку живого
  адреса клиента, и такая потеря невидима, пока клиент не пожалуется, что ему перестали писать.
  Поэтому же `return-path` не переехал в `provider.endpoint_config`: он обязан называть тот же ящик, что
  читает поллер, и оператор, способный сменить одно без другого, молча выключил бы обработку bounce'ов
- ✅ DKIM-подпись при необходимости (конфиг) (EM-03)
  — `DkimSigner`: `relaxed/relaxed` + `rsa-sha256` (RFC 6376), ключ — PKCS#8 из секрет-хранилища (SEC-04),
  выключено по умолчанию. Выключено — это ожидаемое состояние: доставляемость (SPF/DKIM/DMARC) — зона
  почтовой команды, и корпоративный релей обычно подписывает всё, что через него уходит; Hub подписывает
  там, где релей этого не делает. Подписывается сериализованное письмо, а не модель, из которой оно
  собрано: иначе подпись расходится с отправленным на одном перефолженном заголовке, и ошибка всплывает
  у получателя, где её не отладить. Тест проверяет подпись так, как её проверяет принимающий MTA
- ✅ Email-шаблоны, интеграционные тесты (GreenMail) (QA-03)
  — у версии шаблона появилась HTML-альтернатива (`TemplateVersion.Body.html`, миграция V11): EM-01 требует
  `multipart/alternative`, то есть у письма две формы одного текста, и HTML — не отдельный шаблон, а вторая
  форма той же версии, с теми же merge-полями, тем же статусом и тем же ревьюером (FR-4.2). Текст
  обязателен всегда, HTML — только вместе с ним (письмо без plain-части приходит пустым ровно в те клиенты,
  ради которых multipart и существует), HTML вне email-шаблона отклоняется, PAN в HTML отклоняется наравне
  с текстом (SEC-05), колонка `html` появилась в CSV импорта FR-4.6. Заодно исправлен дефект Phase 9:
  `TemplateApplier` затирал присланный системой-источником HTML при рендере по шаблону — теперь шаблон
  побеждает по тем полям, о которых у него есть мнение, а вложения, отправитель и HTML, которого у шаблона
  нет, остаются от отправителя.
  Тесты: 26 unit-тестов (`SmtpMessageCodecTest`, `SmtpResponseCatalogTest`, `BounceCodecTest`,
  `DkimSignerTest`, `AttachmentStoreTest` + дополнения в `MaskingTest`, `MessageValidatorTest`,
  `TemplateVersionTest`, `TemplateUseCasesTest` и новый `TemplateApplierTest`) и два интеграционных набора
  на GreenMail — `SmtpEmailAdapterIT` (6: MIME на проводе, вложение, переиспользование соединений, DKIM,
  троттлинг, недоступный релей) и `EmailBounceIT` (5: hard bounce → suppression, повторный проход,
  задержка, переполненный ящик, обычное письмо в ящике отчётов)

### Phase 12. Push (§16 этап 4) ✅

- ✅ `PushProviderPort`, выбор адаптера по платформе токена (§9.4)
  — порт был с Phase 3; здесь появилось то, что его использует. Выбор адаптера идёт по платформе
  **каждого** токена (`supportsPlatform`), а не по получателю: у одного получателя устройства обеих
  платформ, и «получатель iOS» — не то утверждение, которое можно сделать
- ✅ **FCM** адаптер HTTP v1, OAuth2 service account, автообновление токена (PU-01, PU-02)
  — `adapter/out/provider/fcm` на том же каркасе, что SMS и SMTP, и с тем же контрактом с
  `ProviderCallExecutor`. `FcmAccessTokens` меняет подписанный JWT сервис-аккаунта на access token и
  обновляет его заранее (`refresh-skew`), чтобы обновление приходилось на промежуток между
  сообщениями, а не на сообщение. Библиотеку google-auth не берём: ради одной подписи и одного
  form-post она принесла бы свой транспортный стек, HTTP-клиенту которого нельзя задать таймауты
  Hub'а (PR-01). `project_id` читается из ключа, а не из yaml: два источника одного факта — на один
  больше, чем нужно, а ошибка их расхождения не называет ни один из них
- ✅ FCM поля: `notification`/`data`/`android.priority`/`ttl`/`collapse_key`; классификация ошибок, `UNREGISTERED`→инвалидация токена+событие `push-token.invalidated` (PU-03, PU-04)
  — `FcmMessageCodec` (deep link едет в `data`: цель тапа разбирает приложение Банка, системный
  трей о его экранах не знает) + `FcmErrorCatalog`. Разделение то же, что у SMS Gate: **бросается**
  только то, что говорит о самом FCM (5xx, `UNAVAILABLE`/`INTERNAL`, отказ в кредах), остальное
  **возвращается**. Кампания по десяти тысячам удалённых приложений — это десять тысяч
  `UNREGISTERED`, и ни один из них не говорит о здоровье провайдера. `QUOTA_EXCEEDED`/429 —
  retryable, но не отказ провайдера (Hub шлёт слишком быстро, breaker остановил бы и тот трафик,
  который в лимит укладывается). `SENDER_ID_MISMATCH`/`THIRD_PARTY_AUTH_ERROR` приходят с 401/403,
  но описывают сообщение, а не креды Hub'а, — правило «401 = плохие креды» открыло бы breaker всего
  провайдера из-за одного чужого токена
- ✅ Режим «FCM как единый провайдер для iOS» по конфигурации (PU-05)
  — ключ `ios-delivery` в `provider.endpoint_config`, читается на каждом вызове: перевод iOS-трафика
  между FCM и прямым APNs — маршрутное решение, и оно нужно оператору именно в момент, когда один из
  двух отказывает, а не в следующий деплой (AD-07, FR-2.7). В режиме PU-05 кодек пишет блок `apns`
  (priority/expiration/collapse-id) — без него iOS получил бы умолчания Google, то есть OTP
  «когда телефон сам проснётся»; блок повторяет то, что послал бы прямой адаптер, чтобы смена режима
  не меняла того, что видит клиент
- ✅ **APNs** адаптер HTTP/2, JWT `.p8`, ротация JWT, заголовки `apns-*`, пул соединений, `GOAWAY` (PU-06, PU-07)
  — `adapter/out/provider/apns`. Мультиплексирование и `GOAWAY` даёт JDK-клиент: сотни стримов на
  несколько соединений, при `GOAWAY` соединение дренируется и открывается новое. Своего пула здесь
  нет намеренно — он воспроизвёл бы то, что клиент уже делает, и хуже; смысл PU-07 в том, что путь
  отправки не блокируется на соединении, а это и есть виртуальные потоки плюс мультиплексирование
  (AR-07). `ApnsJwtProvider` подписывает ES256 и пересобирает подпись по расписанию внутри окна
  20–60 минут (Apple отвергает и слишком свежий токен, и слишком старый); интервал вне окна
  приводится к границе, а не роняет старт: цена отказа — выключенный канал push, а правильный
  диапазон известен из спецификации. Единственное неочевидное место — перевод DER-подписи JDK в
  сырую пару `R‖S` формата JWS: с DER Apple отвечает `InvalidProviderToken`, не объясняя почему,
  и на это есть тест, проверяющий подпись так, как её проверяет принимающая сторона.
  `apns-id` — это `messageId` Hub'а (UUIDv7, ровно тот формат, который ждёт Apple): вопрос
  поддержки «что с этим уведомлением» отвечается в обе стороны
- ✅ APNs классификация ошибок, `410`→инвалидация токена (PU-08)
  — `ApnsResponseCatalog`: смысл несёт `reason`, а не статус (400 — это и кривой payload, и токен
  чужого приложения, и только второе снимает адрес). `DeviceTokenNotForTopic` тоже инвалидирует
  токен: для Hub'а это то же самое, что мёртвый токен, и держать его — вызов на сообщение навсегда.
  `ExpiredProviderToken`/`InvalidProviderToken` — blocking и сброс подписи: email-версия этого —
  отказ SMTP-аутентификации, SMS-версия — Playmobile 102
- ✅ Мультитокенность получателя, агрегированный+детальный статус (PU-09)
  — `PushFanOut` в `application/service/support`: сага по-прежнему видит один вызов провайдера и
  один ответ (иначе конвейер перестал бы быть канало-независимым, AR-05), а под ним уходит по
  submission на токен. Агрегация: **принял хоть один — сообщение принято** (клиент уведомлён, и
  провалить сообщение, которое дошло на телефон, потому что на планшете истёк токен, — это ложь
  системе-источнику); если не принял никто, побеждает худший класс (blocking > retryable >
  permanent) — саге нужна самая сильная инструкция. Детальный статус — новая таблица `push_delivery`
  (V12, секционированная, как `delivery_attempt`), строка на устройство. Не колонка в
  `delivery_attempt`: у одной попытки push'а несколько адресов и ответов сразу, а из текста нельзя
  посчитать «сколько iOS-устройств отказало за сутки». Инвалидация токена (PU-04, PU-08) идёт через
  `PushTokenRegistrar` — запись в suppression с новой причиной `PUSH_TOKEN_INVALID` плюс событие в
  `comm.outbound.push-token.invalidated.v1`; идемпотентно по `saveIfAbsent`, потому что кампания
  бьёт в мёртвое устройство раз на сообщение, а топик, повторяющий одну инвалидацию на каждое
  сообщение батча, некому потреблять
- ✅ Массовый push fan-out батчами, изоляция `NOTIFICATION`, ≥500 push/с на инстанс (PU-10)
  — механизм батчей общий (FR-1.6) и изоляция классов трафика тоже (TC-01, с Phase 6): push здесь
  ничего своего не добавляет и не должен. Что добавлено — параллельность внутри одного сообщения:
  устройства опрашиваются одновременно на виртуальных потоках, поэтому получатель с четырьмя
  устройствами стоит латентности самого медленного, а не суммы. Получатель с одним устройством
  (подавляющее большинство) остаётся на вызывающем потоке и за машинерию не платит.
  ⚠️ Сами 500 push/с на инстанс — предмет нагрузочных тестов (Phase 15, QA-05)
- ✅ Валидация payload ≤4КБ (APNs/FCM) на этапе валидации (PU-11)
  — `PushPolicy` (`commhub.compliance.push`) вместо константы в домене: у получателя с четырьмя
  устройствами отказ платформы стоил бы четырёх HTTP-вызовов, чтобы четыре раза узнать одно и то же,
  а отказ конвейера несёт системе-источнику каноническую причину (IR-01). Туда же — потолок числа
  токенов на сообщение (PU-09): заявка с двумястами токенами это рассылка, отправленная через
  единичный endpoint, и честный ответ на неё — отказ с причиной, а не двести молчаливых вызовов
  впереди очереди OTP (TC-01)
- ✅ Push-статус ограничен `SENT_TO_PROVIDER`; `DELIVERED` — фаза 2 по событию приложения (PU-12)
  — принятый ack ведёт в `SENT_TO_PROVIDER` и на этом останавливается: колбэка у push нет, и
  придумывать `DELIVERED` из ответа платформы нельзя — платформа подтверждает приём, а не показ.
  Путь для фазы 2 уже есть: событие приложения через Kafka → `ProcessProviderStatus`, тем же
  маршрутом, что DLR и bounce'ы (AD-06)
- ✅ Sandbox/тестовые токены для тестовой отправки (PU-13)
  — выбор контура APNs делается **на сообщение** по признаку TEST (FR-7.4), а не на деплой: токен
  dev/TestFlight-сборки существует только в sandbox, и перепутанный контур выглядит ровно как мёртвый
  токен. У FCM отдельного контура нет, поэтому тестовая отправка уходит с `validate_only`: токен
  проверяется целиком, а уведомление на телефоне клиента не появляется

> Тесты: 27 unit-тестов (`PushFanOutTest`, `PushTokenRegistrarTest`, `ProviderSubmissionMapperTest`,
> `FcmMessageCodecTest`, `FcmErrorCatalogTest`, `ApnsResponseCatalogTest`, `ApnsJwtProviderTest`,
> `PushTokenEventCodecTest` + дополнения в `MessageValidatorTest` и `PublishOutboxEventsServiceTest`)
> и три интеграционных набора: `FcmPushAdapterIT` (9) и `ApnsPushAdapterIT` (10) на WireMock,
> `PushDeliveryPersistenceIT` (4) на Testcontainers.

### Phase 13. Наблюдаемость, безопасность, эксплуатация ✅

- ✅ Метрики Micrometer→Prometheus по канал/провайдер/поток/класс, латентности этапов, OTP e2e, лаги Kafka,
  состояние CB, квоты (OBS-01) — `adapter/out/metrics`: `MicrometerMetricsAdapter` (реализация `MetricsPort`),
  `CircuitBreakerMetrics` (состояние breaker'ов серией на состояние: `state="OPEN" == 1` читается в алерте, а
  `state == 2` — нет) и `BacklogMetrics` (глубина outbox, **возраст старейшего неопубликованного события** и
  глубина DLQ; счётчики описывают события, а затор — это состояние, его измеряют). Имена и метки — в
  `MetricNames`: это операционный контракт, по нему написаны алерты и дашборды. Лаг консьюмеров и метрики
  продюсера берутся у самих клиентов (`MicrometerConsumerListener`/`MicrometerProducerListener`), гистограммы —
  вместо посчитанных на инстансе перцентилей: p99 по TC-01 считается по деплойменту, а такие перцентили не складываются
- ✅ Distributed tracing OpenTelemetry, `correlationId` в baggage/логах (OBS-02, FR-8.6) — мост
  `micrometer-tracing-bridge-otel` + OTLP подключены в `bootstrap` (runtime, sampling по умолчанию 0.0:
  коллектор есть в контуре Банка, а не локально); `adapter/observability/CorrelationIdFilter` берёт
  `X-Correlation-Id` у источника либо создаёт его, кладёт в MDC и в baggage и возвращает в ответе.
  Заголовок побеждает поле `correlationId` тела IK-03 и не смешивается с ним: тело разбирается на сообщение,
  а батч несёт их много, — заголовок же описывает вызов, о котором и говорят лог-строка и span
- ✅ Structured JSON logs, MDC (messageId/streamId/batchId/correlationId), маскирование PII (OBS-03) —
  структурные логи Boot'а (ECS, `COMMHUB_LOG_FORMAT`), `LogContext` как ресурс (закрытие возвращает прежние
  значения, а не чистит: потоки переиспользуются), MDC открывается вокруг use case'а, а не вокруг лог-строк, —
  тогда всё, что пишет конвейер, ищется по тому же потоку и батчу. `PiiMaskingJsonCustomizer` + `LogMasking` —
  страховка под правилом «маскируем в месте записи»: MSISDN, e-mail (домен переживает: «всё на этот домен
  отбивается» — то, ради чего читают лог bounce'ов) и PAN по Луну (проверка Луна не даёт превратить в звёздочки
  идентификатор или сумму)
- ✅ Алерты: SLA OTP, delivery rate, error rate, CB open, лаг консьюмера, DLQ, квоты, БД/Kafka (OBS-04) —
  `deploy/observability/prometheus-alerts.yaml`, 12 правил с ссылками на разделы runbook'а. Бизнес-правила
  фильтруют `test="false"` (FR-7.4): тестовая отправка не должна ни поднимать алерт, ни гасить его
- ✅ Аутентификация источников: REST mTLS/OAuth2, Kafka SASL/SCRAM+ACL (SEC-01) —
  `adapter/in/rest/security`: цепочки по видам вызывающего (callback'и по SEC-07 внутри контроллера,
  management, admin, `/api/v1`, всё остальное — отказ, а не умолчание), OAuth2 client credentials.
  Требовать ли токен от систем-источников, решает контур (`commhub.security.require-source-system-token`,
  локально выключено и говорит об этом в логе); **админ-панели это не касается — она закрыта всегда**
  (ADR-0037, 10.08.2026). mTLS удалён тогда же: механизм без CA, где пустой allowlist означал «любой
  сертификат называет свой поток». `StreamAccessGuard` — «поток видит только свои данные»: список потоков
  берётся из claim'а токена; отказ — 403, а не 404 (просить чужое, будучи аутентифицированным, — это не
  «не найдено»). Брокерская половина — `KafkaSecurityConfigurer`,
  пароль ссылкой на секрет; читается один раз на старте, потому что клиент Kafka собирает JAAS при создании
- ✅ RBAC на API (метод×ресурс×скоуп), критичные операции с подтверждением+аудит (SEC-03) — `Roles` (шесть
  ролей `app_role` из §10.1), маппинг групп SSO → `ROLE_*` и scope'ов → `SCOPE_*` (это два разных семейства:
  scope описывает, что можно машине, группа — кто такой человек), `@EnableMethodSecurity` для `@PreAuthorize`
  в BFF (Phase 14); критичные операции уже пишут аудит — у kill switch, тестовой отправки и правок
  конфигурации запись в журнал не опциональна
- ✅ Секрет-хранилище + ротация (SEC-04) — сделано в Phase 7 (`adapter/out/secret`); в этой фазе добавлены
  только брокерские креды Kafka (см. SEC-01). 10.08.2026 способ доставки значения изменён: переменные
  окружения вместо каталога секретов (ADR-0036 заменил ADR-0021), из манифеста убран том, вместо него
  `secretKeyRef` в `env`. Ценой стала мгновенность ротации: она теперь требует rolling restart
  (простоя нет, `maxUnavailable: 0`), процедура — в `docs/RUNBOOK.md`
- ✅ Аудит действий пользователей и доступа к ПДн, append-only, экспорт (FR-7.3, SEC-08) —
  `PersonalDataAccess` пишет запись при **чтении** сообщения оператором; опрос своего сообщения системой-
  источником не пишет ничего (это штатный трафик §8.2, и строка на каждый опрос утопила бы журнал, который
  читает аудитор). Читающая сторона — `GetAuditLog`/`AuditQueryService` над новым `AuditQueryPort` с фильтрами
  по сущности, пользователю и периоду; экспорт — та же выборка, пройденная до конца (экспорт, который читает
  иначе, чем экран, не сверить с тем, что видели). ✅ CSV-рендеринг выгрузки приехал с админ-BFF в Phase 14
  (`CsvRenderer`: UTF-8 с BOM и обезвреживание формул), там же — потолок обхода с честным заголовком
- ✅ Тестовая отправка с меткой TEST, без учёта в статистике (FR-7.4) — `SendTestMessage`/`TestSendService`:
  делегирует обычному `SubmitMessage`, потому что проверять надо **конвейер** (квоты потока, фильтры канала,
  креды провайдера, sandbox платформы), а не отдельный путь отправки. Отличаются три вещи: метка TEST
  (она же включает sandbox APNs и `validate_only` FCM, PU-13), свежий dedup-ключ (повтор теста — не дубликат)
  и закреплённый провайдер, если он назван: `MessagePipeline.routeTo` выражает это исключением остальных
  провайдеров канала, поэтому непригодный провайдер даёт обычный `NO_ROUTE_AVAILABLE`, а не особый отказ.
  «Без учёта в статистике» — это **метка, а не выброшенные данные**: `test` стал измерением метрик и полем
  контракта витрины, и панели/алерты фильтруют по нему
- ✅ Выгрузка событий в витрину (Kafka-топик/batch) (FR-6.4) — `ExportDeliveryEvents` + `comm.outbound.events.v1`
  со своей схемой в `resources/schema`. Единица выгрузки — **сообщение, дошедшее до терминального статуса**, а
  не переход: витрина считает объёмы, доставляемость и стоимость, а поток переходов уже публикуется в
  `comm.outbound.status.v1`. Курсор (V13 `export_cursor`) идёт по паре `(terminal_at, id)` — в одну микросекунду
  терминальными становятся несколько сообщений, и курсор по одному времени либо пропускал бы их, либо повторял
  бесконечно; двигается только после подтверждения брокера (at-least-once, дедупликация у витрины по messageId).
  Ни адреса, ни текста в контракте нет: в аналитический контур уходит что отправлено, а не кому (SEC-06)
- ✅ K8s: liveness/readiness/startup, graceful shutdown с дообработкой in-flight (NF-05) — группы actuator'а
  с **разным составом**: liveness — только состояние приложения (рестарт пода из-за недоступной БД меняет
  аварию на crash loop во время неё), readiness — плюс БД (непринятая транзакция = непринятое сообщение),
  startup — БД (пока идут миграции, liveness не должен убивать под). Брокер и провайдеры намеренно снаружи
  групп: для того и существует outbox, а провайдер лежит одинаково на всех подах, — но их индикаторы
  (`KafkaHealthIndicator`, `ProviderAvailabilityHealthIndicator` в `bootstrap/health`) публикуются и питают
  алерты. Graceful shutdown: `server.shutdown=graceful`, дожидание прохода планировщиков, `Dockerfile` и
  `deploy/k8s/deployment.yaml` (rolling update без просадки, PDB, непривилегированный контейнер, креды провайдеров переменными окружения)
- ✅ Grafana-дашборды + runbook (OBS-05, OBS-06) — `deploy/observability/grafana/`: обзорный, по-канальный,
  по-провайдерный и инфраструктурный; `docs/RUNBOOK.md` — по разделу на симптом, с якорями, на которые ссылаются
  аннотации алертов

> Тесты: 25 unit-тестов (`MicrometerMetricsAdapterTest`, `LogMaskingTest`, `CorrelationIdFilterTest`,
> `StreamAccessGuardTest`, `DeliveryEventCodecTest`, `TestSendServiceTest`, `ExportDeliveryEventsServiceTest`,
> `AuditAccessTest`) и два интеграционных набора: `EventExportPersistenceIT` (5) и дополнения к
> `AuditPersistenceIT` (2 теста на поиск и постраничность).
>
> ⚠️ Попутно починены integration-наборы, которые не запускались: контекст `AbstractPersistenceIT` поднимался
> с JDK-прокси вместо CGLIB (и потому не мог собрать `CachingProviderConfigRepository`), `OutboxRelayIT` и
> `InboundKafkaIT` не хватало бинов, а `AuditPersistenceIT` пытался очистить `audit_log`, который в БД
> запрещает `DELETE`/`TRUNCATE` (V7) — тесты переписаны так, чтобы работать с append-only журналом, а не вокруг него.

### Phase 14. Admin REST BFF (backend для будущего frontend) ✅

- ✅ BFF-эндпоинты под все разделы админки (§11.2): дашборд, батчи, сообщения, DLQ, потоки, каналы/провайдеры,
  маршрутизация, шаблоны, suppression, статистика, аудит, администрирование — `adapter/in/admin`, по контроллеру
  на раздел, 56 операций под `/api/admin/v1`. Своя база пути, а не доля `/api/v1`: §8.2 — опубликованный контракт,
  под который интегрируются системы Банка и который нельзя двигать, а это бэкенд одной SPA, которая едет вместе
  с ним; и цепочка безопасности SEC-02 вешается на префикс, а не на список эндпоинтов, который надо не забыть
  дополнить. Карточка сообщения и карточка батча отвечают **тем же телом**, что §8.2: экран, который читает
  оператор в инциденте, и ответ, который опросила система Банка, — это один документ, и «панель показывает другое»
  перестаёт быть возможной фразой. Drill-down от батча к сообщениям — это список сообщений с фильтром `batchId`,
  а не вложенный эндпоинт: та же пагинация, то же маскирование, та же запись SEC-08
- ✅ OIDC-интеграция (Keycloak/AD), проверка ролей на backend (UI-02, SEC-02, FR-7.2) — своя цепочка фильтров
  (`@Order(3)`, между management и API систем-источников) плюс `@PreAuthorize` на каждом эндпоинте.
  Аутентификация на цепочке, авторизация на методе — и нужны обе: цепочка отвечает 401 тому, у кого токена нет,
  метод отвечает 403 тому, чьих ролей не хватает; схлопнуть их значило бы сделать «кто ты» и «можно ли тебе»
  одним ответом. mTLS здесь сознательно не предлагается: сертификат опознаёт машину, а панели нужно знать,
  какой сотрудник смотрит. Выражения SpEL собраны один раз в `AdminAuthority` — опечатка в `@PreAuthorize`
  не является ни ошибкой компиляции, ни падением теста, это эндпоинт, до которого никто не дойдёт (или наоборот).
  Плюс с §11.2 («OPERATOR+») расписан явно: ADMIN не наследует остальные роли неявным порядком.
  Контура без issuer'а не существует (ADR-0037, 10.08.2026): `issuer-uri` обязателен, инстанс без него не
  стартует, а локальный контур поднимает свой Keycloak с демо-администратором. Прежний `@adminAccess.open()`
  в начале каждого выражения удалён вместе с `AdminAccess`; проверяет это `AdminSecurityIT` — 401 без токена,
  403 с чужой ролью, 200 со своей
- ✅ Серверная пагинация/сортировка/фильтры для больших списков (UI-03) — `PageResponse` (items + total + limit +
  offset), потолки страницы живут в query-записях use case'ов, а `AdminPaging` превращает выход за них в отказ
  IR-01, а не в необработанный `Guard` на краю. Пагинация по offset, а не курсором, сознательно: эти экраны
  читает человек, который прыгает на седьмую страницу, сортирует и возвращается — ровно то, в чём курсор плох;
  строки под ним двигаются, и для списка «что было вчера» это не проблема корректности. Единственное место,
  которое этого не терпит, — выгрузка, и она не пагинируется, а проходит тот же запрос до конца.
  **Период — не украшение**: `message` партиционирована по `accepted_at` (DB-02), поэтому экран, открытый без
  периода, был бы сканом всех партиций, которые оставил retention, — а без периода экраны открывают именно
  тогда, когда спешат. `AdminPeriod` подставляет сутки и отклоняет окно шире 92 дней: годовой отчёт — это job,
  а не HTTP-запрос, и узнают об этом обычно по трём таким запросам, держащим пул соединений
- ✅ SSE/polling для дашбордов (UI-03) — `GET /dashboard` и `GET /dashboard/stream` отвечают одним и тем же телом
  из одного use case. Главный здесь **обычный GET**: polling переживает балансировщик, корпоративный прокси и
  заснувший ноутбук, а поток — удобство для вкладки, оставленной на панели на стене; клиент, который не может
  держать соединение, не теряет ничего, кроме push'а. Поток идёт на виртуальных потоках (AR-07), пушит раз в
  15 секунд и пересчитывает период на каждом пуше, а не захватывает его при открытии: «последние сутки»,
  зафиксированные при открытии, за ночь превращаются во «вчера». Дашборд — **один ответ, а не экран из десятка
  вызовов**: его опрашивает каждая открытая вкладка, и десять виджетов были бы десятью запросами на вкладку.
  Алертов в теле нет и не будет: их считает Alertmanager по правилам OBS-04 и знает то, чего не видит запрос
  к БД (переставший скрестись под, недоступный брокер); второй движок алертов разошёлся бы с первым ровно
  тогда, когда это важно. Панель показывает два состояния, на которые отсюда можно повлиять, — health
  провайдеров и kill switch
- ✅ Kill switch, системные параметры (§11.2 Администрирование) — `KillSwitchPort` наконец получил адаптер
  (V14 `kill_switch`), и это был последний порт, блокировавший старт контекста. Состояние в БД, а не в JVM:
  инстансов много, и «остановлено» должно означать одно и то же на всех в пределах секунд (AD-07). Кэш — с тем
  же окном, что у конфигурации маршрутизации, потому что читается это на **каждой** отправке и ещё раз на
  каждой диспетчеризации (FR-1.7, TC-01), а меняется несколько раз в год. Одно отличие от остальных кэшей
  принципиально: нечитаемая таблица **не** откатывается к «ничего не остановлено» — везде в системе неудачное
  чтение конфигурации падает в деплойное умолчание, потому что лимитер не должен становиться причиной остановки
  отправок, а здесь полярность обратная, и угадать «всё в порядке» значило бы возобновить отправку ровно в тот
  инцидент, ради которого рубильник включали. Системные параметры (`system_parameter`) — строка по строке через
  use case с записью «было/стало» в аудит: параметр это рычаг на живом трафике, и прежнее значение больше
  ниоткуда не восстановить. Пользователей и ролей здесь нет, и §11.2 это допускает («или маппинг SSO-групп»):
  Hub не хранит пользователей и паролей (§10.1), личность приходит из корпоративного SSO, а маппинг групп на
  роли `app_role` — деплойная конфигурация, применяемая на токене
- ✅ CSV-выгрузки §11.2 — отчёты (ANALYST+) и журнал аудита (SECURITY_AUDITOR/ADMIN). Выгрузка — **тот же
  запрос**, что на экране, пройденный до конца: выгрузка, которая читает иначе, не сверяется с тем, что видели.
  `CsvRenderer` пишет UTF-8 с BOM (Excel на Windows иначе превращает кириллические причины в кракозябры — файл
  выглядит сломанным для единственных людей, которые его просили) и обезвреживает ячейку, начинающуюся с
  `= + - @` (Excel считает такую ячейку формулой). Ни одна из выгружаемых колонок так начинаться не должна —
  ровно поэтому защита безусловная: день, когда одна начнётся, будет днём, когда кто-то положил это в шаблон
  или в текст отказа. Выгрузка аудита ограничена 50 000 строк и **сообщает об этом** заголовком
  `X-Commhub-Truncated`: необъявленный потолок — это файл, который выглядит полным и не является им, а для
  аудита это единственный режим отказа, который имеет значение. XLSX сознательно не делается: §11.2 просит
  «CSV/XLSX», а CSV с BOM открывается всеми таблицами Банка, и второй бинарный формат — это второй рендеринг
  тех же строк и библиотека, которую надо патчить, ради файла, который окажется в том же месте
- ✅ Импорт CSV: suppression list (§11.2) и каталог шаблонов (FR-4.6). Обе строки идут через тот же use case,
  что и добавление руками, — та же валидация, то же хеширование, та же запись в аудит; плохая строка становится
  сообщённой ошибкой, и остальные всё равно загружаются (файл на четыреста адресов, сконвертированный руками,
  содержит пару плохих строк, и находить их по одной загрузке — самый медленный способ мигрировать). Уже
  существующая запись — «пропущено», а не ошибка: загрузить один файл дважды должно быть безопасно, потому что
  при миграции это случается. RFC 4180-ридер вынесен в `in/importer/CsvRecords` и общий у обоих импортов —
  два самописных CSV-ридера в одной кодовой базе рано или поздно расходятся в трактовке кавычки с переводом
  строки внутри, а SMS-тексты переносятся
- ✅ Маскирование адресов по ролям (§11.2 «Сообщения», DB-04, SEC-06) — `AdminMasking` на выходе. §11.2 отдаёт
  экраны сообщений `OPERATOR+` и `VIEWER` с маскированными адресами, и это утверждение о **степени**, а не о
  доступе: строки видят все, кому экран открыт, полный адрес — те, кому с ним работать. Маскируется на выходе,
  а не в запросе, — запрос обязан сравнивать полные адреса, иначе он ничего не найдёт (DB-05), — и не в
  application-слое, который про роли не знает. Переиспользуется `provider/support/Masking`, чтобы номер
  выглядел в панели так же, как в логе, с которым его сверяют (`99890***4567`)
- ✅ Контракт OpenAPI 3.1 админ-API (`resources/openapi/comm-hub-admin-v1.yaml`) + `AdminOpenApiContractTest`.
  Здесь он важнее, чем у API систем-источников: из этого файла SPA фазы 16 генерирует клиент и типы, поэтому
  отсутствующий в нём эндпоинт — это эндпоинт, который фронтенд не может вызвать. Сам документ не публичен
  (карта всех административных эндпоинтов Hub'а стоит дороже для того, кому её иметь не следует)

> ⚠️ SEC-08 расширен на поиск: список сообщений клиента — это доступ к его данным не меньше, чем одна карточка,
> поэтому `SearchMessages` пишет запись аудита. Одну на поиск, а не на строку: аудитору нужно видеть, что номер
> **искали**, и строка на каждое попадание превратила бы один широкий поиск в сотню отдельных чтений. Сущностью
> такой записи стал **хеш адреса**, а не messageId, и это то, что делает вопрос SEC-08 «кто смотрел на этого
> клиента» одним индексным запросом по `(entity_type, entity_id)`. Открытый адрес в append-only журнал, из
> которого ничего нельзя удалить, не пишется: это был бы второй склад адресов клиентов без истории хранения
> (SEC-06, DB-03), а вопрос аудитора остаётся отвечаемым — хешируй номер и сравни, ровно как уже требует от него
> suppression list.
>
> ⚠️ Jackson 3 отказывается отображать отсутствующее поле на примитив, поэтому опциональные `boolean`/`int` в
> телах запросов объявлены боксированными и получают умолчание в компактном конструкторе. Это не косметика: тело без
> `includeCriticalOtp` — это ровно то, как выглядит снятие рубильника, и оно отклонялось бы как «неразбираемое».
>
> ⚠️ `AnalyticsJdbcClient` — DB-06 наконец используется: тяжёлые чтения панели (список сообщений за месяц
> партиций, отчёт за квартал) идут в реплику, если она настроена, и в primary, если нет. Держатель, а не второй
> бин `JdbcClient`: второй бин сделал бы неоднозначной точку инъекции каждого репозитория модуля. Выбор явный —
> маршрутизация по атрибуту транзакции отправляла бы отчёт в реплику молча и превращала лаг репликации в
> «данные пропали».

> Тесты: 36 unit-тестов (`AdminQueryUseCasesTest`, `SystemParameterServiceTest`, `AdminSupportTest`,
> `MessageAdminControllerTest`, `AdministrationControllerTest`, `AdminOpenApiContractTest`) и 16 интеграционных
> (`AdministrationPersistenceIT` — 7, `MessageSearchPersistenceIT` — 9). Итого по проекту 847 unit и 134
> интеграционных.
>
> ⚠️ Попутно починен флакующий `DeliveryEventCodecTest`: проверка «в витрину не уходит адрес» была написана как
> `doesNotContain("998")` и совпадала с hex случайного UUIDv7 примерно раз на сотню прогонов; теперь проверяются
> имена полей и целый адрес.

### Phase 15. Тестирование и приёмка backend ✅

- ✅ ArchUnit: правила гексагона AR-02/AR-03 (QA-02) — `HexagonalArchitectureTest` (слои, свобода домена от
  фреймворков, самодостаточность пакета провайдера по AR-04: ни один класс снаружи не имеет права называть
  `playmobile`/`smsgate`/`smtp`/`fcm`/`apns`) плюс новый `LayerConventionsTest` — 21 правило о том, где живёт
  класс своего рода: input-порты интерфейсы, команды и запросы записи, реализации use case'ов `…Service` в
  `application.service`, мапперы только MapStruct и только в `mapper/`, advice'ы только в `handlers/`,
  контроллеры `…Controller` в `adapter.in`, конструкторное внедрение (аннотированных полей нет нигде),
  в application нет ни логгера, ни транспорта, домен не читает часы. Правило перестало быть договорённостью
  и стало упавшей сборкой
- ✅ Integration Testcontainers: PostgreSQL, Kafka, WireMock, GreenMail (QA-03) — **полный контекст приложения
  впервые поднимается тестом**: `ApplicationContextIT` (умолчания деплоя + миграция схемы приложением +
  «у каждого порта есть реализация») и `ProviderAdaptersContextIT` (все пять адаптеров §9 включены разом).
  Именно это и было целью: слайсы не видят ни двойной бин, ни неразбираемые `@ConfigurationProperties`.
  Найдено и починено шесть дефектов старта — см. ниже
- ✅ Contract-тесты адаптеров и совместимости Kafka-схем (QA-04) — `OutboundContractCompatibilityTest`
  замораживает набор полей трёх опубликованных контрактов (`status`/`dlq`, витрина FR-6.4, push-token PU-04):
  пара «кодек + схема рядом» переезжает вместе и переименования не ловит, а потребители в Банке ломаются
  (NF-08, BACKWARD). `ProviderDocumentationContractTest` проходит по **всем** кодам §18.1/§18.2: каждый
  документированный код узнан, описан и классифицирован, ни один не проваливается в «undocumented».
  Плюс уже имевшиеся `PlaymobileSmsAdapterIT`/`SmsGateSmsAdapterIT`/`Fcm…`/`Apns…`/`Smtp…` на WireMock и GreenMail
- ✅ Нагрузочные k6: NF-01, TC-01 (QA-05) — `load/k6`: `smoke.js`, `nf01-throughput.js`
  (constant-arrival-rate, порог p99 приёма ≤ 200 мс и доля 202 ≥ 99 %), `tc01-otp-isolation.js` (рассылка
  500 000 чанками по 10 000 **одновременно** с ровным потоком OTP; порог стоит только на классе
  `CRITICAL_OTP` — рассылке замедляться можно, OTP нет). `load/README.md` описывает предусловия контура и
  что прикладывать к приёмке. Взят k6, а не Gatling: сценарий — это HTTP-контракт §8.2, и второй сборочный
  контур ради него не нужен. ⚠️ Прогонов нет: k6 на машине разработки не установлен, цифры снимаются на
  тестовом контуре Банка
- ✅ Chaos (QA-06) — падение инстанса между ack брокера и коммитом, недоступный брокер и конкурентная выборка
  двумя relay закрыты ещё `OutboxRelayIT` (Phase 5). Добавлены: `ProviderFailoverIT` — молчащий основной
  провайдер, сообщение уходит через резервного в пределах бюджета попыток, а после накопления отказов
  `CheckProviderHealth` ставит провайдера `DOWN` и маршрут для следующего сообщения ведёт к резерву (это и
  есть failover ≤ 60 с: монитор ходит раз в 30 с); `InfrastructureOutageIT` — **`docker pause`** настоящих
  контейнеров: замороженная БД даёт отказ приёма вместо тихого 202 и снимает под с балансировщика
  (readiness, NF-05), а после разморозки приём восстанавливается и от отказавшей попытки не остаётся строки;
  замороженный брокер вообще не мешает приёму — события копятся в outbox с `last_error`, и следующий проход
  публикует всё накопленное (ради этого outbox и существует, AD-03)
- ✅ Приёмочные сценарии: OTP + транзакционный + массовый (QA-08) — `AcceptanceScenariosIT` на полностью
  собранном Hub'е: OTP по REST → Playmobile (WireMock по §9.1) → DLR по callback'у → `DELIVERED` → событие
  §6.4 в топике статусов; транзакционный документ IK-03 из Kafka-топика → тот же конвейер, класс трафика от
  топика; массовая рассылка — создание батча, чанк элементов, пауза и возобновление оператором (FR-3.2),
  отправка всех элементов провайдеру. Сценарии повторяются Банком на тестовых контурах Playmobile и SMS Gate —
  стаб доказывает, что свою половину Модуль делает верно

> **Что тесты этой фазы нашли в продукте.** Полный контекст не поднимался ни разу за четырнадцать фаз, и
> первый же его запуск стоил шести исправлений: `streams: {}` и `values: {}` в `application.yml` приходят
> биндеру скаляром и роняли контекст на первом же `@ConfigurationProperties` с картой; read-replica (DB-06)
> включалась **пустым** значением свойства (`@ConditionalOnProperty` считает `""` за «да») и создавала второй
> `DataSource`, после чего Boot переставал конфигурировать первичный `JdbcTemplate`/`JdbcClient`, а её бин
> `JdbcClient` вдобавок подавлял автоконфигурацию первичного — реплика вынесена в собственный тип
> `ReadReplicaClient`; `DeduplicationPolicy`, `SendingPolicy` и `ProviderHealthPolicy` не имели бинов
> (появились `adapter/out/policy` и настройки `commhub.dedup`, `commhub.sending`, `commhub.provider.health.*`);
> `Router`, `FallbackChain` и `SegmentCalculator` не имели бинов — домен без Spring, и объявить их обязан
> сборщик приложения (`bootstrap/config/DomainServiceConfig`); наконец, в Boot 4 автоконфигурация Flyway
> вынесена в отдельный модуль, которого не было на classpath, — то есть `spring.flyway.*` был текстом в yaml,
> а приложение стартовало на непромигрированной базе (DB-01).

> **Два долга, найденных приёмкой и осознанно не закрытых в этой фазе** (это фаза тестирования, а не
> дописывания функциональности):
>
> 1. **`DispatchMessage` некому вызывать.** Сага отправки реализована (Phase 3/7) и порт
>    `MessageRepository.findDispatchable(trafficClass, now, limit)` под неё есть, но планировщика, который
>    забирал бы `QUEUED` и отправлял, в `adapter/in/scheduler` нет — приёмочные сценарии дёргают use case
>    руками, как это делал бы воркер. Без него развёрнутый Модуль принимает сообщения и не отправляет ни
>    одного. То же касается `ExpireMessages` (FR-3.4): порт `findExpired` есть, планировщика нет
> 2. **Счётчики прогресса батча не растут.** `Batch.registerSent/registerDelivered/registerFailed` не
>    вызывает никто — ни сага, ни обработка отчётов провайдера, — поэтому карточка рассылки (FR-3.1) вечно
>    показывает нули во всём, кроме `processed`. Ассерта на них в `AcceptanceScenariosIT` намеренно нет:
>    он был бы ассертом на дефект

> Тесты: 881 unit и 150 интеграционных, все зелёные (`./gradlew build` и `./gradlew integrationTest`).
> Прирост фазы — 23 unit в `bootstrap` (ArchUnit), 11 unit в `adapter` (контракты) и 16 интеграционных
> (`ApplicationContextIT` 5, `ProviderAdaptersContextIT` 4, `AcceptanceScenariosIT` 3, `ProviderFailoverIT` 2,
> `InfrastructureOutageIT` 2).

---

## ЧАСТЬ B. FRONTEND (React SPA админ-панель)

Начинается после готовности backend Admin BFF (Phase 14) и стабилизации контрактов.

### Phase 16. Каркас

- ✅ Vite + React 18 + TypeScript, структура проекта, ESLint/Prettier (UI-01)
- ✅ UI-kit (Ant Design или MUI — согласовать с Банком) (UI-01)
- ✅ i18n RU/UZ/EN (минимум RU), формат дат Asia/Tashkent, хранение UTC (UI-01, UI-04)
- ✅ OIDC-аутентификация (~~Authorization Code + PKCE~~ → форма входа в панели, Phase 21), хранение токена, refresh (UI-02, SEC-02)
- ✅ Гейтинг по ролям RBAC на клиенте (дублирует backend) (FR-7.2)
- ✅ API-клиент к Admin BFF (типы из OpenAPI), обработка ошибок/`Retry-After`
- ✅ Общие компоненты: серверные таблицы (пагинация/сортировка/фильтр), виртуализация, маскирование PII (UI-03, DB-04)

> **Заметки по реализации (Phase 16).** Модуль — `web/` (npm, вне Gradle: фронтенд собирается своим
> конвейером). UI-kit — **Ant Design 5** (согласовано 09.08.2026). Конфигурация развёртывания —
> `public/config.json`, читается до первого рендера: issuer/clientId OIDC, `rolesClaim` (зеркало
> `commhub.security.roles-claim`, по умолчанию `groups`) и маппинг SSO-группа → роль §10.1. Пустой
> issuer — не режим работы, а ошибка настройки контура: панель показывает «не настроена» и внутрь не
> пускает (ADR-0037, 10.08.2026; локально issuer указывает на Keycloak из `docker compose`, вход
> `demo/demo`). Аутентификация — ~~`oidc-client-ts`/`react-oidc-context` (Code + PKCE)~~ форма входа
> в самой панели, direct access grant (Phase 21, ADR-0043); токены в `sessionStorage`, тихое
> продление; роли считаются из
> payload access-токена и гейтят меню и маршруты (`src/auth/roles.ts` — клиентское зеркало
> `AdminAuthority`, разделы §11.2 объявлены один раз в `src/layout/navigation.tsx`). Типы API
> генерируются `openapi-typescript` из `comm-hub-admin-v1.yaml` (`npm run generate:api`,
> результат закоммичен), клиент — `openapi-fetch`: любой не-2xx становится `ApiError` с
> problem+json и разобранным `Retry-After` в одном месте (middleware). Попутно в контракте
> исправлены две строки невалидного YAML (неэкранированное `: ` в description) — джексоновскому
> парсеру контрактного теста они не мешали, стороннему генератору мешали. `ServerTable` —
> серверная пагинация/сортировка + виртуализация по высоте; фильтры принадлежат экрану и
> замыкаются в `fetchPage`. Маскирование PII (`maskMsisdn`/`maskEmail`) зеркалит `Masking.java` —
> для значений, введённых оператором; что видно роли, решает backend (`AdminMasking`). Экраны
> разделов — заглушки: маршруты, меню и гейтинг настоящие, содержимое приходит в Phase 17.

### Phase 17. Разделы (по §11.2)

- ✅ Дашборд: объёмы по каналам, delivery rate, латентность OTP, health провайдеров, активные батчи, алерты; автообновление (все роли)
- ✅ Рассылки (батчи): список/карточка, прогресс, стоимость, пауза/возобновление/стоп, drill-down (OPERATOR+)
- ✅ Сообщения: поиск по externalMessageId/msisdn/correlationId/периоду, таймлайн статусов и попыток (OPERATOR+/VIEWER маскировано)
- ✅ DLQ: просмотр, фильтры, ручной повтор (единично/пакетно), архивирование (OPERATOR+)
- ✅ Входящие потоки: CRUD, интеграция, дефолты, квоты, quiet hours, статус подключения, приостановка (ADMIN)
- ✅ Каналы и провайдеры: CRUD, лимиты, тарифы, fallback, балансировка, вкл/выкл, тестовая отправка, health-история (ADMIN)
- ✅ Маршрутизация: просмотр/редактирование политик, приоритеты, dry-run проверка маршрута (ADMIN)
- ✅ Шаблоны: CRUD, версии, локали, merge-поля, предпросмотр сегментов/стоимости, workflow ревью/публикации (TEMPLATE_MANAGER + вторая роль)
- ✅ Suppression list: просмотр/добавление/удаление, импорт CSV, причины (ADMIN/OPERATOR)
- ✅ Статистика/отчёты: по каналам/провайдерам/потокам/батчам, стоимость, экспорт CSV/XLSX (ANALYST+)
- ✅ Аудит: журнал, фильтры, экспорт (SECURITY_AUDITOR/ADMIN)
- ✅ Администрирование: пользователи/роли (или маппинг SSO-групп), системные параметры, kill switch (ADMIN)

> **Заметки по реализации (Phase 17).** Все двенадцать экранов — `web/src/pages/<раздел>/`,
> маршруты по-прежнему строятся из `navigation.tsx` (карта раздел → экран в `App.tsx`; карточка
> шаблона — отдельный маршрут `/templates/:code` под той же секцией `templateManager`). Общие
> добавления: `shared/labels.ts` (цвета Tag'ов перечислений контракта — сами значения показываются
> как есть, словарь статусов у панели и у API один, §6.3), `PeriodFilter` (пикер в Asia/Tashkent,
> наружу — ISO UTC; пустое значение легально и означает серверный дефолт «последние сутки»,
> AdminPeriod/DB-02), `useReasonPrompt` (одна модалка обоснования на экран → заголовок
> `X-Commhub-Reason`, FR-7.3) и `shared/download.ts` (сохранение CSV-выгрузок; BOM ставит backend).
> Дашборд — polling каждые 15 с (позиция контракта: переживает прокси и заснувший ноутбук, SSE не
> используется); `otpLatencyP99Millis: null` рисуется как «нет OTP-трафика», не нулём; алертов на
> дашборде нет сознательно — их оценивает Alertmanager (решение Phase 14). Плитки/таблицы — Ant
> Design (Statistic/Progress/Table), без графической библиотеки: три канала и счётчики читаются
> таблицей, а не диаграммой. DLQ ведёт собственную таблицу (не ServerTable): очереди нужен выбор
> строк, повтор — только явный список id (позиция контракта), частичный результат показывается как
> applied/skipped. Тестовая отправка и dry-run маршрута маскируют введённый оператором адрес в
> подтверждении (`maskAddress` — зеркало `Masking.java`); что видно роли в списках, по-прежнему
> решает backend (`AdminMasking`). Статистика выгружается только в CSV — XLSX сознательно не
> производится (решение Phase 14); выгрузка аудита показывает предупреждение по заголовку
> `X-Commhub-Truncated`. Администрирование — kill switch (причина обязательна при включении,
> отпускание — `activate: false` без причины), системные параметры (NF-06) и маппинг SSO-групп из
> `config.json` только для чтения: Модуль не хранит пользователей (§10.1). Импорты CSV (шаблоны,
> suppression list) шлют файл как `text/csv` через типизированный клиент. Известные долги Phase 15
> экранам видны и здесь: карточка рассылки честно показывает нули счётчиков прогресса, пока
> `Batch.registerSent/…` никто не вызывает, а без диспетчера `DispatchMessage` сообщения не
> уходят — оба долга закрываются на backend'е, экраны менять не придётся. Проверено:
> `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm run build` — зелёные.

### Phase 18. Тестирование frontend

- ✅ Unit/компонентные тесты (Vitest/RTL) ключевых компонентов
- ✅ E2E Playwright критических сценариев: пауза батча, повтор из DLQ, публикация шаблона, тестовая отправка (QA-07)
- ✅ Проверка доступности и i18n

> **Заметки по реализации (Phase 18).** Два уровня и разные вопросы. **Unit/компонентные** —
> Vitest + Testing Library в jsdom (`web/src/**/*.test.ts(x)`, `npm test`): чистые модули
> (маскирование, время, `parseRetryAfter`/`describeError`, роли и гейтинг §11.2, чтение
> `config.json`) и компоненты, ошибка в которых видна на всех экранах сразу — `ServerTable`
> (limit/offset, сортировка, ошибка с повтором, игнорирование ответа устаревшего запроса),
> `useReasonPrompt` (`null` — передумал, `''` — подтвердил без причины), `RequireSection`, меню
> по ролям, DLQ и тестовая отправка. Admin BFF подменяется заглушкой `src/test/api.ts`
> («GET /dlq» → тело или {status, body}), и она же записывает, что ушло на сервер: тест
> проверяет и запрос, и то, что увидел оператор. Заглушка ставится **до** `initApi` —
> openapi-fetch запоминает `globalThis.fetch` в момент создания клиента. **E2E** — Playwright
> (`web/e2e`, `npm run test:e2e`): четыре сценария QA-07 плюс отказы BFF. Backend сознательно не
> поднимается — BFF подменяется на уровне сети (`e2e/fixtures/adminApi.ts`) с живым состоянием
> (пауза меняет статус в карточке, повтор убирает строку из очереди), панель работает в open
> mode; поведение самого Хаба покрыто его ITs (Phase 15), а здесь проверяется панель.
> **Доступность** — axe-core (`@axe-core/playwright`, WCAG 2.1 A/AA) на пяти экранах; контраст
> исключён (палитра Ant Design — тема, а не наш код), одно замечание antd
> (`aria-allowed-attr` на обёртке Select внутри Form.Item) внесено в явный список известных с
> обоснованием. **i18n** — сверка словарей RU/UZ/EN между собой (набор ключей, непустые
> значения, одинаковые интерполяции) и с ключами, которые просят экраны; отдельно —
> перечисления `shared/labels.ts` против сгенерированной схемы контракта, чтобы новый статус
> §6.3 не выпал из фильтра молча.
>
> Тесты нашли и здесь же исправлены три вещи. **Обоснование FR-7.3 по-русски не уходило вообще**:
> значение HTTP-заголовка — байтовая строка, и `X-Commhub-Reason: разобрано вручную` роняет
> запрос ещё до отправки. Теперь заголовок ставится в одном месте (`shared/reason.ts`,
> percent-encoded UTF-8), а BFF декодирует его один раз на входе
> (`adapter/in/admin/support/ReasonHeaderFilter`, только `/api/admin/v1` — опубликованный
> контракт §8.2 не трогаем); в контракте это записано в описании заголовка. **Доступность**:
> фильтрам-Select'ам и переключателю языка не хватало `aria-label` (у antd placeholder — не
> подпись), `Progress` был progressbar без имени, чекбоксы строк DLQ — без имени; `lang`
> документа теперь следует за выбранным языком, а не остаётся `ru` из `index.html`.
> **Незаполненная форма тестовой отправки** роняла unhandled rejection — `validateFields`
> вынесена из «отправки» в проверку. Проверено: `npm test` (108), `npm run test:e2e` (20),
> `npm run typecheck`, `npm run lint`, `npm run format:check`, `./gradlew build`.

---

## Phase 19. Keycloak и снятие режима «без аутентификации» (10.08.2026)

- ✅ Локальный OIDC-издатель: Keycloak в `docker compose` (`8180`) с экспортом realm'а
  `docker/keycloak/commhub-realm.json` — группы с именами ролей §10.1, публичный клиент
  `commhub-admin` (PKCE S256), демо-администратор `demo/demo` и по пользователю на каждую роль
  (SEC-02)
- ✅ Админ-панель закрыта на любом контуре: цепочка `/api/admin/v1` безусловно `authenticated()`,
  `issuer-uri` обязателен при старте, `AdminAccess` и префикс `@adminAccess.open()` удалены
  ([ADR-0037](architecture/adr/ADR-0037-admin-panel-always-behind-oidc.md))
- ✅ mTLS удалён целиком (цепочка x509, `certificateUserDetailsService`, свойства `mtls-*`) —
  закрывает S2, S3 и S7 [аудита](AUDIT-2026-08-09.md)
- ✅ `commhub.security.oauth2-enabled` → `require-source-system-token`: `/api/v1` по-прежнему решает
  контур, панель — нет (SEC-01)
- ✅ Панель без открытого режима: `OpenSession` и `Session.open` удалены, пустой `authority` —
  экран «не настроена»; попутно починен возврат на запрошенный маршрут после входа
  (`src/auth/returnTo.ts`) — раньше вход по ссылке на `/dlq` заканчивался дашбордом
  (в Phase 21 механизм стал не нужен вместе с редиректом)
- ✅ Тесты: Keycloak третьим синглтоном в `HubTestContainers` (тот же файл realm'а), `KeycloakTokens`
  через direct access grant, `AdminSecurityIT` (401/403/200 и логин в журнале SEC-08 вместо UUID);
  E2E логинятся настоящим `demo/demo` (проектом `setup` Playwright; с Phase 21 — формой панели)

> **Что нашли по дороге.** Пока `@PreAuthorize` никогда не отказывал, два дефекта нечем было
> заметить. Отказ метода — `AccessDeniedException` — выбрасывается внутри диспетчера, а не в
> цепочке фильтров, поэтому доходил до `UnexpectedFailureHandler` и возвращался как **500**:
> проблема прав, отрапортованная как поломка Хаба (теперь `adapter/in/admin/handlers/
> AuthorizationDeniedHandler`). И `StatisticsController.Filters.includeTest` был примитивом, в
> который биндер не может положить отсутствующий query-параметр, — то есть экран статистики
> отвечал 500 на первый же запрос оператора, тот самый, который без фильтров. Обе находки —
> следствие того, что RBAC начал исполняться, а не объявляться.

---

## Phase 21. Форма входа в панели (11.08.2026)

- ✅ Вход — экран самой панели: `LoginPage`, токен через direct access grant к издателю
  (`src/auth/tokenClient.ts`, `src/auth/tokenStore.ts`)
  ([ADR-0043](architecture/adr/ADR-0043-panel-login-form.md), UI-02, SEC-02)
- ✅ Редирект-поток удалён: `/auth/callback`, `src/auth/returnTo.ts`, `oidc-client-ts` и
  `react-oidc-context`; глубокая ссылка переживает вход сама — адрес никуда не уходит
- ✅ Продление за 30 с до истечения, восстановление сессии после F5, выход гасит сессию
  и у издателя (`end_session_endpoint`)
- ✅ Тесты: `src/auth/session.test.tsx`, `src/auth/tokenClient.test.ts`, `e2e/sign-in.spec.ts`;
  проект `setup` Playwright и `storageState` удалены — входит каждый тест (`e2e/fixtures/signIn.ts`),
  форма входа добавлена в axe-проверку

> **Чего это стоило.** Пароль теперь проходит через код панели, `grant_type=password` удалён из
> OAuth 2.1, а контур обязан разрешать direct grant клиенту панели — MFA и федерация в AD этим
> путём не работают. Требование заказчика, цена записана в ADR-0043 вместе с альтернативой,
> к которой следует вернуться, если появится контур с MFA: своя тема оформления Keycloak.
> Backend не менялся ничем — он остаётся resource server'ом с тем же издателем.

---

## Порядок и вехи

1. **Phase 1–3** — каркас + домен + use cases (ядро).
2. **Phase 4–6** — персистентность, outbox/Kafka, входящие адаптеры.
3. **Phase 7–10** — SMS-провайдеры, маршрутизация, шаблоны, фильтры → **MVP SMS готов** (SRS этап 2).
4. **Phase 11** Email → **Phase 12** Push (SRS этапы 3–4).
5. **Phase 13–15** — наблюдаемость/безопасность/тесты (сквозные, ведутся параллельно, финализируются здесь).
6. **Phase 16–18** — frontend после стабилизации Admin BFF.
7. **Phase 19** — Keycloak и снятие открытого режима (после того, как панель стала пригодной к работе).
8. **Phase 21** — форма входа перенесена в панель (требование заказчика, ADR-0043).

> После завершения каждого пункта — отмечать ✅ (не `[x]`).
