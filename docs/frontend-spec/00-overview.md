# 00 — Overview

## What the Hub is

**Notification Hub** is the Bank's centralised transport and orchestration layer for client and
service notifications across **SMS, Email and Push**. Source systems (mobile app, CRM, card
processing, …) submit already-formed messages over Kafka or REST; the Hub does routing, load
balancing, fallback, templating, filtering, delivery through provider adapters, status aggregation,
retries, statistics and audit.

The Hub does **not** build audiences, does not write content and does not run campaign logic. Whatever
the panel shows, it is showing the transport layer's own state.

## What the panel is

The panel is the **operator interface to that transport layer** — a client of one backend-for-frontend
API, and nothing else. It has no database, no business rules of its own and no second source of truth.
Every decision it appears to make is either presentation or a duplicate of a check the backend makes
anyway.

Concretely, the panel exists so that a person can:

- **see** what is happening right now (dashboard, message search, batch progress, provider health);
- **intervene** in traffic already in flight (pause a batch, retry a dead letter, stop everything);
- **configure** the routing that decides where the next message goes (streams, channels, providers,
  routing policies, quotas, quiet hours);
- **curate content** (the template catalogue and its four-eyes review workflow);
- **enforce compliance** (the suppression list);
- **send** a message or a bulk list by hand, from a published template;
- **account for it all** (statistics, exports, the audit journal).

Those are the thirteen sections of SRS §11.2, and they are the thirteen screens of `08-screens/`.

## Scope

| In scope | Out of scope |
|---|---|
| Everything under `/api/admin/v1` | The source-system API `/api/v1` (§8.2) — the panel never calls it |
| The thirteen §11.2 sections | Anything requiring a backend change |
| RU / UZ / EN interface, RU default | Additional locales |
| CSV import and export | XLSX (deliberately not produced — see below) |
| OIDC sign-in against the Bank's issuer | User provisioning, password reset, group management |

### Explicit non-goals, each with its reason

**No user or role management.** SRS §10.1: the Hub stores no users and no passwords. Identity comes
from the corporate SSO, and the SSO group → application role mapping is deployment configuration. The
administration screen therefore *displays* that map read-only and offers no way to edit it. A panel
that grew a user list would be a second identity store with no lifecycle.

**No XLSX export.** Exports are CSV, UTF-8 with a BOM. This was decided when the export was built: an
XLSX writer is a dependency and a rendering surface for something Excel opens from CSV anyway.

**No access to the source-system API.** `/api/v1` is a published contract that the Bank's systems
integrate against. The panel's backend is `/api/admin/v1`, a separate API shipped with the panel. The
two overlap deliberately in one place — the message card and the batch card return *exactly* the §8.2
body, so the screen an operator reads during an incident and the answer a source system polled are the
same document — but the panel still reads it from the admin API.

**No client-side authorization decisions.** The panel hides what a role cannot use. The backend
refuses what a role may not do. The second one is the security control; the first is politeness. See
`03-authorization.md`.

**No unauthenticated mode.** There is no configuration flag, no dev bypass and no "open" contour
(ADR-0037). A missing issuer is a misconfiguration that lets nobody in, not a mode.

## Deployment shape

The panel is a static SPA. It is built once and configured at runtime from `config.json` (see
`01-runtime-config.md`), so the same artifact runs on the local stand and in production. It is
normally served from the same origin as the API — the reference implementation proxies `/api` to the
backend in development and expects a reverse proxy to do the same in a real contour, which is why the
default `apiBaseUrl` is the relative `/api/admin/v1`. Cross-origin deployment works too, but then the
contour has to arrange CORS on the backend, which it does not do by default.

The panel talks to exactly two hosts: the API and the OIDC issuer.
