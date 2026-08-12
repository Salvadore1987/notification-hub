# Streams

**Route** `/streams` · **Roles** ADMIN (the list is also readable by OPERATOR, for the Send form's
picker)

| Endpoint | Roles |
|---|---|
| `GET /streams` | A O |
| `POST /streams/{streamId}` | A |
| `PUT /streams/{streamId}` | A |
| `POST /streams/{streamId}/suspend` | A |
| `POST /streams/{streamId}/resume` | A |

A stream is an inbound source of traffic — "whose send is this". It decides quotas, rate limits,
default channel/traffic class/priority, and the quiet-hours window, and it is what appears in
statistics and on every message card.

## List

No paging — there are tens of streams, and `GET /streams` returns an array. Plain table plus a
Refresh button.

| Column | Source |
|---|---|
| Stream | `streamId` |
| Name | `name` |
| Status | `status` (`StreamStatus`) |
| Connection | `connectionStatus` — **observed, read-only** |
| Defaults | `defaults` summarised |
| Last activity | `lastActivityAt` |
| Actions | Edit · Suspend / Resume |

`connectionStatus` (`CONNECTED` / `IDLE` / `DISCONNECTED` / `UNKNOWN`) is observed activity of the
source, **not a setting**. Never render it as editable.

## Form — register and edit

One form for both. `POST` to register, `PUT` to edit; the body is `StreamRequest` either way.

### `PUT` semantics — the load-bearing detail

- **`null` (or an omitted field) means "leave it alone".**
- **`clearQuietHours: true` means "remove the quiet-hours window".**

Two distinct meanings — "there is no window" and "do not touch the window" — and an absent field
cannot carry both. That is the entire reason the flag exists. Do not send `quietHours: null` hoping it
clears anything.

### Fields

**Identity**

| Field | Rule |
|---|---|
| `streamId` | Pattern `^[a-z0-9][a-z0-9._-]{1,63}$` — **lowercase**. `PlayMobile` is refused. Note this is the opposite case rule from provider and template codes. **Disabled when editing**: source systems name it in every message, so it never changes after registration. |
| `name` | Free text |

**There is no credentials field on a stream** (ADR-0044). Nothing in the configuration holds or
resolves a secret — do not add a field for one.

**There is no integration-type field either** (ADR-0045). A stream is not bound to a transport: the same
stream is accepted over REST and from Kafka, and the source system picks per call. The field used to
exist, constrained nothing, and was silently dropped by `PUT` — do not reintroduce it as a label.

**Stream defaults** (`defaults`) — applied when the source system does not name them:
`channel`, `provider`, `trafficClass`, `priority`, `balancingStrategy`.

> **`balancingStrategy` here is not a default — it is an override of the channel's.** Empty means "the
> channel's strategy applies"; filled means "replace it for this stream's traffic" (FR-2.3).
> A stream does **not** define a provider list or a fallback order at all — those always come from the
> channel. See `providers.md` for the three levels.

**Quota** (`quota`) — `dailyCount`, `monthlyCount`, `dailyCost`, `monthlyCost`, and `behavior`
(`BLOCK_AND_ALERT` = refuse, `ALERT_ONLY` = pass but signal).

**Rate limit** (`rateLimit`) — `tps`, `perMinute`, `perRecipientPerHour` (IR-02).

**Quiet hours** (`quietHours`) — `start`, `end` (`HH:mm`), `zone` (IANA, default `Asia/Tashkent`),
`behavior` (`DEFER` = hold until the window ends, `REJECT` = refuse).

> A stream's window **replaces** the channel's entirely; it does not narrow it. Channel 21:00–09:00
> plus stream 23:00–07:00 gives this stream exactly 23:00–07:00, and the channel's hours stop
> applying. An empty window means "the channel's applies", **not** "no quiet hours" — the only way to
> remove one is `clearQuietHours`. OTP and transactional traffic ignore quiet hours at every level.

A window crossing midnight is normal; do not validate `start < end`.

## Suspend and resume

```
POST /streams/{streamId}/suspend        X-Commhub-Reason
POST /streams/{streamId}/resume         X-Commhub-Reason
```

Both ask for a justification. Choose the button from the current `status`. While suspended, the source
system is refused at intake.

Worth saying on the screen: **suspending a stream is not a way to deal with errors.** A suspended
stream stops accepting and the source system starts queueing on its side — the problem does not go
away, it changes owner.

## Errors

| Situation | `code` | Handling |
|---|---|---|
| Registering an existing id | `CONFLICT` (409) | Say the stream already exists; offer to edit it. |
| Editing a missing id | `NOT_FOUND` (404) | Re-fetch the list. |
| Bad identifier | `VALIDATION_FAILED` (400) with `field` | Attach to the field; the case rule is the usual cause. |
