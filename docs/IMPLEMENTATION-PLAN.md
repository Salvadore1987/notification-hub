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
  — `adapter/src/main/resources/db/migration/V1__baseline.sql`, схема `comm_hub`, тест `FlywayMigrationIT`
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
  — пакет `model/type`; дополнительно `ChannelSelectionMode`, `BalancingStrategy`, `ChannelStatus`, `StreamStatus`, `ConnectionStatus`, `IntegrationType`,
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
> `CustomerPreferencePort` (заглушкой, как предписывает FR-8.2), после Phase 12 — `PushDeliveryLogPort`; контекст
> всё ещё не стартует целиком — ждут своих фаз `MetricsPort` (Phase 13), `KillSwitchPort` (Phase 14) и
> `ProviderProbePort` (задел, для SMS реализации не будет).

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
- [ ] Порт `KillSwitchPort` — таблицы под него в §10.1 нет, реализуется вместе со своей функциональностью (Phase 14)

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
  значения как явные `null`), схема `adapter/src/main/resources/schema/comm.outbound.status.v1.json`
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
- ✅ OpenAPI 3.1 (IR-03) — `adapter/src/main/resources/openapi/comm-hub-api-v1.yaml`, отдаётся по
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
  — `adapter/out/secret`: схемы `env:`/`file:`/`prop:`, без схемы — файл в смонтированном каталоге секретов
  (проверка выхода за каталог), кэш с TTL ⇒ ротация без рестарта. В Vault Hub не ходит сам: токен Vault — ещё один
  секрет, а реестр на пути каждой отправки — ещё одна точка отказа; секреты рендерит Vault-agent в том пода.
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
  ⚠️ REST/BFF-эндпоинты — Phase 14, вместе с остальной админкой (§11.2); use case'ы под них готовы
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

### Phase 13. Наблюдаемость, безопасность, эксплуатация

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

### Phase 14. Admin REST BFF (backend для будущего frontend)

- [ ] BFF-эндпоинты под все разделы админки (§11.2): дашборд, батчи, сообщения, DLQ, потоки, каналы/провайдеры, маршрутизация, шаблоны, suppression, статистика, аудит, администрирование
- [ ] OIDC-интеграция (Keycloak/AD), проверка ролей на backend (UI-02, SEC-02, FR-7.2)
- [ ] Серверная пагинация/сортировка/фильтры для больших списков (UI-03)
- [ ] SSE/polling для дашбордов (UI-03)
- [ ] Kill switch, системные параметры (§11.2 Администрирование)

### Phase 15. Тестирование и приёмка backend

- [ ] ArchUnit: правила гексагона AR-02/AR-03 (QA-02)
- [ ] Integration Testcontainers: PostgreSQL, Kafka, WireMock (Playmobile/SMS Gate/FCM/APNs), GreenMail (QA-03)
- [ ] Contract-тесты адаптеров и совместимости Kafka-схем (QA-04)
- [ ] Нагрузочные Gatling/k6: NF-01 (≥100 msg/s, батч 1млн + OTP), TC-01 (батч ≥500k без деградации OTP) (QA-05)
- [ ] Chaos: падение инстанса, недоступность провайдера (failover ≤60с), БД/Kafka (QA-06)
- [ ] Приёмочные сценарии: OTP-поток + транзакционный + массовый на тестовых контурах (QA-08)

---

## ЧАСТЬ B. FRONTEND (React SPA админ-панель)

Начинается после готовности backend Admin BFF (Phase 14) и стабилизации контрактов.

### Phase 16. Каркас

- [ ] Vite + React 18 + TypeScript, структура проекта, ESLint/Prettier (UI-01)
- [ ] UI-kit (Ant Design или MUI — согласовать с Банком) (UI-01)
- [ ] i18n RU/UZ/EN (минимум RU), формат дат Asia/Tashkent, хранение UTC (UI-01, UI-04)
- [ ] OIDC-аутентификация (Authorization Code + PKCE), хранение токена, refresh (UI-02, SEC-02)
- [ ] Гейтинг по ролям RBAC на клиенте (дублирует backend) (FR-7.2)
- [ ] API-клиент к Admin BFF (типы из OpenAPI), обработка ошибок/`Retry-After`
- [ ] Общие компоненты: серверные таблицы (пагинация/сортировка/фильтр), виртуализация, маскирование PII (UI-03, DB-04)

### Phase 17. Разделы (по §11.2)

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

### Phase 18. Тестирование frontend

- [ ] Unit/компонентные тесты (Vitest/RTL) ключевых компонентов
- [ ] E2E Playwright критических сценариев: пауза батча, повтор из DLQ, публикация шаблона, тестовая отправка (QA-07)
- [ ] Проверка доступности и i18n

---

## Порядок и вехи

1. **Phase 1–3** — каркас + домен + use cases (ядро).
2. **Phase 4–6** — персистентность, outbox/Kafka, входящие адаптеры.
3. **Phase 7–10** — SMS-провайдеры, маршрутизация, шаблоны, фильтры → **MVP SMS готов** (SRS этап 2).
4. **Phase 11** Email → **Phase 12** Push (SRS этапы 3–4).
5. **Phase 13–15** — наблюдаемость/безопасность/тесты (сквозные, ведутся параллельно, финализируются здесь).
6. **Phase 16–18** — frontend после стабилизации Admin BFF.

> После завершения каждого пункта — отмечать ✅ (не `[x]`).
