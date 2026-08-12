# DLQ — dead-letter queue

**Route** `/dlq` · **Roles** ADMIN, OPERATOR

| Endpoint |
|---|
| `GET /dlq` |
| `POST /dlq/retry` |
| `POST /dlq/archive` |

## Filters

Period · `reason` (`RejectionReason`) · `includeRetried` (default false) · `includeArchived`
(default false).

**This list has no default period, deliberately.** The queue is read to find the *oldest* thing in it,
and a screen that silently showed only the last day would hide exactly that. Leave `from`/`to` empty
unless the operator sets them, and do not add a client-side default.

Triage starts with the reason filter: `PROVIDER_REJECTED` and `ATTEMPTS_EXHAUSTED` need entirely
different handling, and mixed together they just look like "lots of errors".

## Table

This is the one list that needs **row selection**, so it may own its own table rather than reusing the
shared one — but the paging semantics stay identical.

| Column | Source |
|---|---|
| ☑ | selection checkbox, **with an accessible name per row** |
| Message id | `messageId`, copyable |
| Reason | `reason` |
| Last error | `lastError` — the provider's answer or the pipeline's refusal |
| Queued since | `movedAt` |
| Retried | `retriedBy` / `retriedAt` |
| Archived | `archived` |
| Actions | per-row Retry / Archive |

Above the table: **"Retry selected (N)"** and **"Archive selected (N)"**.

### Which rows can be selected

- A row is retryable when `retryable` is true. **`retryable: false` is a property of the message, not
  of the operator's permissions** — say so in the tooltip, or every disabled button becomes a
  permissions ticket.
- A row is selectable unless there is nothing left to do with it: not retryable **and** already
  archived.

## Actions

```
POST /dlq/retry     { "messageIds": ["…", "…"] }
POST /dlq/archive   { "messageIds": ["…", "…"] }      X-Commhub-Reason: <percent-encoded>
```

**Send explicit id lists only. Never send a filter, and never offer a "retry everything matching"
control.** "Retry everything that matches" is one mistyped field away from re-sending a day of
traffic. The API takes no filter for this reason; do not simulate one by selecting every row across
every page.

- **Retry does not ask for a justification.** Archive does.
- Retry is safe by construction: idempotency by dedup key means a retry cannot produce a duplicate
  send (FR-1.5, AD-03).

### Reading the answer

`DlqActionResult { applied[], skipped[] }` — both are id lists. Report them literally: "applied: N,
skipped: M". **A 200 with skipped entries is the normal case**, not an error: skipped rows are the
ones the action did not apply to, visible in the Retried and Archived columns. Re-fetch the list after
either action.

## What does *not* end up in the DLQ

An empty DLQ next to a non-empty "failed" count on the dashboard is not a screen bug, and operators
ask about it. Put the explanation on the screen:

- The DLQ holds what could **not be processed** — every attempt and every fallback provider exhausted
  (`ATTEMPTS_EXHAUSTED`), or the pipeline refused for its own reason.
- A message the provider **accepted** and then reported as `UNDELIVERED` was processed completely.
  That is a terminal delivery outcome, and it is investigated in Messages, not here.
- An address the provider called unusable is already on the suppression list and will not be retried
  — that row is found in Suppressions, by address hash.

## Do not

Do not offer "archive all" as a convenience. Archiving is the decision "this customer will not receive
this message", and unlike a retry it is irreversible from the customer's side. Keep it a per-selection
action with a justification.

## States

- Empty queue → say so plainly. An empty DLQ is good news and should read like it.
- 400 on retry/archive with an empty `messageIds` → prevent it in the UI; the button is disabled at
  zero selection.
- 403 → the role map is wrong; show it.
