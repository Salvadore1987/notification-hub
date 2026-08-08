# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

**Phase 1–5 done (scaffolding + domain model + application ports/use cases + PostgreSQL persistence + transactional outbox relay → Kafka), Phase 6 next (inbound REST/Kafka adapters).** `domain/` holds the full model (`model/{vo,type,content}`, aggregates, `service/{SegmentCalculator,Router,FallbackChain}`, `support/{Guard,UuidV7}`) with 276 unit tests at 97% line coverage; `application/` holds `port/in` + `port/in/command`, `port/out` (+`port/out/provider`), `service` (use cases) with `service/{pipeline,support}`, `dto/`, `mapper/`, `policy/`, 64 unit tests at 86%; `adapter/` holds `out/persistence` (`config`, `messaging`, `delivery`, `template`, `audit`, `json`, `crypto`, `support`), `out/kafka`, `out/time` and `in/scheduler`; 44 unit tests plus six Testcontainers suites tagged `integration`.

Domain naming to keep straight: the `Channel` aggregate of spec §6.1 is the class `ChannelConfig` (the name `Channel` belongs to the channel enum), and a message carries `MessageContents` — content per channel (MP-02) — instead of a single `MessageContent`. Aggregates take instants as parameters; the domain never reads a clock — the application layer gets "now" from `ClockPort`.

Application-layer conventions worth knowing before adding to it: use case interfaces are named after the use case (`SubmitMessage`, `DispatchMessage`) and their implementations `…Service`; a rejection is never an exception but a verdict/result record carrying a `RejectionReason` (IR-01) — `NotFoundException` is the only business-ish exception; `MessagePipeline` is the single entry point to the stages (dedup → template → validate → route → filters → quotas) so a single message, a batch item and a DLQ retry all traverse the same path; use cases append to `OutboxPort` and never publish directly (AD-03). Every record stays at ≤ 8 components — Checkstyle's `ParameterNumber` covers record headers too. Spring beans are wired by component scan; the persistence ports and `ClockPort` are implemented since Phase 4 and `StatusPublisherPort` since Phase 5, but `SecretResolverPort` (Phase 7), `FrequencyCounterPort` (Phase 10), `MetricsPort` (Phase 13), `KillSwitchPort` (Phase 14) and `CustomerPreferencePort` still have none, so the Spring context does not start yet (unit tests, ArchUnit and the ITs do not need it).

- `docs/sms-notification-hub-spec.md` — the SRS (v1.0, RU). **Authoritative.** Read the relevant section before scaffolding anything; requirement IDs (`FR-*`, `AR-*`, `AD-*`, `PM-*`, `PU-*`, `SG-*`, `QA-*`…) are referenced throughout this file and in the plan.
- `docs/IMPLEMENTATION-PLAN.md` — the working checklist (RU), backend-first (Phase 1…15) then frontend (Phase 16…18), each item tagged with its SRS requirement ID. **This is the task board** — when asked "what's next", read it. Mark completed items with ✅ (never `[x]`), per the rule below.
- `CONTRIBUTING.md` — environment, local stack, branching/PR rules.

Frontend (Phase 16+) has not started: no `web/` module yet.

Delivery stages (spec §16): 1) detailed design → 2) **MVP: core pipeline + SMS** (Playmobile + SMS Gate, REST/Kafka ingress, basic admin) → 3) Email → 4) Push → 5) extensions. Don't build stage-3/4 concerns into stage-2 code paths beyond the ports that already exist.

## Build, test, run

