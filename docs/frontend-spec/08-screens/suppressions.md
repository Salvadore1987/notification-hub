# Suppression list

**Route** `/suppressions` · **Roles** ADMIN, OPERATOR

| Endpoint |
|---|
| `GET /suppressions` |
| `GET /suppressions/check` |
| `POST /suppressions` |
| `POST /suppressions/import` |
| `DELETE /suppressions/{entryId}` |

The list of addresses the Hub must not write to (FR-5.1).

## Layout

An **address check** card at the top — it is the action the screen is opened for most often — then
filters, action buttons and the table.

## Card — address check

```
GET /suppressions/check?channel=SMS&address=998901234567
GET /suppressions/check?channel=SMS&clientId=CL-0001
```

`channel` is **required**; supply `address` or `clientId`.

Response `SuppressionCheck { channel, suppressed, entry }`. Answer directly:

- "sending is allowed", or
- "sending is blocked" with the reason and the term — `validUntil`, or "indefinitely" when it is null.

Mask the operator-typed address in the answer (`07-conventions.md`).

This is the answer to both "the customer still gets messages although they unsubscribed" and "the
customer gets nothing although they should".

## Filters and table

Filters: `channel` · `reason` · `clientId`.

**There is no address filter, and there will not be one.** The table stores SHA-256 hashes, not
addresses (DB-04) — "show me this number" is the check above, not a filter. Do not add a search box
that quietly does nothing.

| Column | Source |
|---|---|
| Channel | `channel` |
| Address hash | `addressHash` — truncated with a copy action |
| Client id | `clientId` |
| Reason | `reason` (`SuppressionReason`) |
| Valid until | `validUntil` — "indefinitely" when null |
| Created by | `createdBy` |
| Created | `createdAt` |
| Actions | Delete |

Because the table shows hashes, **a row cannot be found by eye**. Say so on the screen and point at
the check card; otherwise operators scroll looking for a phone number that is not there.

### Some rows appear by themselves

A provider reporting a number blacklisted or non-existent, an email hard bounce, an expired push
token — those entries are created automatically, and `createdBy` carries the **provider code** rather
than an operator login. Render that distinction; it changes how the row is interpreted.

## Ban an address

`POST /suppressions` (`SuppressionRequest`):

| Field | Rule |
|---|---|
| `channel` | select |
| `address` **xor** `clientId` | **exactly one of the two.** Enforce it in the form. |
| `reason` | required. In practice an operator picks `MANUAL`, `OPT_OUT` or `COMPLAINT`; `HARD_BOUNCE`, `PROVIDER_BLACKLIST` and `PUSH_TOKEN_INVALID` normally arrive automatically. Offer all seven, with a hint. |
| `validUntil` | optional; empty means indefinitely |

The address is sent **in the clear** and hashed server-side after being validated by the channel's own
value object — so a mistyped number is refused here rather than becoming a hash that matches nothing
for ever. A 400 on this form is usually a malformed address; attach it to the field.

409 means the entry already exists for that exact channel scope.

## Import CSV

`POST /suppressions/import`, body `text/csv`. Required columns by header name: `channel`, `reason`,
plus one of `address` / `clientId`.

Every row goes through the same use case as a manual add — same validation, same hashing, same audit
entry. **An already-existing entry counts as skipped, not as an error**: loading the same file twice
must be safe.

Result `ImportResult { imported, skipped, failures[] { line, reason } }`.

## Remove an entry

```
DELETE /suppressions/{entryId}          X-Commhub-Reason
```

Confirmation plus a justification.

Say what this means: **removing an entry is opt-in after opt-out.** The operator is asserting that the
customer agrees to receive messages again. That is why it asks for a justification and why it lands in
the audit journal — and why "just in case" is the wrong reason to do it.

## Errors

| Situation | `code` |
|---|---|
| Neither or both of address/clientId | `VALIDATION_FAILED` (400) — prevent in the form |
| Malformed address | `VALIDATION_FAILED` (400) with `field` |
| Entry already exists | `CONFLICT` (409) |
| `check` without `channel` | `VALIDATION_FAILED` (400) — the field is required |
| Deleting an entry already gone | `NOT_FOUND` (404) — re-fetch |
