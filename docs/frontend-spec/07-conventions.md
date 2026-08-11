# 07 — Cross-cutting conventions

Rules that apply on every screen. Each screen file assumes these and does not repeat them.

## Time

**Storage and transport is UTC. Display is `Asia/Tashkent`, fixed.**

Not "the browser's timezone". The Hub serves one country; a laptop whose clock is on holiday in Dubai
must not make the operator read shifted timestamps, and two operators comparing screens must see the
same numbers.

- Render: `DD.MM.YYYY HH:mm:ss` for instants, `DD.MM.YYYY` for dates, both in `Asia/Tashkent`.
- Absent instant → `—`. Never an empty cell, never `null`, never the epoch.
- Period pickers operate in `Asia/Tashkent` and emit **UTC ISO-8601** on the wire.
- Prefer omitting `from`/`to` to computing "the last 24 hours" client-side — the server's default is
  computed against the clock the data was written with. See `04-api-contract.md`.

The one place a different zone appears is a **quiet-hours window**, which carries its own IANA zone
(default `Asia/Tashkent`). Render those `HH:mm` values in the window's zone and label it — a window
that crosses midnight (`21:00`–`09:00`) is normal and must not be shown as invalid.

## Money

`Money` is a **string**: `"12.5000 UZS"` — amount and currency together.

Never parse it into a float to re-render it. Four decimal places are meaningful: per-segment SMS
tariffs multiplied over a fifty-thousand-recipient batch are where the rounding shows up. Display the
string; if you must align a column, align on the space.

Where a form takes a tariff or a cost quota, the request carries **a number** and the currency is
implied (`UZS`). Do not send a formatted string back.

## Rates

`deliveryRate` and `completionPercent` are doubles. Decide once whether your codebase treats them as
`0..1` or `0..100`, verify against a live response, and be consistent — this is a classic source of a
progress bar stuck at 1%.

## Identifiers

Validate in the form, before the request. The server validates too, but a 400 after a submit is a
worse way to learn about a capitalisation rule.

| Identifier | Pattern | Example | Note |
|---|---|---|---|
| `streamId` | `^[a-z0-9][a-z0-9._-]{1,63}$` | `mobile-app` | **lowercase** — `PlayMobile` is refused |
| provider `code` | `^[A-Z0-9][A-Z0-9_]{1,31}$` | `SMS_GATE` | **uppercase**, underscore only |
| template `code` | `^[A-Z0-9][A-Z0-9._-]{1,63}$` | `OTP_LOGIN` | **uppercase** — the opposite case rule to a stream id |

The stream/template case inversion catches people. Say so in the field hint.

Other identifiers are UUIDs (`messageId`, `batchId`, `providerId`, `policyId`, `entryId`,
`templateId`, `versionId`) — display them monospace and make them copyable; an operator pastes them
into tickets and log searches all day.

## Masking

The server masks addresses by role (see `03-authorization.md`). The panel masks in exactly one
situation: **echoing back a value the operator just typed**, in a confirmation dialogue — the test-send
confirmation and the route dry-run summary. A confirmation screenshot pasted into a ticket should not
carry a customer's phone number.

Mirror the backend's algorithm so the two agree:

- **MSISDN**: first 5 characters + `***` + last 4 → `99890***4567`. Seven characters or fewer → `***`.
- **Email**: first + `***` + last of the local part, then `@domain` → `i***n@bank.uz`. Local part of
  two characters or fewer → `f***@domain`.
- Blank → `-`.
- Dispatch on the presence of `@`.

Never mask a value that came from the API — it is already masked or deliberately not.

## The justification prompt

Twelve operations accept `X-Commhub-Reason`, two require it (`04-api-contract.md`). Implement the
prompt **once**, as a modal that resolves a promise, and reuse it everywhere:

```
askReason(title) → Promise<string | null>
  null  → the operator cancelled. THE ACTION MUST NOT HAPPEN.
  ''    → confirmed with no justification. Send the request, omit the header.
  '…'   → confirmed. Send the request with the header, percent-encoded.
```

The `null` versus `''` distinction is load-bearing: cancelling the prompt has to abort the action, and
an implementation that treats a falsy value as "no reason given" pauses batches that nobody meant to
pause. Where the reason is mandatory, make the confirm button require a non-empty value instead of
sending a blank one.

## Tables

Server-driven, uniformly:

- The table component owns **page, page size and sort**, and takes a fetch function.
- **Filters belong to the screen**, and the fetch function closes over them. Changing a filter
  invalidates the table and resets to page 1.
- Guard against **stale responses**: a slow page-1 request must not overwrite a fast page-2 one.
  Sequence the requests and drop out-of-order answers.
- Show `total` from the response, not the number of rows on screen.
- Errors render inside the table with a retry action, not as a disappearing toast — a table that
  silently shows nothing is indistinguishable from a table with no data.
- Empty state says "nothing matches these filters", not "no data".
- Respect the per-list `limit` ceilings from `04-api-contract.md`.

The DLQ is the one list that needs row selection; give it whatever it needs, but keep the paging
semantics identical.

## Forms

- Validate identifiers and required fields client-side; let everything else be the server's answer.
- On a `VALIDATION_FAILED` problem with a `field`, attach the message to that field.
- On `CONFLICT`, **re-fetch the object and re-render** before showing the error: the operator is
  looking at stale state, and the second attempt on refreshed data usually succeeds or explains
  itself.
- A form action that failed must leave the form filled in. Nobody retypes a fifty-thousand-row upload
  configuration because a stream id was wrong.
- Never submit on Enter for a destructive or send action.

## Reference data

Streams, providers, deployed adapters and the template catalogue are used as **pickers** on several
screens. Fetch them through a small cache (a short TTL, in-flight de-duplication) so opening a form
does not re-fetch four lists.

**On failure, degrade to a free-text input — never block the form.** A broken or forbidden reference
lookup must not make an otherwise valid action impossible; the server validates the value anyway. This
matters most on the Send screen, where an OPERATOR can read streams and templates but not providers.

## Internationalisation

- **RU, UZ, EN. Russian is the default and the fallback.**
- Persist the choice locally; set `<html lang>` to match, so screen readers and browser tooling follow.
- **Do not translate enum values, identifiers, provider codes, template codes, stream ids or error
  `code`s.** Translate labels, headings, hints, buttons and the sentences you compose around a `code`.
- Keep the three dictionaries structurally identical: same keys, same interpolation placeholders.
  `09-testing.md` makes that a test.
- Date, number and picker locales must follow the chosen language.

## Accessibility

WCAG 2.1 level A/AA is the floor. Four things break most often here and must be got right:

- **Every filter control has an accessible name.** For most component libraries a placeholder is not a
  label; add an explicit one.
- **Row checkboxes are named** — "select message 018f-…", not an anonymous checkbox in every row.
- **Progress bars are named** and expose their value.
- **`<html lang>` follows the language switch**, which itself is a named control.

Test with an automated auditor over the main screens plus the login form (`09-testing.md`). If your
component library emits markup that trips a rule, record it as a known exception with the selector —
do not disable the rule globally.

## Things to keep out

- No client-side sorting or filtering of a server-paged list. It sorts one page and looks like it
  sorted everything.
- No optimistic updates on state transitions. Batch pause, kill switch and template publication can be
  refused by the server for reasons the panel cannot see; render the server's answer.
- No auto-retry of failed writes. A retried `POST /send/message` is a second SMS.
- No polling of the message list. Each search writes an audit entry (SEC-08).
- No storage of API data in `localStorage`. It outlives the session that was allowed to see it.
