# Notification Hub — Admin Frontend Specification

This folder specifies **an admin panel for the Notification Hub, complete enough to build one from
scratch on any stack, such that it drops onto the running backend without a single backend change.**

It is written for a generator — a person or a model — that has the backend available but has never
seen the existing SPA. It is deliberately stack-agnostic: it says what the application must do, what
it must send, what it must render and what it must refuse, and leaves the choice of framework,
component library and state manager to whoever builds it.

> **Language note.** The rest of `docs/` is Russian. This folder is English on purpose: it describes
> an English contract (endpoints, schemas, enums, error codes) and is meant to be fed to a code
> generator. The *product* it describes still ships a Russian-first UI — see `07-conventions.md`.

## The standing rule

**The OpenAPI document is the contract. This folder is the behaviour around it.**

```
adapter/in/admin/src/main/resources/openapi/comm-hub-admin-v1.yaml
```

also served by the running application at `GET /api/admin/v1/openapi.yaml`. Generate your types and
your client from that file, not from prose in here. Where this folder and the yaml disagree, the yaml
wins and this folder is the bug — report it.

What this folder adds is everything the yaml cannot say: how to authenticate, which role may press
which button, what a `null` means on each screen, why the Channels tab does not render the response it
just fetched, and which of the seemingly-arbitrary rules are load-bearing.

## Reading order

| File | What it settles |
|---|---|
| `00-overview.md` | What the Hub is, what the panel is, what is deliberately out of scope |
| `01-runtime-config.md` | `config.json`, bootstrap order, what an unconfigured contour must do |
| `02-authentication.md` | Sign-in, token lifecycle, storage, sign-out, failure modes |
| `03-authorization.md` | Six roles, section→role map, full endpoint→authority matrix, masking |
| `04-api-contract.md` | Base URL, paging, periods, custom headers, CSV, the traps |
| `05-error-model.md` | problem+json, all 25 codes, and the UI treatment each deserves |
| `06-vocabulary.md` | Every enum on the wire, exact constants, rendering rules |
| `07-conventions.md` | Time, money, identifiers, i18n, tables, forms, accessibility |
| `08-screens/` | `00-navigation.md` plus one file per §11.2 section |
| `09-testing.md` | What to test at which level, and how to fake the backend |
| `10-acceptance.md` | The checklist that decides whether the result is a drop-in replacement |

Read `00`–`07` once, front to back, before writing any code. Then treat `08-screens/*` as the
per-screen work orders — each is self-contained and repeats the cross-cutting rules it depends on.

## How to use this with a generator

1. Start the backend locally (`docker compose up -d`, `./gradlew :bootstrap:bootRun`) or take the yaml
   from the repository.
2. Run your stack's OpenAPI typegen against `comm-hub-admin-v1.yaml`. Commit the output. Do not
   hand-write request or response types — there are 77 schemas and they change with the backend.
3. Feed `00`–`07` as the project's standing rules, then one `08-screens/*` file per screen task.
4. Verify against `10-acceptance.md` before calling it done.

## Reference implementation

`web/` in this repository is a working implementation of exactly this specification (React 18 +
TypeScript + Ant Design 5 + Vite). It is **not** normative — where it and this folder differ, this
folder is what the next implementation should follow — but it is a useful second opinion when a rule
here is ambiguous, and its end-to-end tests encode the four acceptance scenarios of QA-07.

## Related documents

- `docs/sms-notification-hub-spec.md` — the SRS. §11.2 is the section list this panel implements;
  §10.1 is the role list; §6.3 is the status vocabulary.
- `docs/ADMIN-GUIDE.md` (RU) — the operator's guide to the panel as built. Useful for *why* a screen
  behaves the way it does.
- `docs/QUICKSTART-SEND.md` (RU) — empty database to delivered SMS. The panel must be able to walk
  this whole path; `10-acceptance.md` makes that a requirement.
- `docs/architecture/adr/` — ADR-0037 (no unauthenticated mode), ADR-0038 (operator send),
  ADR-0041 (mock provider), ADR-0042 (deployed adapters), ADR-0043 (own login form).
