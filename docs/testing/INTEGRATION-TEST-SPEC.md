# Сквозное интеграционное тестирование: что проверяется и как

Документ описывает набор интеграционных тестов, доказывающих, что **путь сообщения работает целиком и одинаково через оба входа** — REST (§8.2) и Kafka (§8.1). Источник требований — SRS [`../sms-notification-hub-spec.md`](../sms-notification-hub-spec.md), раздел §15 (QA-01…QA-08).
**Спецификация авторитетна**: при расхождении прав SRS, а расхождение здесь является дефектом документации.

Сами кейсы — в [`TEST-CASES.md`](TEST-CASES.md); этот файл отвечает на вопросы «где граница», «на каком контуре», «почему тест не мигает» и «что считать приёмкой». Читать в паре: кейс без контура невоспроизводим, контур без кейсов ничего не доказывает.

**Легенда статусов:** ✅ покрыто и зелёное · 🟡 покрыто частично · ⏳ теста нет · ⚠️ оговорка внутри готового пункта.

---

## 1. Границы набора (SRS §15)

Набор проверяет **сквозной путь сообщения** — от документа, положенного системой-источником в REST или Kafka, до статусного события в `comm.outbound.status.v1`:

```
источник ──► adapter/in/rest ──┐
                               ├─► InboundMessageCodec ─► MessagePipeline ─► Router/FallbackChain
источник ──► adapter/in/kafka ─┘        (дедуп → шаблон → валидация → маршрут → фильтры → квоты)
                                                                       │
                          ProviderGateway ◄── MessageDispatchScheduler ─┘
                                 │
              провайдер ◄────────┤                        outbox_event ──► comm.outbound.status.v1
                                 │                                    └──► comm.outbound.dlq.v1
              DLR ──► adapter/in/callback ──► ProcessProviderStatus ──┘└──► comm.outbound.push-token.invalidated.v1
```

### Что внутри границы

| Область | Модули |
|---|---|
| Приём и разбор контракта IK-03 | `:adapter:in:rest`, `:adapter:in:kafka`, `:adapter:in:contract` |
| Конвейер | `application/service/pipeline`, `application/service/support` |
| Маршрутизация и отказоустойчивость | `domain/service/{Router,FallbackChain}`, `ProviderHealthService` |
| Отправка и сага | `:adapter:in:scheduler`, `DispatchPreparation`/`DispatchSettlement`, `:adapter:out:provider:*` |
| Обратная связь провайдера | `:adapter:in:callback`, `EmailBouncePoller`, `MockDeliveryReports` |
| Исходящие контракты | `PublishOutboxEventsService`, `:adapter:out:kafka` |

### Что снаружи и почему

| Вне набора | Чем закрыто |
|---|---|
| Разделы админ-панели §11.2 (`:adapter:in:admin`) | `AdminSecurityIT`, `TemplateWorkflowIT`; экраны — QA-07, Playwright в `web/e2e/` |
| CSV-импорты шаблонов и подавлений (`:adapter:in:importer`) | собственные тесты модуля |
| Экспорт в аналитический контур (`ExportDeliveryEventsService`, `comm.outbound.events.v1`) | `EventExportPersistenceIT`; по умолчанию выключен (`commhub.export.events.enabled: false`) |
| Нагрузка (NF-01, TC-01 в цифрах) | QA-05, [`../../load/`](../../load/) |
| Frontend | QA-07, [`../frontend-spec/09-testing.md`](../frontend-spec/09-testing.md) |

Админ-эндпоинты используются как **способ подготовить контур** (завести поток, опубликовать шаблон), но сами предметом кейсов не являются: их отказы и права проверяет другой набор.

### Чего набор не заменяет

Уровневые тесты остаются и предполагаются зелёными: семейство `AbstractPersistenceIT` (12 классов — SQL, партиции, шифрование, курсоры), контрактные IT провайдеров (`PlaymobileSmsAdapterIT`, `SmsGateSmsAdapterIT`, `SmtpEmailAdapterIT`, `EmailBounceIT`, `FcmPushAdapterIT`, `ApnsPushAdapterIT`), `OutboxRelayIT`, `InboundKafkaIT`, ArchUnit и `OutboundContractCompatibilityTest`. Сквозной набор не дублирует их таблицы кодов ошибок и не проверяет заново форму HTTP-запроса к провайдеру — он проверяет, что **решение, принятое одним слоем, доезжает до соседнего**.

---

## 2. Эталонный контур

