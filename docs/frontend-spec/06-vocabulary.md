# 06 — Vocabulary

Every enum that crosses the wire, with its exact constants. These are pinned to the Java domain enums
by a backend test (`AdminOpenApiContractTest.enumsMatchTheDomain`), so the document and the code cannot
drift — but the panel is outside that test's reach, which is why this file exists and why
`09-testing.md` asks you to test your own lists against the generated schema.

## The three rules

**1. Enum values render as-is and are never translated.** `DELIVERED` is `DELIVERED` in the Russian,
Uzbek and English interface. The §6.3 status vocabulary is one vocabulary: an operator on the phone
with an engineer, a log line, a Kafka event and a screen must all say the same word. Translate the
*column heading*, never the *value*.

**2. Never hand-type an enum value.** Derive filter options and colour maps from the generated schema
types so that a value added to the backend becomes a compile error in the panel rather than a filter
that silently drops rows.

**3. An unknown value renders as itself.** If the server sends a constant this build does not know,
show the raw string in a neutral style. Never render it as blank, and never drop the row.

---

## Channel and traffic

### `Channel`
```
SMS  EMAIL  PUSH
```
Exactly three. Adding a channel is a release, not a row (AR-05) — but see `08-screens/providers.md`
for why the Channels tab still builds its rows from this list rather than from the API response.

### `TrafficClass`
```
CRITICAL_OTP  TRANSACTIONAL  NOTIFICATION
```
Isolation classes (TC-01). From Kafka the class comes from the topic and overrides the document. Only
`NOTIFICATION` is subject to quiet hours and frequency capping; everything except `CRITICAL_OTP` is
stoppable by default, which is why the kill switch spares OTP unless asked.

### `Priority`
```
REALTIME  HIGH  NORMAL  LOW
```
`REALTIME` is the highest. It exists and must be offered — it was once missing from the contract and
therefore unusable from the panel.

---

## Message and batch

### `MessageStatus` — 14 values (§6.3)
```
ACCEPTED  VALIDATED  ROUTED  QUEUED  SENDING  SENT_TO_PROVIDER  RETRYING
DELIVERED  UNDELIVERED  EXPIRED  REJECTED  DUPLICATE  CANCELLED  FAILED
```

Suggested semantics for colouring — the exact palette is yours, the grouping should not be:

| Group | Values | Reads as |
|---|---|---|
| Success | `DELIVERED` | positive |
| In flight | `ACCEPTED` `VALIDATED` `ROUTED` `QUEUED` `SENDING` `SENT_TO_PROVIDER` `RETRYING` | neutral / in progress |
| Failure | `UNDELIVERED` `EXPIRED` `REJECTED` `FAILED` | negative |
| Neither | `DUPLICATE` `CANCELLED` | muted |

`SENT_TO_PROVIDER` is **terminal for push** (PU-12): neither APNs nor FCM reports delivery, so a push
that reads `SENT_TO_PROVIDER` forever is correct, not stuck. Say so on the message card.

### `BatchStatus` — 5 values
```
ACCEPTED  PROCESSING  PAUSED  STOPPED  COMPLETED
```
Beware: the *card object* is the schema named `BatchStatus_` (trailing underscore). See
`04-api-contract.md`.

### `RejectionReason` — 17 values
```
VALIDATION_FAILED  DUPLICATE_SUBMISSION  SUPPRESSED  OPT_OUT  QUIET_HOURS
FREQUENCY_CAPPED  QUOTA_EXCEEDED  STREAM_SUSPENDED  TEMPLATE_NOT_PUBLISHED
TEMPLATE_VARIABLE_MISSING  NO_ROUTE_AVAILABLE  PAN_DETECTED  TTL_EXPIRED
SEND_STOPPED  KILL_SWITCH  PROVIDER_REJECTED  ATTEMPTS_EXHAUSTED
```
The reason recorded on a message or a DLQ entry. Nearly the same list as the error `code` vocabulary
of `05-error-model.md`, with one rename: `DUPLICATE_SUBMISSION` here is `DUPLICATE` there.

---

## Configuration

### `BalancingStrategy`
```
ROUND_ROBIN  WEIGHTED  LEAST_COST  PRIMARY_ONLY
```
`PRIMARY_ONLY` — not `PRIORITY`. This value was wrong in the contract for a while precisely because it
was written inline in five places instead of as one named schema.

### `StreamStatus`
```
ACTIVE  SUSPENDED  DISABLED
```

