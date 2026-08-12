# 04 — API contract

Everything in this file is about **how** to call the admin BFF. **What** to call is in the OpenAPI
document; generate types from it.

## Base

```
{apiBaseUrl}          default /api/admin/v1
Authorization: Bearer {accessToken}          on every request
```

60 operations across 49 path items, grouped by 14 tags that correspond one-to-one with the screens.

One more operation exists and is **not in the document**: `GET /api/admin/v1/openapi.yaml`, which
serves the document itself (any role, `application/yaml`). Useful for fetching the contract from a
running contour instead of the repository.

## One client, two pieces of middleware

Create a single HTTP client at bootstrap and give it exactly two concerns:

1. **Request middleware** — attach the bearer token. Read it through an indirection (a getter the auth
   module registers) so the client module does not import session state.
2. **Response middleware** — if the response is OK, pass it through. Otherwise parse the
   `application/problem+json` body and **throw** a typed error carrying `status`, the parsed problem
   and a parsed `Retry-After`.

Throwing at the transport boundary is what makes every screen a single success path with one error
branch, instead of a status check at every call site. See `05-error-model.md` for the error shape.

Nothing else belongs in the client: no retries (the panel is a person pressing a button — an automatic
retry of a failed `POST /send/message` is a second SMS), no caching, no toast notifications.

## Paging

Server-side, offset-based:

```
?limit=<int ≥1>&offset=<int ≥0>
```

Responses that page carry `total`, `limit`, `offset` alongside `items`.

Offset paging is deliberate, not a shortcut. These screens are read by a person who jumps to page
seven, sorts, and comes back — exactly what a cursor is bad at. Rows do shift underneath, and for
"what happened yesterday" that is not a correctness problem. The one thing that cannot tolerate drift
is an export, and exports are not paged: they walk the same query to the end.

Per-list ceilings, enforced server-side (a larger `limit` is a 400, not a silent clamp):

| List | Default | Max |
|---|---|---|
| Messages, DLQ, Templates, Suppressions | 50 | 500 |
| Batches | 50 | 200 |
| Audit | 50 | 1000 |

Offer page sizes that stay inside those ceilings.

## Periods

`from` and `to` are **ISO-8601 instants in UTC**; `to` is exclusive.

- Omitting both is legal and means *the server's default*: **the last 24 hours**. Prefer omitting to
  sending a client-computed "last 24h" — the server's clock is the one the data was written with.
- The maximum span is **92 days**. A wider request is a 400 pointing at `from`.
- `to` before `from` is a 400 pointing at `to`.

The 24-hour default exists because the `message` table is partitioned by acceptance time, and a screen
opened with no period would scan every partition retention keeps — and screens get opened with no
period exactly when somebody is in a hurry.

**Two lists deliberately have no default period**: DLQ and Audit. The dead-letter queue is read to
find the *oldest* thing in it, and a screen that silently showed only the last day would hide exactly
that. Do not add a client-side default to those two.

The user picks periods in `Asia/Tashkent` and the panel converts to UTC on the way out and back on the
way in — see `07-conventions.md`.

## Sorting

Where a list supports it, sorting is server-side. Filters belong to the screen; the table component
owns paging and sorting and takes a fetch function that closes over the current filters. A filter
change invalidates the table and resets to the first page.

## Custom headers

### `X-Commhub-Reason` — request

The operator's justification for an action, which lands in the audit journal (FR-7.3).

**The value must be percent-encoded UTF-8** (`encodeURIComponent`). This is not optional politeness:
an HTTP header value is a byte string, and justifications are typed in Russian. Building a request
with a raw Cyrillic header value **throws in the browser**, and the request never leaves. The backend
decodes it once on the way in, for the admin API only.

Produce the header in exactly one helper, used by every screen. An empty justification means **omit
the header entirely**, not send an empty one.

Twelve operations accept it:

| Operation | Required? |
|---|---|
| `POST /batches/{batchId}/actions/{action}` | optional |
| `POST /dlq/archive` | optional |
| `POST /streams/{streamId}/suspend` · `/resume` | optional |
| `POST /channels/{channel}/state/{status}` | optional |
| `DELETE /providers/{providerId}` | optional |
| `POST /providers/{providerId}/state/{state}` | optional |
| `DELETE /templates/{code}` · `POST /templates/{code}/restore` | optional |
| `DELETE /suppressions/{entryId}` | optional |
| **`POST /send/message`** | **mandatory** — blank or absent is a 400 |
| **`POST /send/batch`** | **mandatory** — blank or absent is a 400 |