Тесты этого набора сеются один раз на JVM через `HubConfiguration` (`bootstrap/src/test/java/uz/hamkorbank/commhub/support/HubConfiguration.java`) — **через репозитории, а не SQL**: конфигурация, записанная в обход агрегата, проверяет схему, а не Модуль.

Сегодняшний сидер заводит три потока, два SMS-провайдера, один канал и одну политику — SMS и только SMS. Набор требует контура, на котором заявленный функционал вообще можно поставить.

### 2.1. Потоки

Отдельный поток на сценарий вместо переконфигурирования одного: так кейсы не мешают друг другу, и не нужен глобальный сброс конфигурации между классами.

| `streamId` | Класс трафика | Особенность | Для кейсов |
|---|---|---|---|
| `ibank-otp` | `CRITICAL_OTP` | без окон и квот | `IT-TC`, `IT-FLT` (обход тихих часов), `IT-DSP` |
| `core-banking` | `TRANSACTIONAL` | без окон | `IT-ING`, `IT-DED`, `IT-STS` |
| `marketing-bulk` | `NOTIFICATION` | тихие часы 21:00–09:00 `Asia/Tashkent`, `REJECT` | `IT-FLT` |
| `marketing-defer` | `NOTIFICATION` | те же часы, `DEFER` | `IT-FLT`, `IT-DSP` |
| `quota-day` | `NOTIFICATION` | суточная квота 3 сообщения, `BLOCK_AND_ALERT` | `IT-QTA` |
| `quota-alert` | `NOTIFICATION` | суточная квота 1, `ALERT_ONLY` | `IT-QTA` |
| `stream-suspended` | `TRANSACTIONAL` | `StreamStatus.SUSPENDED` | `IT-ING`, `IT-TC` |
| `stream-disabled` | `TRANSACTIONAL` | `StreamStatus.DISABLED` | `IT-TC` |
| `email-stream` | `TRANSACTIONAL` | канал по умолчанию `EMAIL` | `IT-PRV`, `IT-VAL` |
| `push-stream` | `TRANSACTIONAL` | канал по умолчанию `PUSH` | `IT-PRV` |
| `rate-limited` | `NOTIFICATION` | `stream.rate_limit_config` — 2 запроса/с | `IT-ING` (только REST) |

### 2.2. Каналы

| Канал | Стратегия | Порядок отката | Замечание |
|---|---|---|---|
| `SMS` | `PRIMARY_ONLY` | `[MOCK-PRIMARY, MOCK-RESERVE]` | базовый канал большинства кейсов |
| `EMAIL` | `PRIMARY_ONLY` | `[MOCK-EMAIL]` | |
| `PUSH` | `PRIMARY_ONLY` | `[MOCK-PUSH]` | терминальный статус `SENT_TO_PROVIDER` (PU-12) |

**Канал в системе ровно один на код** — `channel.code` первичный ключ с `CHECK` по перечислению `Channel`, второго профиля `SMS` завести нельзя. Поэтому кейсы, которым нужна другая стратегия балансировки или статус `MAINTENANCE`/`DISABLED`, переписывают ту же строку, и такие кейсы не параллелятся между собой. Возвращать строку обратно кейс не обязан: контур накатывается заново перед каждым прогоном (§5, п. 1).

### 2.3. Провайдеры

Основной механизм — **фальшивый провайдер** (`:adapter:out:provider:mock`, [ADR-0041](../architecture/adr/ADR-0041-mock-provider-for-local-stand.md)), включаемый в тестовом профиле `commhub.provider.mock.enabled=true`. Исход определяется **последними двумя символами адреса** (`MockBehaviour`), поэтому кейс задаёт поведение провайдера самим адресом получателя и ничего не стабит:

| Суффикс | Поведение | Ответ | Что происходит дальше |
|---|---|---|---|
| `…00` | `DELIVERED` | ack принят | DLR `DELIVERED` через `report-delay` |
| `…01` | `UNDELIVERED` | ack принят | DLR `UNDELIVERED` |
| `…02` | `NO_ANSWER` | исключение | повтор, затем failover; открывает breaker (PR-01) |
| `…03` | `BLOCKING` | ack с кодом `102`, `ErrorClass.BLOCKING` | немедленный failover, breaker открыт (§18.1) |
| `…04` | `INVALID_ADDRESS` | ack `REJECTED`, код `20`, `invalidRecipient` | адрес попадает в список подавления (FR-5.1) |