### `ConnectionStatus`
```
CONNECTED  IDLE  DISCONNECTED  UNKNOWN
```
Observed activity of the source, not a setting. Read-only everywhere.

### `ChannelStatus`
```
ACTIVE  MAINTENANCE  DISABLED
```

### `ProviderState` — what you set
```
ENABLED  DISABLED  MAINTENANCE
```
`MAINTENANCE` is not `DISABLED`: both make a provider unselectable, but they are different audit
entries, which is the whole reason the distinction exists.

### `ProviderHealthStatus` — what you observe
```
UP  DEGRADED  DOWN  UNKNOWN
```
Derived passively from real delivery attempts (PR-02). `DOWN` is unselectable. **Read-only — there is
no "mark provider healthy" button**, deliberately: health is a conclusion from traffic, and a provider
with no traffic recovers through `UNKNOWN` (selectable, untrusted) rather than by being declared well.
Render `UNKNOWN` as "no data", never as a failure.

### `QuotaExhaustionBehavior`
```
BLOCK_AND_ALERT  ALERT_ONLY
```
`BLOCK_AND_ALERT` — not `BLOCK`. The short name was in the contract once and made *every* quota form
in the panel answer 400.

### `QuietHoursBehavior`
```
DEFER  REJECT
```

### `PushPlatform`
```
ANDROID  IOS  WEB
```
**Device platforms, not adapter names.** `FCM` and `APNS` are `adapterType` values on a provider
profile and have no place in a recipient address.

### `SmsEncoding`
```
GSM7  UCS2
```
Chosen by segmentation (§18.3). One non-GSM character forces the whole message to UCS2 — which is why
a preview showing `UCS2` and 3 segments for a short Russian text is correct and worth surfacing.

---

## Templates

### `TemplateCatalogStatus` — the card
```
ACTIVE  ARCHIVED
```

### `TemplateVersionStatus` — the version
```
DRAFT  ON_REVIEW  PUBLISHED  ARCHIVED
```
Only `PUBLISHED` is sendable. **There is no `REJECTED` and no `IN_REVIEW`.** Both names were once in
the contract and in the SPA, so the "send to review" button answered 400 forever. A reviewer's refusal
is a return to `DRAFT`.

Do not conflate the two: the card status says whether the template is in the catalogue; the version
status says whether anything can be sent from it.

### `ContentLocale`
```
RU  UZ  EN
```
Content locales, deliberately separate from the interface language — an operator working in English
may well publish a Russian template.

---

## Compliance

### `SuppressionReason` — 7 values
```
OPT_OUT  COMPLAINT  HARD_BOUNCE  DELIVERY_FAILURES  PROVIDER_BLACKLIST
PUSH_TOKEN_INVALID  MANUAL
```
There is no `INVALID_ADDRESS` and no `BLACKLISTED`; both were once in the contract and matched neither
the domain nor the database constraint.

An operator adding an entry by hand realistically picks `MANUAL`, `OPT_OUT` or `COMPLAINT`; the rest
arrive automatically from providers and bounces, with the provider code as `createdBy`.

---

## Reports

### `StatisticsDimension` (query parameter `dimension`)
```
CHANNEL  PROVIDER  STREAM  BATCH  DAY  HOUR
```
Default `CHANNEL`. The dimension decides what `StatisticsRow.key` holds — a channel name, a provider
code, a stream id, a batch id, or a date/hour bucket.

---

## Values that look like enums and are not

Do not build these as unions from a hand-written list:

| Value | Why it is open |
|---|---|
| `adapterType` (`playmobile-http`, `smsgate-http`, `smtp`, `fcm`, `apns`, `mock-sms`, …) | Deliberately `type: string` in the contract. An `enum` would freeze AR-04 into a published document. Get the valid set from `GET /providers/adapters`, which lists what *this contour* deployed. |
| Provider `code` (`PLAYMOBILE`, `SMS_GATE`, …) | Operator-created. Pattern `^[A-Z0-9][A-Z0-9_]{1,31}$`. |
| `streamId` | Operator-created. Pattern `^[a-z0-9][a-z0-9._-]{1,63}$`. |
| Template `direction` | Free text. |
| Audit `action` and `entityType` | Free text filters over an open journal vocabulary; offer a text field, not a dropdown. |
| System parameter `key` | Free text. |
| Batch action (`start`, `pause`, `resume`, `stop`) | A path vocabulary, not a domain enum. Fixed, but it is four use cases rather than an enum type. |
