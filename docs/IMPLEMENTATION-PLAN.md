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
> После Phase 4 закрыты порты персистентности и `ClockPort`, после Phase 5 — `StatusPublisherPort`; контекст всё ещё
> не стартует целиком — ждут своих фаз `SecretResolverPort` (Phase 7), `FrequencyCounterPort` (Phase 10),
> `MetricsPort` (Phase 13), `KillSwitchPort` (Phase 14), `CustomerPreferencePort` (фаза 2 по SRS).

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
- [ ] Порты `KillSwitchPort` и `FrequencyCounterPort` — таблиц под них нет в §10.1, реализуются вместе со своей функциональностью (Phase 10 и Phase 14)

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
  `commhub.rest.rate-limit`, пер-стримовые переопределения переедут в реестр потоков Phase 8 (AD-07)
- ✅ Провайдерский Callback API (webhook) — `adapter/in/callback`: `POST /api/callbacks/{providerCode}`,
  `CallbackGuard` (IP allowlist + общий секрет, сравнение секрета в постоянном времени, конфигурация на
  провайдера), ответ на отказ — голый 403 без причины. Идемпотентность обеспечивает `ProcessProviderStatus`
  (AD-06): отчёт, ничего не изменивший, тоже отвечает 200, иначе провайдер будет ретраить бесконечно.
  ⚠️ Сами трансляторы payload'ов (`ProviderCallbackTranslator`) приходят с адаптерами провайдеров в Phase 7 —
  до этого настроенный провайдер без транслятора получает 404
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

### Phase 8. Маршрутизация, каналы, провайдеры, квоты (конфигурация в БД)

- [ ] CRUD провайдеров/каналов, основной+резервные, порядок fallback (FR-2.1, FR-2.2)
- [ ] Балансировка round-robin/вес/least-cost на уровне канала/потока (FR-2.3)
- [ ] Дефолтные канал/провайдер/класс на входящий поток (FR-2.4, TC-02)
- [ ] Квоты/бюджеты (кол-во и стоимость) по канал/провайдер/поток день/месяц, поведение при исчерпании (FR-2.6)
- [ ] Вкл/выкл провайдера и канала «на лету», режим обслуживания (FR-2.7)
- [ ] Горячее применение конфигурации без рестарта ≤30с (AD-07, NF-07)
- [ ] Декларативные `routing_policy` (match/action/priority); dry-run «какой маршрут получит сообщение X» (FR-8.9 задел)
- [ ] Health-check провайдеров + авто failover/failback (PR-02, FR-6.3)

### Phase 9. Шаблоны и persoнализация

- [ ] CRUD шаблонов, версии, локали RU/UZ/EN, статусы DRAFT→ON_REVIEW→PUBLISHED→ARCHIVED (FR-4.1)
- [ ] Maker/checker: автор не публикует свой шаблон (FR-4.2)
- [ ] Merge-поля, строгий режим валидации (FR-4.3)
- [ ] SMS-предпросмотр: расчёт сегментов и стоимости по тарифам (FR-4.4)
- [ ] Маппинг на провайдерские шаблоны (Playmobile `template-id`), статус согласования (FR-4.5)
- [ ] Миграция существующей базы (~470 шаблонов) — скрипт импорта (FR-4.6)

### Phase 10. Фильтрация и compliance

- [ ] Suppression list: проверка перед отправкой, причины, API+админка, результат `REJECTED/SUPPRESSED` (FR-5.1)
- [ ] Учёт opt-in/opt-out для нетранзакционных классов (FR-5.2)
- [ ] Quiet hours (Asia/Tashkent, localtime по признаку): отложить/отклонить (FR-5.3)
- [ ] Frequency capping для `NOTIFICATION` (в MVP счётчики+алерты) (FR-5.4)
- [ ] PCI: детектор PAN по Луну, reject/alert, запрет PAN в SMS (SEC-05)
- [ ] Email hard bounce → авто-добавление в suppression (EM-02)

### Phase 11. Email (§16 этап 3)

- [ ] `EmailProviderPort` → SMTP-адаптер: STARTTLS/TLS, пул соединений, лимит скорости (EM-01)
- [ ] HTML+plain (multipart/alternative), вложения с лимитом, заголовок `X-Comm-Message-Id` (EM-01)
- [ ] Bounce-обработка: IMAP-poller / DSN → suppression (EM-02)
- [ ] DKIM-подпись при необходимости (конфиг) (EM-03)
- [ ] Email-шаблоны, интеграционные тесты (GreenMail) (QA-03)

### Phase 12. Push (§16 этап 4)

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