Адрес читается по каналу: SMS — MSISDN (`998901234500`), PUSH — **сам токен** (токен, оканчивающийся на `04`, гоняет путь мёртвого устройства), EMAIL — **локальная часть до `@`** (`ivan00@example.com` доставлено, `ivan04@example.com` отбито).

Типы адаптеров: `mock-sms`, `mock-email`, `mock-push`.

WireMock (`support/ProviderStub.java`) остаётся, но **только** там, где предметом является контракт настоящего провайдера или размыкание breaker'а по HTTP: `IT-PRV-1xx` (Playmobile/SMS Gate) и chaos-кейсы. Смешивать нельзя — фальшивый провайдер не проверяет тело запроса к Playmobile, а Playmobile-стаб не умеет присылать DLR сам.

### 2.4. Шаблоны

| Код | Состояние | Для чего |
|---|---|---|
| `OTP_RU_UZ` | `PUBLISHED` в `RU` и `UZ`, переменная `{CODE}` | штатный рендер, выбор локали |
| `ONLY_RU` | `PUBLISHED` только `RU` | откат локали на `RU` |
| `DRAFT_ONLY` | единственная версия `DRAFT` | `TEMPLATE_NOT_PUBLISHED` |
| `ARCHIVED_CARD` | карточка `ARCHIVED`, версия опубликована | `TEMPLATE_NOT_PUBLISHED` по архиву карточки |
| `MANY_VARS` | `PUBLISHED`, три переменные | `TEMPLATE_VARIABLE_MISSING` в строгом режиме |

### 2.5. Что контуром не сеется

Список подавления, частотные счётчики, счётчики квот, kill switch и здоровье провайдеров — это **состояние, а не конфигурация**. Сеять их в общий контур нельзя: они бы протекли между кейсами. Кейс заводит своё состояние сам и получает чистые таблицы в `@BeforeEach` (§5).

### 2.6. Требования к сидеру (ТЗ на расширение `HubConfiguration`)

| Умение | Есть | Нужно |
|---|---|---|
| Поток с классом трафика и статусом | ✅ | — |
| Поток с окном тишины и поведением | ⏳ | `Stream.quietHours()` заполнено |
| Поток с суточной/месячной квотой и поведением исчерпания | ⏳ | `Stream.quota()` |
| Поток с `rate_limit_config` | ⏳ | нужен для `IT-ING` REST-only |
| Каналы `EMAIL` и `PUSH` | ⏳ | `ChannelConfig` + порядок отката |
| Провайдеры `mock-*` | ⏳ | три профиля с тарифом и весом |
| Провайдеры с разными весом/тарифом | ⏳ | для `WEIGHTED` и `LEAST_COST` |
| Шаблоны из §2.4 | ⏳ | через `Template`, с автором и проверяющим (FR-4.2) |

---

## 3. Транспортная матрица (SRS §8.1, §8.2)

Правило набора: **функциональный кейс с двумя заполненными клетками — это один текст теста, исполняемый дважды** (`@ParameterizedTest` по `Ingress`), а не два похожих теста, которые разойдутся при первой же правке. Утверждение «оба входа ведут в один конвейер» доказывается только так.

| Функция | REST | Kafka | Почему так |
|---|---|---|---|
| Приём единичного сообщения | ✅ | ✅ | §8.2: тело REST **есть** документ IK-03 |
| Обязательные поля, `schemaVersion`, форматы адресов | ✅ | ✅ | один `InboundMessageCodec` на оба входа |
| Дедупликация | ✅ | ✅ | ключ считается в конвейере, не в адаптере |
| Шаблоны, валидация, PAN | ✅ | ✅ | |
| Маршрутизация и отказ маршрута | ✅ | ✅ | |
| Тихие часы, подавление, частота, квоты | ✅ | ✅ | `MessagePipeline` — единственная дверь |
| Батч: заголовок, чанки элементов | ✅ | ✅ | заголовок — `comm.inbound.batch-control.v1`, элементы — `comm.inbound.notification.v1` |
| Батч: `start`/`pause`/`resume`/`stop` | ✅ | ✅ | `BatchActions` против `BatchCommandPayload` |
| Синхронный отказ `problem+json`, коды `ProblemType` | ✅ | — | у Kafka нет синхронного ответа; отказ конвейера логируется и коммитится (IR-01) |
| Ограничение частоты потока, `Retry-After` | ✅ | — | на стороне Kafka лимитера нет намеренно (`package-info.java`): нагрузку сдерживают квоты, общие для обоих входов (IR-02) |
| `GET /messages/{id}`, `GET /messages?streamId=&externalMessageId=` | ✅ | — | статус спрашивают запросом, а не топиком |
| 405 / 415 / 406 / неизвестный путь | ✅ | — | `UnsupportedRequestHandler` |
| SEC-01: право источника на поток, 403 | ✅ | — | `StreamAccessGuard` знает поток только из тела REST |
| Неразбираемый документ → `comm.inbound.parse-error.v1` | — | ✅ | REST отвечает 400 синхронно; в parse-error попадает только нечитаемое (IK-04) |
| Класс трафика берётся из **топика** и перекрывает поле документа | — | ✅ | TC-01 построена на топиках |
| Отказ конвейера **не** попадает в parse-error | — | ✅ | отказ — штатный исход, а не отравленная запись |
| Порядок `CREATE` → `PAUSE` в одном разделе | — | ✅ | ключ раздела `batchId` |