The kill switch is the odd one out: its justification travels **in the body** (`KillSwitchRequest.reason`)
and is mandatory when activating.

### `X-Commhub-Truncated` — response

Boolean, on `GET /audit/export` only. `true` means the export stopped at the 50 000-row ceiling.

**Surface it.** An unannounced cap is a file that looks complete and is not, and for an audit export
that is the only failure mode that matters. Show a warning next to the download and tell the operator
to narrow the period.

### `Retry-After` — response

On 429. Seconds or an HTTP-date; parse both, never produce a negative. Use it to disable the action and
count down rather than letting the operator hammer the button.

### `X-Correlation-Id` — request and response

The backend gives every HTTP call a correlation id, echoes it on the response, and puts it in its logs
and traces. It is not declared in the OpenAPI document but it is always there.

Worth doing: show it in error details. "Something went wrong (`X-Correlation-Id: …`)" turns a support
ticket into a one-query log search. The panel may also *send* one, and the backend will honour it if
it is non-blank and short enough.

## CSV

### Uploading

Three endpoints take a raw CSV body with `Content-Type: text/csv` — not multipart, not a JSON wrapper:

- `POST /templates/import` (+ `?approver=`)
- `POST /suppressions/import`
- `POST /send/estimate` and `POST /send/batch` (the CSV variants)

Read the picked file as text in the browser and post the string.

All of them share the same tolerance rule: **bad rows are reported, not fatal**. The response carries
`failures[] { line, reason }` with 1-based line numbers, and the good rows were still applied. Render
that list; do not present a partial success as a failure.

### Downloading

`GET /statistics/export` and `GET /audit/export` return `text/csv;charset=UTF-8` with:

- a **UTF-8 BOM** — so Excel does not turn Cyrillic into mojibake;
- a leading apostrophe on any cell starting with `= + - @`, so a cell is never a formula;
- `Content-Disposition: attachment; filename="commhub-statistics.csv"` / `"commhub-audit.csv"`.

Fetch as text, save as a blob. Do not re-encode, do not strip the BOM, do not parse and re-render —
the export must be byte-identical to what the server produced, or it stops being comparable to the
screen it came from.

Column layouts, for reference:

```
audit:      occurredAt, username, action, entityType, entityId, before, after, reason, sourceIp
statistics: key, accepted, delivered, failed, rejected, inFlight, segments, cost, deliveryRate
```

## Traps

Four places where a generator working from the yaml alone will get it wrong.

### 1. `POST /send/estimate` is one operation with two request bodies

`application/json` (a single message) and `text/csv` (an uploaded list) are **two backend handlers
behind one OpenAPI operation**. Typed clients generated from the document model it as one call, which
makes the CSV path easy to build incorrectly:

- **JSON variant** — body is `SendRequest`. The five query parameters are ignored.
- **CSV variant** — body is the file; `streamId`, `templateCode`, `locale`, `channel` **must** be
  query parameters (plus optional `trafficClass`), because the CSV carries recipients, not a header.

`POST /send/batch` is CSV-only and requires the same four query parameters, plus a mandatory reason
header.

### 2. `BatchStatus` is an enum; `BatchStatus_` is an object

The batch *card* schema is named `BatchStatus_` with a trailing underscore, because `BatchStatus` was
already taken by the status enum. Generated type names inherit the underscore. Not a typo; do not
"fix" it.

### 3. `GET /dashboard/stream` exists and the reference implementation does not use it

There is an SSE endpoint emitting a `dashboard` event every 15 seconds. The contract itself recommends
plain polling as the primary mechanism, because polling survives a load balancer, a corporate proxy
and a laptop that went to sleep. **Poll `GET /dashboard` every 15 s.** Using SSE is permitted; if you
do, you must still fall back to polling when the stream drops.

### 4. Enums that the document spells inline

Most enums are named schemas and generate as unions. A few values appear inline on a property. Treat
`06-vocabulary.md` as the authority for the full constant list of every enum, and never hand-type an
enum value that the document does not spell.

## What the panel must never call

`/api/v1/**` — the source-system API of §8.2. It is a published contract for the Bank's systems, it
authorises by a machine token's stream claim, and it is not the panel's backend. The panel has its own
equivalents for everything it needs, and the message and batch cards already return exactly the §8.2
bodies.