Gradle wrapper 9.7.0; Java 25 toolchain (Gradle downloads JDK 25 via foojay if it is not installed locally — the machine's default JDK may be newer).

```bash
./gradlew build                     # compile + spotlessCheck + checkstyle + unit tests + ArchUnit
./gradlew spotlessApply             # auto-format (palantir-java-format) — run before committing
./gradlew integrationTest           # tests tagged "integration" (Testcontainers; needs Docker)
./gradlew :bootstrap:bootRun        # run the app (needs docker compose up first)
./gradlew :bootstrap:bootJar        # -> bootstrap/build/libs/notification-hub.jar
./gradlew :domain:test              # single module

docker compose up -d                # PostgreSQL 5432, Kafka 9092, Schema Registry 8081,
                                    # WireMock 8089, GreenMail 3025/3143/8085
docker compose down -v
```

Tests tagged `integration` are excluded from `test` and only run via `integrationTest` — tag every Testcontainers/WireMock test with `@Tag("integration")` and name it `*IT`. There is no CI pipeline in the repo (removed deliberately): run `./gradlew build` and `./gradlew integrationTest` locally; SAST/dependency scanning (SEC-09) belongs to the Bank's own pipeline.

Versions live in `gradle/libs.versions.toml` (Spring Boot 4.1.0, MapStruct, ArchUnit, Resilience4j, Spotless, Checkstyle); everything else is managed by the Spring Boot BOM — do not pin versions in module build files. Checkstyle config: `config/checkstyle/checkstyle.xml` (it mechanically enforces the no-`var` and constructor-injection rules); suppressions in the adjacent `suppressions.xml`.

Flyway migrations: `adapter/src/main/resources/db/migration`, schema `comm_hub`, `V<n>__<snake_case>.sql`. `FlywayMigrationIT` checks the schema still builds from scratch (DB-01).

Outbox relay (Phase 5): use cases only ever `OutboxPort.append` inside their transaction; `PublishOutboxEventsService` claims rows with `FOR UPDATE SKIP LOCKED`, publishes through `StatusPublisherPort`, and sets `published_at` **only after the broker acked** — never reorder those two. A publication failure stops the pass (status order per message beats throughput) and leaves `attempts`/`last_error` on the row. `adapter/out/kafka` owns the producer (`acks=all`, idempotent) and `StatusEventCodec`, which writes the flat §6.4 JSON field by field because that record would exceed 8 components; its field list must stay in step with `resources/schema/comm.outbound.status.v1.json` (a test asserts it). `adapter/in/scheduler` only ticks the use case. Kafka settings live under `commhub.kafka`, not `spring.kafka` — Boot 4's Kafka autoconfig module is not on the classpath.

Message content is encrypted at rest (DB-04): `persistence/crypto` holds `ContentCipher` (AES-256-GCM, app-level rather than pgcrypto) and `ContentCodec`, and `message.contents` / `message.template_variables` go through it — stored as the JSON scalar `"CH1.<keyId>.<base64url>"`, with cleartext still readable. `recipient` deliberately stays in clear (GIN index, DB-05). Encryption is on by default and a missing `commhub.persistence.encryption.keys.<activeKeyId>` fails startup — never "fix" that by disabling it.

Persistence conventions (Phase 4): adapters are `…PersistenceAdapter` over `JdbcClient` with hand-written row mappers — the domain carries no persistence annotations (AR-02) and is rebuilt through its factories or `rehydrate` builders, which no entity mapper can drive (rationale in `adapter/out/persistence/package-info.java`). `jsonb` columns go through the module's own `JsonCodec` (Jackson 3, `tools.jackson.*` since Boot 4) and explicit `…Json` records, never the shared REST `ObjectMapper`. `message`, `message_status_history`, `delivery_attempt` and `outbox_event` are monthly range partitions maintained by `comm_hub.ensure_partitions` / `detach_partitions_before` and `PartitionMaintenanceJob`; every one has a DEFAULT partition as a safety net, and a non-empty one means the scheduler failed. Instants are bound as `OffsetDateTime` at UTC — the driver has no mapping for `Instant`.

## What this system is

**Notification Hub** — a centralized transport & orchestration layer for the Bank's client/service notifications across **SMS, Email, Push** (extensible to Inapp/Stories/WhatsApp/Telegram/Viber/RCS/Web Push). It is a **transport and orchestration layer only**: it does NOT build audiences, content, or campaign logic — source systems submit already-formed messages/batches via **Kafka (primary)** or **REST (synchronous, incl. OTP)**. The Hub does routing, load-balancing, fallback, templating, filtering (suppression / quiet hours), delivery via provider adapters, status aggregation, retries/DLQ, stats, and an admin panel.

## Non-negotiable architectural constraints (from the spec)

These are hard requirements, not preferences. Verify against the spec section before deviating.

- **Hexagonal architecture (Ports & Adapters)** enforced by ArchUnit in `./gradlew build` (AR-01…AR-06). Dependencies point inward only: `adapter → application → domain`.
- **`domain/` has zero compile deps on Spring, JPA, Kafka, Jackson** (AR-02). Only JDK, `java.time`, and own value objects.
- **Multichannel via the Message pattern** (spec §5): one canonical `Message` (channel-independent envelope) + a `sealed MessageContent` hierarchy (`SmsContent`, `EmailContent`, `PushContent`). The pipeline (validate → dedup → route → filter → template → send → status) is single and channel-agnostic.
- **Adding a provider = new adapter only** — no changes to `domain/` or `application/` (AR-04). Each provider implements a channel output port: `SmsProviderPort`, `EmailProviderPort`, `PushProviderPort` (MP-05, AR-04).
- **Adding a channel** = new content specialization + new channel port + provider adapters + DB config; pipeline core unchanged (AR-05).
- **Delivery guarantee**: Transactional Outbox + at-least-once + idempotency by dedup key (AD-03). Consumers must be idempotent.
- **Traffic-class isolation** (`CRITICAL_OTP` / `TRANSACTIONAL` / `NOTIFICATION`): separate Kafka topics, thread pools, and rate limits. Bulk `NOTIFICATION` load must never breach the OTP SLA (p99 ≤ 5s accept→provider; TC-01).
- **Canonical status model** (spec §6.3): provider statuses map onto canonical statuses in the adapters (mapping tables in §18.1 Playmobile, §18.2 SMS Gate).
- **Routing/provider config lives in PostgreSQL**, editable via admin panel, applied **without restart** (≤30s, AD-07/NF-07).
- **Virtual Threads (Java 25 Loom)** for mass provider I/O; blocking HTTP clients are fine, no reactive stack (AR-07).
- **Resilience4j** for retry (exp backoff + jitter) + circuit breaker + automatic failover/failback per provider (PR-01, FR-6.3).

## Target stack (spec §3.1)

Java 25 (LTS) · Spring Boot 4.x / Spring Framework 7 · PostgreSQL 16+ (time-partitioned) · Apache Kafka · React 18+/TypeScript (Vite, Ant Design or MUI) admin SPA · Flyway/Liquibase · Micrometer + OpenTelemetry + Prometheus/Grafana · Docker + K8s/OpenShift · OIDC SSO (Keycloak/AD) + internal RBAC.

## Module layout (spec §4.2, AR-01)

Four Gradle modules exist (`settings.gradle.kts`); base package `uz.hamkorbank.commhub`, so e.g. SMS provider adapters go to `adapter/src/main/java/uz/hamkorbank/commhub/adapter/out/provider/playmobile/`. Module dependencies are already wired (`bootstrap → adapter → application → domain`) — add packages, not modules.

```
notification-hub/
├── domain/          # pure model + domain services (Router, FallbackChain, SegmentCalculator). No frameworks.
├── application/      # port/in (use cases), port/out (repos/providers/publishers), orchestration, outbox saga
├── adapter/
│   ├── in/  rest (source systems v1) · kafka (inbound consumers) · admin (BFF) · callback (provider DLR webhooks)
│   └── out/ persistence (Spring Data + outbox) · kafka (status/DLQ) · provider/{playmobile,smsgate,smpp,smtp,apns,fcm} · notification
└── bootstrap/        # Spring Boot app, config, wiring
```

Use cases (input ports) are interfaces taking explicit `Command`/`Query` records and returning `Result` records; REST/Kafka adapters only translate transport DTO ↔ command (AR-06).

## Key domain facts to keep straight

- **Kafka inbound topics** split by traffic class: `comm.inbound.critical.v1` / `.transactional.v1` / `.notification.v1` / `.batch-control.v1`. Outbound: `comm.outbound.status.v1`, `comm.outbound.dlq.v1`, parse-errors → `comm.inbound.parse-error.v1` (§8.1).
- **Idempotency**: `(streamId, externalMessageId)` or `dedupKey` within the dedup window (default 24h) → returns `DUPLICATE`, no resend (FR-1.5).
- **SMS segmentation** (`SegmentCalculator`, §18.3): GSM-7 = 160/153 chars, UCS-2 = 70/67; any non-GSM char forces the whole message to UCS-2; `^ { } \ [ ~ ] | €` count as 2 in GSM-7. Segment count drives cost and quotas.
- **MSISDN format** is strictly `9989xxxxxxxx` (no `+`, no spaces) for Playmobile.
- **Playmobile** `message-id` ≤ 20 chars, generated by the Hub, stored on `DeliveryAttempt`. Error-code classification (retryable / non-retryable / blocking→circuit-breaker) in §18.1; code 102 (Account lock) trips the breaker.
- **Push** has no delivery receipt from APNs/FCM → terminal canonical status is `SENT_TO_PROVIDER`; `UNREGISTERED`/`410` invalidates the token and emits `push-token.invalidated`.
- **PCI DSS**: content validator must reject/alert on full PAN (Luhn detector); no PAN in SMS. PII masked in logs/UI (`99890***4567`).
- **Campaigns without recipient lists (FR-8.11) are OUT of scope** (deferred 06.08.2026). Only an unimplemented `AudienceResolverPort` is reserved in the core. Bulk sends go through the normal batch mechanism with recipient lists.

## Global engineering rules (apply to all Java code here)

- No `var`; constructor injection only; UUIDv7 for primary keys; AAA pattern in tests.
- **Separate classes/packages for the four app-layer concerns** — never collapse: service/use case (orchestration, no mapping logic) · input `XxxCommand`/`XxxQuery` records · output DTO records (in `dto/`) · **MapStruct mappers only** (`@Mapper(componentModel="spring")`, in `mapper/`). MapStruct is mandatory — add `org.mapstruct:mapstruct` + processor before the first mapper.
- `@ControllerAdvice`/`@RestControllerAdvice` live in a dedicated `handlers/` package, one class per concern.
- Delegate: Java tests → `jvm-test-architect` agent; architecture → `solution-architect`; JMS/messaging → `jms` skill; Java+Spring → `spring-java-architect`. New service/entity/ports scaffolding → `hexagonal-ddd` skill.
- Mark finished TODO items with ✅ (not `[x]`) — this applies to `docs/IMPLEMENTATION-PLAN.md`.
- Project docs are written in Russian; code, identifiers, log messages, and API contracts in English.

## Testing expectations (spec §15)

Domain/use-case unit coverage ≥80% lines / ≥90% for critical logic (routing, dedup, status machine, segmentation). ArchUnit tests for hexagonal rules. Integration tests via Testcontainers (PostgreSQL, Kafka) + WireMock stubs for Playmobile/SMS Gate/FCM/APNs and GreenMail for SMTP. Contract tests, Gatling/k6 load tests (NF-01, TC-01), chaos tests (no loss/dup on instance kill), Playwright E2E for admin.