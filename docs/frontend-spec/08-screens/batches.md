# Batches

**Route** `/batches` · **Roles** ADMIN, OPERATOR, VIEWER (control buttons: ADMIN and OPERATOR only)

| Endpoint | Roles |
|---|---|
| `GET /batches` | A O V |
| `GET /batches/{batchId}` | A O V |
| `POST /batches/{batchId}/actions/{action}` | A O |

## Filters

Period (default: server's 24 h) · `streamId` · `channel` · `status` · `activeOnly`.

`activeOnly` is **not** the same as filtering by one status — it means "not finished", covering
several statuses at once. Offer both; do not implement `activeOnly` as a status filter.

## Table

| Column | Source |
|---|---|
| Batch id | `batchId` — copyable |
| Stream | `streamId` |
| Channel | `channel` |
| Status | `status` (`BatchStatus`) |
| Total | `total` |
| Progress | `progress.completionPercent` as a named progress bar |
| Cost | `costEstimate` |
| Created | `createdAt` |

Row click opens the card.

## Card

Everything from the row, plus the `progress` block: `processed`, `sent`, `delivered`, `failed`, and
the overall progress indicator.

A side panel is a good fit — the operator keeps the filtered list visible while working through it.

### Read the counters literally, and say so

- **`sent`** grows when a provider accepted the message.
- **`delivered` / `failed`** grow when the provider's report arrives.
- **`processed` lags on SMS, on purpose.** A message handed to a provider is still in flight; it
  becomes processed when the delivery report lands.
- **Push has no delivery report at all**, so `processed` there grows immediately (PU-12).

An operator watching an SMS batch sit at "processed: 40%" while "sent: 95%" is looking at correct
numbers. Put that in the field help rather than making them ask.

## Actions

The available set depends on the current status, and that is normal:

| Status | Buttons |
|---|---|
| `ACCEPTED` | Start · Stop |
| `PROCESSING` | Pause · Stop |
| `PAUSED` | Resume · Stop |
| `STOPPED`, `COMPLETED` | none |

```
POST /batches/{batchId}/actions/{start|pause|resume|stop}
X-Commhub-Reason: <percent-encoded>
```

Every one of the four **asks for a justification** (`07-conventions.md`). Cancelling the prompt aborts
the action.

After a successful action: re-fetch the list and re-open the card with the new status. Do not update
optimistically — the server can refuse a transition the panel thought was available, and it answers
409 `CONFLICT` when it does.

On 409: re-fetch the batch and re-render before showing the error. The operator is looking at a batch
somebody else already stopped.

### Pause is reversible, Stop is not

Pause holds delivery and resumes from where it stopped. **There is no transition out of `STOPPED`.**
Confirm Stop distinctly from Pause — an operator who meant "hold this for ten minutes" and pressed
Stop cannot undo it.

## Drill-down to messages

A "batch messages" button navigating to `/messages?batchId={batchId}`. That is the whole drill-down —
**there is no nested messages-of-a-batch endpoint**, and the message list with a filter gives the same
paging, the same masking and the same SEC-08 audit entry.

## Guidance worth surfacing

Do not offer, and do not encourage, pausing a batch "to speed up OTP". Traffic-class isolation
(TC-01) already separates bulk notification traffic from OTP into different queues and thread pools.
Pausing treats a symptom that usually is not there.

## Empty and error states

- No batches matching the filters → "nothing matches these filters", with the filters still visible.
- A batch id in the URL that does not exist → 404 `NOT_FOUND` → a not-found card, not an empty one.
- VIEWER sees the list and the card with **no action buttons at all** — not disabled ones. A greyed
  button invites a support ticket about permissions.
