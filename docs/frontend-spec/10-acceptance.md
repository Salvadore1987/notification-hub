# 10 — Acceptance

The checklist that decides whether a generated panel is a drop-in replacement. Every item is
observable against a running backend; none of them requires reading the panel's source.

Preconditions: `docker compose up -d` and `./gradlew :bootstrap:bootRun`, with the panel served
against that backend and `config.json` pointing at `http://localhost:8180/realms/commhub`.

## A. Bootstrap and configuration

- [ ] `config.json` is fetched before the first render; the API client is created from `apiBaseUrl`.
- [ ] With `oidc.authority` emptied, the panel renders a "contour not configured" page and **lets
      nobody in** — no shell, no login form, no read-only mode.
- [ ] With `config.json` removed entirely, the same thing happens, and the failure is on the console.

## B. Authentication

- [ ] `demo` / `demo` signs in through the panel's own form. The browser never navigates to the
      issuer.
- [ ] A wrong password shows "wrong username or password" and nothing else.
- [ ] With Keycloak stopped, the failure reads as "the authentication service is unavailable" — a
      different message from the one above.
- [ ] **F5 does not ask for a password again** while the session is alive.
- [ ] **Closing the tab ends the session** — a new tab asks again.
- [ ] Opening `/dlq` unauthenticated shows the form **at that URL**, and signing in lands on the DLQ.
- [ ] Sign-out returns the form, and the Keycloak session is gone (a fresh sign-in is required).
- [ ] The password appears in no console line, no storage entry and no error text.

## C. Roles

- [ ] `viewer` / `viewer` sees Dashboard, Batches and Messages only.
- [ ] `viewer` sees recipient addresses **masked** (`99890***4567`) and **no** batch control buttons.
- [ ] `analyst` sees Statistics; `auditor` sees Audit; `template-manager` sees Templates.
- [ ] `demo` (ADMIN) sees all thirteen sections.
- [ ] A URL for a forbidden section renders a 403 page, not the screen and not a redirect.
- [ ] A 403 from the API renders an explanation and does **not** sign the operator out.

## D. Cross-cutting behaviour

- [ ] Timestamps render in `Asia/Tashkent` as `DD.MM.YYYY HH:mm:ss` regardless of the machine's
      timezone; absent instants render `—`.
- [ ] Statuses, channels and reasons render as the raw uppercase constants in **all three languages**.
- [ ] Switching language changes `<html lang>`.
- [ ] A period wider than 92 days is refused with a readable message, not a 400 dump.
- [ ] Cancelling a justification prompt **does not perform the action**.
- [ ] **A Russian justification arrives intact**: pause a batch with reason `разобрано вручную`, then
      find that exact text in the Audit journal. (This is the percent-encoding rule; getting it wrong
      makes the request throw before it is sent.)

## E. Screens reachable and complete

- [ ] All 60 operations of the contract are reachable from some screen, **except** the one waived
      below.
- [ ] **Waived:** `GET /dashboard/stream` — the SSE variant. Polling `GET /dashboard` is the
      contract's own recommendation and satisfies the requirement.

Spot checks per screen:

- [ ] Dashboard polls every 15 s and renders `otpLatencyP99Millis: null` as "no OTP traffic",
      never `0`.
- [ ] Batches: buttons match the status (`ACCEPTED` → Start/Stop, `PROCESSING` → Pause/Stop,
      `PAUSED` → Resume/Stop, otherwise none); the batch-messages link opens
      `/messages?batchId=…`.
- [ ] Messages: search fires on demand, not per keystroke; the card shows the timeline and **no
      content and no address**.
- [ ] DLQ: retry and archive send explicit id lists; archive asks for a reason and retry does not; the
      applied/skipped answer is rendered.
- [ ] Streams: the id field is disabled when editing; `clearQuietHours` is available and works.
- [ ] Providers → Channels: **on a fresh database, three rows render**, each "not configured" with a
      single Configure action and **no state buttons**.
- [ ] Providers: `adapterType` is a picker sourced from `GET /providers/adapters`; `channel` and
      `adapterType` are locked when editing.
- [ ] Routing: the enable toggle asks for no justification; the dry-run reports a rejection reason
      when there is no route.
- [ ] Templates: Publish is disabled on the caller's own version with an explanation; rejecting
      returns the version to `DRAFT`; archiving is labelled "archive", not "delete".
- [ ] Suppressions: no address filter exists; the check card answers allowed/blocked with the term.
- [ ] Statistics: export sends the same parameters as the screen; there is no XLSX option.
- [ ] Audit: `X-Commhub-Truncated: true` produces a visible warning next to the download.
- [ ] Administration: activating the kill switch requires a reason; `includeCriticalOtp` defaults to
      unticked every time; there is **no user management**.

## F. The end-to-end path

Following `docs/QUICKSTART-SEND.md` **from the panel alone**, on an empty database:

- [ ] register a provider (Providers tab);
- [ ] configure the SMS channel **including a non-empty fallback order** (Channels tab);
- [ ] register a stream (Streams);
- [ ] create a template, write a draft, send it to review, publish it as a second user (Templates);
- [ ] estimate and send one message (Send) and see it in Messages;
- [ ] upload the sample list `docs/samples/recipients-sms-ru.csv`, estimate, send, and manage the
      resulting batch in Batches;
- [ ] **re-upload the same file** and see the result counted as `duplicates`, not as new sends.

The order cannot be rearranged — a channel's fallback order names provider codes, and a channel with
an empty one looks configured and routes nothing. If any step is impossible from the panel, the panel
is incomplete.

## G. Error rendering

- [ ] `NO_ROUTE_AVAILABLE` (send before configuring a channel) reads as an explanation, not "500".
- [ ] `TEMPLATE_NOT_PUBLISHED` (send with an unpublished locale) names the template.
- [ ] `KILL_SWITCH` (send with the switch on) points at Administration.
- [ ] `DUPLICATE` reads as "nothing was sent again", not as a failure.
- [ ] A 500 shows a generic message plus a copyable correlation id.
- [ ] An unrecognised problem `code` still renders `title` and `detail`.

## H. Tests

- [ ] Unit and component tests cover the modules and components listed in `09-testing.md`.
- [ ] The eight end-to-end scenarios pass against a real Keycloak and a stubbed BFF.
- [ ] The accessibility audit passes on five screens plus the login form, with any component-library
      exceptions listed explicitly rather than by disabling rules.
- [ ] Enum lists and the three dictionaries are checked by tests.

## I. No backend changes

- [ ] `git status` on the backend modules is clean. The panel required no endpoint, no field, no
      header and no role that did not already exist.

That last item is the point of the whole exercise. If something could not be built without a backend
change, it is a gap in this specification — report it rather than changing the backend to match.