---

## 4. Детерминизм

Без этих правил набор станет мигающим на второй неделе.

**Время — управляемое.** Тихие часы, TTL, окно дедупликации, часовые корзины `frequency_counter`, `recovery-after` здоровья провайдера — всё это про время, и «подождать до 21:00» тестом быть не может. Бин `ClockPort` в тестовом контексте подменяется управляемыми часами с явным «сдвинуть на N часов»; домен часы не читает вовсе, поэтому подмена одного бина накрывает весь путь.

**Планировщики — по умолчанию выключены.** Кейс, предметом которого является результат, а не расписание, вызывает use case напрямую. Образец уже есть в `AcceptanceScenariosIT`:

```
commhub.outbox.relay.poll-interval-ms=3600000
commhub.dispatch.enabled=false            # либо commhub.dispatch.<class>.poll-interval=200ms
commhub.dispatch.expiry.enabled=false
commhub.provider.health.initial-delay=1h
commhub.metrics.backlog-refresh-interval=1h
commhub.config.cache.refresh-interval=1s
commhub.rest.rate-limit.enabled=false     # кроме кейсов IT-ING, где лимитер и есть предмет
```

**Асинхронное — только через `Awaitility`.** `await().atMost(60s).pollInterval(200ms).untilAsserted(...)`. `Thread.sleep` в наборе запрещён: он либо делает тест медленным, либо мигающим, а обычно и то и другое.

**Задержки фальшивого провайдера — явные.** `commhub.provider.mock.latency` (по умолчанию 50 мс) и `report-delay` (3 с) в тестовом профиле уменьшаются; кейс, ждущий DLR, ждёт его через `Awaitility`, а не через расчёт задержки.

**Проверка — с трёх сторон**, как в существующих приёмочных тестах: ответ транспорта (код и тело), состояние в БД (`JdbcClient` по `message`, `delivery_attempt`, `outbox_event`, `message_status_history`) и внешний эффект (запрос к провайдеру, запись в `comm.outbound.*`). Одной стороны мало: 202 без строки в `message` — это принятое и потерянное сообщение.

---

## 5. Изоляция

Сегодня сидер защищён `SELECT count(*) FROM stream == 0`, то есть bootstrap-классы делят изменяемое состояние БД внутри JVM. Правило набора:

1. **Конфигурация сеется один раз на JVM и не мутируется.** Кейсу, которому нужна своя конфигурация, выдаётся собственный `streamId`/`providerId` с суффиксом теста, а не правится общий.
2. **Изменчивое состояние чистится в `@BeforeEach`**: `message`, `message_status_history`, `delivery_attempt`, `outbox_event`, `dedup_registry`, `suppression_list`, `frequency_counter`, `quota_counter`, `push_delivery`, `batch`, `dlq_entry`.
3. **Журнал аудита не трогается**: `audit_log` запрещает `DELETE` и `TRUNCATE` на уровне БД (V7), поэтому кейсы, читающие журнал, пишутся безразличными к тому, что в нём уже есть.
4. **Один набор `@TestPropertySource` на базовый класс.** Уникальные свойства в каждом классе заставляют Spring поднимать новый контекст — это главный источник времени в интеграционном прогоне.
5. **Топики исходящих контрактов уникальны на класс** (`commhub.kafka.outbound.status-topic=…-<класс>`, `create-topics=true`), иначе классы вычитывают события друг друга.

---

## 6. Соответствие требованиям приёмки (SRS §15)

