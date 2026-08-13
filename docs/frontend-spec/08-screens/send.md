# Send

**Route** `/send` · **Roles** ADMIN, OPERATOR

| Endpoint | Notes |
|---|---|
| `POST /send/estimate` (JSON) | single message |
| `POST /send/estimate` (text/csv) | uploaded list; needs the query parameters |
| `POST /send/message` | **reason header mandatory** |
| `POST /send/batch` (text/csv) | **reason header mandatory**, query parameters required |

This section exists because of ADR-0038 and is deliberately **not** a share of the source-system API
(§8.2): that one authorises by a machine token's stream claim, knows no justification header, and
legitimately allows free content. This one is a person sending on behalf of the Bank.

## What this screen is not

It does not build audiences and it does not run campaigns. The recipient list arrives as a file the
operator brings, exactly as a source system brings one in a batch. **There is no schedule and no
recurring send.**

## The rule that shapes the whole screen

**Content comes only from a published template. There is no text field in either tab.**

Neither request carries a body text — the schema has no such property — so an unreviewed wording
cannot reach a customer without changing the record. If a new wording is needed, it is authored and
published in Templates, where a second person reads it (FR-4.2).

This is not an unfinished feature. Say so in the screen's help text, or the first support ticket will
ask where the message box went.

## Two tabs

Both share the same header fields:

| Field | Control | Notes |
|---|---|---|
| Stream | **picker** over `GET /streams` | Not a channel — "whose send is this". It decides quotas, limits, default traffic class and priority, quiet hours, and it is what shows up in statistics and on the message card. A suspended stream is **visible but not selectable**, so "my stream isn't there" is never a mystery. |
| Template code | **picker** over `GET /templates` | Filtered to the chosen channel. |
| Template locale | **picker** | Narrowed to the locales in which the chosen template **has a publication**. A locale with nothing to send is simply absent. |
| Channel | select, `Channel` | |
| Traffic class | select, optional | Empty → the stream's default. |

Both pickers are lists, not text fields. **If a reference lookup fails or is forbidden, the field
degrades to a plain text input** — an unavailable list must never cost a send (`07-conventions.md`).

### Tab 1 — single message

Adds: recipient address for the channel (msisdn / email / push token + platform), optional `clientId`,
and merge-variable rows (name/value pairs) for whatever the template expects. Optional `externalId`.

Body: `SendRequest` = `{ streamId, templateCode, locale, channel, trafficClass?, recipient,
variables, externalId? }`.

An omitted `externalId` is filled in by the server as `panel-<uuid>`.

### Tab 2 — bulk from a file

Adds: a CSV drop zone. Nothing else — recipients and per-row variables all come from the file.

## The two-step flow

**1. Estimate.** The operator presses "Calculate estimate":

- single: `POST /send/estimate` with the JSON body;
- bulk: `POST /send/estimate` with `Content-Type: text/csv`, the file as the body, and
  `streamId`, `templateCode`, `locale`, `channel` (+ optional `trafficClass`) **as query parameters**.
  See the trap in `04-api-contract.md`.

The response (`SendEstimate`) shows: `recipients`, `segments`, `provider`, `estimatedCost`,
`template { version, status }`, `missingVariables[]`, `failures[] { line, reason }`, and a nullable
`rejection { reason, detail }`.

**2. Send**, from a confirmation dialogue showing the estimate:

- single: `POST /send/message`;
- bulk: `POST /send/batch` with the same four query parameters and the file body.

Both require `X-Commhub-Reason` — the confirm button must require a non-empty justification rather
than sending a blank header.

> **A pipeline refusal of a single send arrives as a problem document, not as a 200.** `POST
> /send/message` answers `422` (`SUPPRESSED`, `QUIET_HOURS`, `TEMPLATE_*`), `429` (`QUOTA_EXCEEDED`,
> `FREQUENCY_CAPPED`), `409` (`DUPLICATE_SUBMISSION`) or `503` (`KILL_SWITCH`), each carrying `code`,
> `detail` and the `messageId` — render it through the ordinary error path (`05-error-model.md`) and do
> not try to read a status out of a 200 body. `POST /send/batch` is different on purpose: it stays
> `202` and reports per-row refusals in `failures[]`, because one bad row must not fail the upload.

### The estimate gate

**The send action is disabled until an estimate exists for the current form state, and any edit to the
form clears the estimate.** If `rejection` is non-null, the send stays disabled and the rejection
reason is shown — there is no route, so there is nothing to send.

Getting this wrong is the difference between "the operator saw what it would cost and who it would go
to" and "the operator pressed a button".

### The estimate stays an estimate

Tariffs and provider health can change between the confirmation and the send. Say so. If the number
turns out to have been wrong, the remedy is to pause the batch in the Batches section — which works,
because a bulk send is an ordinary batch.

## CSV format

Columns by header name. The address column depends on the channel:

| Channel | Address column | Also |
|---|---|---|
| SMS | `msisdn` | |
| EMAIL | `email` | |
| PUSH | `pushToken` | `pushPlatform` |

Reserved column names (case-insensitive): `msisdn`, `email`, `pushToken`, `pushPlatform`,
`clientId`, `externalId`.

**Every other column is a merge variable, and its header is the variable name, case preserved.**
A column `NAME` fills `{NAME}`; a column `name` does not.

```csv
msisdn,clientId,externalId,NAME
998932107400,CL-0001,payroll-2026-08-0001,Иван
998909089700,CL-0002,payroll-2026-08-0002,Пётр
```

A ready 50-row sample lives at `docs/samples/recipients-sms-ru.csv`.

- **Ceiling: 50 000 recipients** per file (deployment-configurable). Over it is a 400 pointing at
  `file`.
- **Bad rows do not fail the upload.** They come back in `failures[] { line, reason }` with 1-based
  line numbers, and the good rows were still processed. Render that list; a partial success is not a
  failure.

### Re-uploading the same file is safe

A row with no `externalId` takes its identity from `sha256(file) + line`. The same file uploaded twice
inside the dedup window produces `DUPLICATE`, not a second message to every customer (FR-1.5). Say
this in the help text — the fear of double-sending is what makes people build their own de-duplication
in a spreadsheet.

## Result

A single send returns `MessageAccepted { messageId, status }` — show the id and offer a link to the
message card.

A bulk send returns `SendBatchResult { batchId, accepted, duplicates, rejected, failures[] }` —
show all four numbers and an **"open batch"** link to `/batches` with that card open. From that moment
it is an ordinary batch: it can be paused and stopped there.

## Errors worth handling by name

| `code` | What to say |
|---|---|
| `TEMPLATE_NOT_PUBLISHED` | The template has no published version in that locale. Link to the template card. |
| `TEMPLATE_VARIABLE_MISSING` | A merge field is empty — the sending path is strict even though the preview is not. |
| `NO_ROUTE_AVAILABLE` | Usually an unconfigured channel on a fresh contour. Link to Providers → Channels. |
| `KILL_SWITCH` | Sending is globally stopped. Link to Administration. |
| `STREAM_SUSPENDED` | That stream is not accepting. |
| `QUOTA_EXCEEDED` | The stream, channel or provider budget is spent — retrying does not help. |
| `VALIDATION_FAILED` with `field` | Attach to the field. Common on a mistyped MSISDN. |

## On the local stand

`docker compose` runs a mock provider (ADR-0041) whose behaviour is read off the **last two digits of
the number**: `…00` delivered, `…01` undelivered, `…02` no answer, `…03` blocking, `…04` unusable
address. Useful for building the screen without a real provider; see `docs/samples/README.md`.
