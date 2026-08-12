# Templates

**Routes** `/templates` (catalogue) and `/templates/:code` (card) · **Roles** ADMIN, TEMPLATE_MANAGER

| Endpoint | Roles |
|---|---|
| `GET /templates` | A T **O** — the send form needs it |
| `POST /templates/import` | A T |
| `GET /templates/{code}` | A T |
| `POST /templates/{code}` · `PUT` · `DELETE` | A T |
| `POST /templates/{code}/restore` | A T |
| `PUT /templates/{code}/versions` | A T |
| `POST /templates/{code}/versions/{locale}/{version}/state/{status}` | A T |
| `POST /templates/{code}/preview` | A T |
| `PUT`/`DELETE` `/templates/{code}/providers/{providerCode}` | A T |

---

## The two statuses that get confused

**They are different things with different vocabularies, and mixing them up is the most common
misunderstanding on this screen.**

| | Values | Says |
|---|---|---|
| **Card** — `Template.catalogStatus` | `ACTIVE` · `ARCHIVED` | whether the template is still in the catalogue |
| **Version** — `TemplateVersion.status` | `DRAFT` · `ON_REVIEW` · `PUBLISHED` · `ARCHIVED` | whether anything can be sent from it |

A new card is **already `ACTIVE`** — there is nothing to switch. What makes a template sendable is
**publishing a version**, not the card. While the "published locales" column shows a dash, nothing can
be sent under that code however `ACTIVE` the card is.

Label the two columns so they cannot be read as the same field.

---

## Catalogue — `/templates`

Server-paged list. Filters: `channel` · `direction` (free text) · `catalogStatus`.
Buttons: **New template** · **Import CSV** · **Refresh**.

| Column | Source |
|---|---|
| Code | `code` |
| Channel | `channel` |
| Direction | `direction` |
| Owner | `owner` |
| Card status | `catalogStatus` |
| Published locales | `publishedLocales[]` — a dash when empty, and that dash means "not sendable" |

Row click → `/templates/{code}`.

**New template** — `POST /templates/{code}` with `TemplateRequest { channel, direction, owner }`.
`code` matches `^[A-Z0-9][A-Z0-9._-]{1,63}$`, **uppercase** — the opposite of a stream id. An existing
code answers 409.

**Import CSV** — `POST /templates/import?approver=…`, body `text/csv`:

- required columns by header name: `code`, `channel`, `locale`, `text`;
- idempotent by wording — the same file twice creates nothing new;
- bad rows are reported and the import continues;
- **`approver` must be a different person from the author** (the authenticated caller). Without a
  different approver nothing is published and everything stays a draft — the four-eyes rule of FR-4.2
  matters most during a bulk load. Say so next to the field.

Result `TemplateImportResult { created, imported, skipped, failures[] { code, locale, reason } }` —
show all four.

---

## Card — `/templates/:code`

`GET /templates/{code}` → `Template`.

Header: code, channel, direction, owner, card status, with **Edit** · **Preview** ·
**Archive template** (or **Restore template** when archived).

Then two cards: **Versions** and **Provider registrations**.

### Edit / archive / restore

| Action | Call | Reason header |
|---|---|---|
| Edit | `PUT /templates/{code}` (`direction`, `owner`) | no |
| Archive | `DELETE /templates/{code}` | yes |
| Restore | `POST /templates/{code}/restore` | yes |

**Archiving is what deleting means here.** The card keeps its versions, stops being sendable and can
be restored. The code stays in the history of every message rendered from it, so it is never truly
deleted. Label the button "Archive", never "Delete".

### Versions

One row per (locale, version): version number, locale, status, variables, `review.createdBy`,
`review.reviewedBy`, `review.publishedAt`, and the body.

**New draft** — `PUT /templates/{code}/versions` (`TemplateVersionRequest`):

| Field | Rule |
|---|---|
| `locale` | `RU` / `UZ` / `EN` |
| `version` | the draft being rewritten; `null` starts the next one |
| `text` | required; variables written as `{CODE}` |
| `subject` | email |
| `html` | **only together with `text`, and only on an email template** (EM-01) |

**There is no `author` field.** The author is the authenticated caller (FR-4.2). Do not add one; the
server ignores it.

**Edit is offered on a `DRAFT` only.** A version on review is what the second person is reading right
now; a published one is what already-sent messages were rendered from. Hide the edit action in every
other status rather than letting the server 409.

### Publication workflow

```
DRAFT → ON_REVIEW → PUBLISHED → ARCHIVED
POST /templates/{code}/versions/{locale}/{version}/state/{status}
```

| From | Buttons |
|---|---|
| `DRAFT` | Send to review (`ON_REVIEW`) |
| `ON_REVIEW` | Publish (`PUBLISHED`) · Reject (`DRAFT`) · Archive |
| `PUBLISHED` | Archive |
| `ARCHIVED` | — |

- **There is no `REJECTED` status.** A reviewer's refusal is a return to `DRAFT` — the version is one
  the author is still writing. (The contract once claimed `REJECTED` and `IN_REVIEW`, and the review
  button answered 400 forever as a result. Take the values from `06-vocabulary.md`.)
- **Publishing requires a second person** (FR-4.2): author and publisher must differ, and both come
  from the account, never from a form field. **Disable Publish on the caller's own version with an
  explanatory tooltip** — the point is that the operator learns this before pressing, not from a
  server refusal after.
- **Publishing v2 archives v1 in the same locale.** A locale has exactly one sendable version, always.
  Show that consequence in the confirmation.
- A transition the domain does not allow answers 409 — re-fetch the card and re-render.

### Preview

`POST /templates/{code}/preview` (`TemplatePreviewRequest { locale, version?, variables }`).

Response: `rendered` (`subject`/`text`/`html`), `missingVariables[]`, `segmentation
{ encoding, characterCount, segments }`, and `costs[] { providerCode, cost, selectable }`.

**The preview is deliberately non-strict and deliberately not status-checked.** An unfilled variable
stays visible as `{NAME}` and is listed in `missingVariables` — the screen exists to be used on a
draft. The *sending* path stays strict (`TEMPLATE_VARIABLE_MISSING`). Do not "improve" the preview by
refusing to render an incomplete draft.

Segments and costs come from the real segment calculator and the real provider tariffs (FR-4.4) —
never compute either client-side. Show `encoding`: a short Russian text rendering as `UCS2` with three
segments is correct and is exactly what an author needs to see.

### Provider registrations

For providers that require the wording to be pre-registered on their side (FR-4.5).

```
PUT    /templates/{code}/providers/{providerCode}   { providerTemplateId, approved }
DELETE /templates/{code}/providers/{providerCode}
```

`providerCode` is a picker over `GET /providers` — which TEMPLATE_MANAGER may read for exactly this
reason. `providerTemplateId` is the provider's own id and is free text (somebody else owns it).

Both return the whole `Template`; re-render the card from the response.

---

## Guidance to put on the screen

Do not "fix" a published wording in place — only a draft is editable. A new wording needs a new draft
and a new publication, otherwise nobody can say what yesterday's messages were rendered from.

## Errors

| Situation | `code` |
|---|---|
| Creating an existing code | `CONFLICT` (409) |
| Rewriting a non-`DRAFT` version | `CONFLICT` (409) |
| Publishing your own version | `CONFLICT` (409) — prevent it in the UI first |
| An impossible transition | `CONFLICT` (409) — re-fetch |
| Unknown code | `NOT_FOUND` (404) |
| HTML on a non-email template | `VALIDATION_FAILED` (400) |
