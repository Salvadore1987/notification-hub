# Messages

**Route** `/messages` · **Roles** ADMIN, OPERATOR, VIEWER (VIEWER sees masked addresses)

| Endpoint |
|---|
| `GET /messages` |
| `GET /messages/{messageId}` |

This is the screen an operator opens when a customer says "the code never arrived".

## Filters

Period (default 24 h) · `externalMessageId` · `recipient` · `correlationId` · `batchId` · `streamId` ·
`status`, and an explicit **Search** button.

**Search on demand, not on keystroke.** Every search writes one SEC-08 audit entry recording an
employee's access to personal data, keyed by the address hash. Per-keystroke search would fill the
journal with noise and make "who looked at this customer" unanswerable — which is the question the
journal exists to answer.

Read `batchId` from the query string on mount, so `/messages?batchId=…` from a batch card works as a
shareable link.

### What the operator needs to know about the filters

- **`recipient` is an exact match.** A number as stored: `9989xxxxxxxx`, no `+`, no spaces. Email is
  case-insensitive. A mask like `99890***4567` will not match — it has to be expanded to the full
  number. **Push tokens are deliberately not searchable.**
- `externalMessageId` is the source system's own id and is usually the fastest route.
- `correlationId` stitches together every message of one business event (FR-8.6) — use it when the
  source system's log gave you one.

Put those three facts in the field help.

## Table

| Column | Source |
|---|---|
| Message id | `messageId`, copyable |
| Stream | `streamId` |
| External id | `externalMessageId` |
| Channel | `channel` |
| Status | `status` (`MessageStatus`) |
| Recipient | `recipient` — **already masked by the server for VIEWER**; render as received |
| Provider | `routing.provider` |
| Accepted | `acceptedAt` |

Row click opens the card.

Do not mask the `recipient` field client-side. It is either full (ADMIN, OPERATOR) or already masked
(everyone else), and masking a mask produces `99890***`.

## Card

`GET /messages/{messageId}` → `MessageCard`.

- Header: message id, stream, external id, batch id, status, `reason`.
- `delivery` block: channel, provider, segments, cost, accepted at, terminal at, correlation id.
- **`delivery.test: true` → a visible TEST marker.** This is a configuration check, not a customer
  message, and an operator reading a timeline needs to know which (FR-7.4).
- `history[]` as a **timeline**: status, reason, detail, occurred at. Colour `FAILED`, `REJECTED` and
  `UNDELIVERED` as failures and `DELIVERED` as success.

### Two things the card does not have, and never will

**No message content.** It is encrypted at rest and does not leave the backend (DB-04). Do not add a
placeholder implying it is coming.

**No recipient address.** The card body is exactly the §8.2 document, which carries neither. The
address is on the list row, where masking applies.

### The card is the same document a source system polls

`GET /messages/{id}` here returns *exactly* the body of the §8.2 endpoint. If a source system claims
one thing and the panel shows another, that is a defect — not two presentations of the same data. It
is worth stating on the screen, because during an incident that identity is what stops an argument.

### Push is terminal at `SENT_TO_PROVIDER`

Neither APNs nor FCM reports delivery (PU-12). A push message that never leaves `SENT_TO_PROVIDER` is
finished, not stuck. Annotate the timeline rather than leaving an operator waiting for a `DELIVERED`
that cannot come.

## States

- No results → "nothing matches these filters" with the filters intact.
- Unknown `messageId` → 404 `NOT_FOUND` → a not-found card.
- 403 on the list would mean the role map is wrong; show it rather than hiding the screen.

## Do not

- Do not poll this list. Each request is an audit entry.
- Do not offer a "customer lookup" mode. This is a message search that happens to accept an address,
  and turning it into a directory is exactly what SEC-08 is written against.
- Do not export the message list. There is no export endpoint for it, deliberately.