| ID | Требование | Чем закрывается в этом наборе |
|---|---|---|
| QA-01 | Unit-покрытие домена и use cases | вне набора: `./gradlew build`, пороги на `:domain` и `:application` |
| QA-02 | ArchUnit гексагональных правил | вне набора: `HexagonalArchitectureTest`, `LayerConventionsTest` |
| QA-03 | Интеграционные тесты на Testcontainers | **весь набор**: PostgreSQL + Kafka + Keycloak в `HubTestContainers`, GreenMail для `IT-PRV-3xx`, WireMock для `IT-PRV-1xx` |
| QA-04 | Contract-тесты | `IT-STS-0xx` (состав §6.4 поле в поле) дополняет `OutboundContractCompatibilityTest` и `ProviderDocumentationContractTest` |
| QA-05 | Нагрузочные сценарии | вне набора: [`../../load/`](../../load/) |
| QA-06 | Отказоустойчивость | `IT-PRV-2xx` (failover ≤ 60 с), `IT-DSP-0xx` (падение пода между ответом провайдера и коммитом); контейнерные паузы — `InfrastructureOutageIT` |
| QA-07 | E2E админ-панели | вне набора: `web/e2e/` |
| QA-08 | Приёмочные сценарии Банка | `IT-TC`, `IT-BAT` и сквозные кейсы OTP/транзакционного/массового потоков; `AcceptanceScenariosIT` остаётся как их краткая форма |

---

## 7. Как запускать

```bash
docker compose up -d          # PostgreSQL, Kafka, Keycloak; контейнеры тестов свои, compose — для ручного прогона
./gradlew integrationTest     # всё с тегом "integration"
./gradlew :bootstrap:integrationTest --tests '*QuietHoursIT'
```

Нужен Docker. Задача `integrationTest` намеренно не входит в `build` (§«Build, test, run» в [`../../CLAUDE.md`](../../CLAUDE.md)) — тесты с контейнерами не должны блокировать компиляцию.

Те же кейсы проходятся руками по [`../QUICKSTART-SEND.md`](../QUICKSTART-SEND.md) с готовыми документами из [`../../http/`](../../http/) (REST) и [`../../kafka/`](../../kafka/) (Kafka) — это способ проверить, что предусловия кейса достижимы на живом стенде, а не только в тесте.

Для ручного прогона есть обвязка [`../../sql/testing/`](../../sql/testing/): `00-reset.sql` (очистка состояния), `01-contour.sql` (этот самый контур), `99-teardown.sql` (уборка вместе с контуром), блок предусловий и проверок на каждый кейс в `cases/`, очистка топиков и раннер полного цикла в `bin/`.

```bash
sql/testing/bin/run-case.sh --list          # все 157 идентификаторов
sql/testing/bin/run-case.sh IT-FLT-006      # очистка → контур → предусловия → действие → проверки → уборка
```

Обвязка — для человека и для разбора; автотесты Phase 22 сеются `HubConfiguration` через репозитории (§2), а не этим SQL: конфигурация, записанная в обход агрегата, проверяет схему, а не Модуль.

---

## Чего в наборе нет — и не по недосмотру

**Проверки производительности.** «Сообщение отправлено за 300 мс» в интеграционном тесте измеряет ноутбук, а не Модуль. p99 accept→provider из TC-01 доказывается нагрузочным прогоном (QA-05), а здесь проверяется только то, что OTP-поток **не встаёт в очередь за массовым** — качественно, через разделённые пулы.

**Настоящих контуров провайдеров.** Playmobile и SMS Gate представлены WireMock'ом по их документации (§18.1, §18.2), фальшивый провайдер — своими правилами. Приёмка на тестовых контурах провайдеров (QA-08) — это отдельное упражнение с реальными учётными данными, и автоматизировать его в `integrationTest` нельзя.

**Разбора кодов ошибок провайдеров построчно.** Таблицы §18.1/§18.2 проверяются контрактными IT самих адаптеров. Сквозной набор берёт по одному представителю каждого класса (`RETRYABLE`, `NON_RETRYABLE`, `BLOCKING`) и проверяет, что **сага поступает с ними по-разному**.

**Порядка исполнения кейсов.** Ни один кейс не зависит от того, что до него отработал другой. Кейс, которому нужно состояние, создаёт его сам — включая те, что проверяют повторную отправку и дедупликацию.

**Экранов панели.** Панель со сквозным путём сообщения не пересекается: она читает то, что этот набор записал. Её тесты — [`../frontend-spec/09-testing.md`](../frontend-spec/09-testing.md).
