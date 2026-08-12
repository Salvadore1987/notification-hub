# Audit

**Route** `/audit` · **Roles** ADMIN, SECURITY_AUDITOR

| Endpoint |
|---|
| `GET /audit` |
| `GET /audit/export` |

The journal of who did what (FR-7.3, SEC-08).

## Filters

Period · `username` · `action` · `entityType` · `entityId`, with an explicit **Search**.

**No default period** — like the DLQ, this list is often read to find something old. Leave the period
empty unless the auditor sets one.

`action` and `entityType` are **free text**, not dropdowns: the journal's vocabulary is open and a
fixed list would hide entries added by a later release.

Three questions the filters answer, worth naming on the screen:

| Question | Filter |
|---|---|
| What did this person do? | `username` |
| Who touched this object? | `entityType` + `entityId` |
| **Who has been looking at this customer?** | `entityId` = the **address hash** |

The third is why SEC-08 records a search against an address hash rather than a message id — it makes
"who has been looking at this customer" a single indexed query. A raw address in an append-only
journal would be a second store of customer addresses with no retention story.

## Table

| Column | Source |
|---|---|
| When | `occurredAt` |
| User | `username` |
| Action | `action` |
| Entity type | `entityType` |
| Entity id | `entityId` |
| Change | `change.before` → `change.after`; render an empty value as `∅`; full text in a tooltip or an expander |
| Reason | `reason` — the operator's `X-Commhub-Reason` justification |
| IP | `sourceIp` |

Server-paged, newest first. `change` is empty on both sides for creations, deletions and reads.

**Reading the journal is not itself audited** — otherwise it would grow from being looked at. So
paging freely through it is fine.

## Export

`GET /audit/export` with the same filters, no paging — the query is walked to the end. Save as
`audit.csv`.

Columns: `occurredAt, username, action, entityType, entityId, before, after, reason, sourceIp`.

### `X-Commhub-Truncated` — do not swallow it

The response may carry `X-Commhub-Truncated: true`, meaning the walk stopped at the **50 000-row
ceiling**.

The file still downloads, and it is **incomplete**. Show a visible warning next to the download —
"the export stopped at the 50 000-row ceiling; narrow the period" — because an unannounced cap is a
file that looks complete and is not, and for an audit export that is the only failure mode that
matters.

Reading the header requires access to the raw response, so make sure the export call is not routed
through a helper that discards headers.

## What the screen must make clear

**The journal is immutable at the database level.** Update, delete and truncate are refused by the
database itself (a journal an administrator can empty is not a journal). There is no edit action here,
and there is no way to add one.

## Errors

403 means the caller lacks ADMIN and SECURITY_AUDITOR — show it. 400 comes from a malformed period.
