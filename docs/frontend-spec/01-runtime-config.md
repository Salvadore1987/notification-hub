# 01 — Runtime configuration

The panel is built once and deployed to several contours (local stand, test, production). Everything
that differs between them is **runtime** configuration, read from a static file before the first
render. Nothing contour-specific may be baked in at build time.

## `config.json`

Served from the panel's own origin at `/config.json`.

```json
{
  "apiBaseUrl": "/api/admin/v1",
  "oidc": {
    "authority": "http://localhost:8180/realms/commhub",
    "clientId": "commhub-admin",
    "scope": "openid profile"
  },
  "rolesClaim": "groups",
  "groupRoles": {}
}
```

| Field | Type | Meaning |
|---|---|---|
| `apiBaseUrl` | string | Base URL of the admin BFF. Relative (`/api/admin/v1`) when a reverse proxy fronts both; absolute for a cross-origin contour. |
| `oidc.authority` | string | OIDC **issuer** URL. Discovery runs against `{authority}/.well-known/openid-configuration`. |
| `oidc.clientId` | string | Public client id registered at the issuer. `commhub-admin` in the shipped realm. |
| `oidc.scope` | string | Space-separated scopes requested with the grant. `openid profile`. |
| `rolesClaim` | string | Token claim carrying the SSO groups. Mirrors the backend's `commhub.security.roles-claim`; default `groups`. **Must match the backend** or the panel and the API will disagree about who the operator is. |
| `groupRoles` | object | SSO group name → §10.1 role name. Empty when groups are already named after the roles (the shipped realm is). |

### Built-in defaults

Every field has a default, so a partial `config.json` is legal. The defaults are the ones above with
**one deliberate exception: `oidc.authority` defaults to the empty string.**

### `scope` has no `offline_access` on purpose

A public browser client does not need a token that outlives the SSO session. An ordinary refresh token
is enough for silent renewal, and it dies with the session at the issuer — which is what sign-out is
supposed to achieve.

## Bootstrap order

This order is not a style preference; getting it wrong produces a client that sends requests to the
wrong base URL or without a token.

1. `fetch('/config.json', { cache: 'no-store' })`. No-store matters: a contour that re-points its
   issuer must not be defeated by a cached config in an operator's browser.
2. Merge over the defaults (shallow, with `oidc` merged one level deep).
3. Create the API client from `apiBaseUrl`, and install the auth and error middleware **at creation
   time**.
4. Restore any existing session (see `02-authentication.md`).
5. **Only then** render.

Nothing may render before step 5, and no request may leave before step 3.

## Failure handling

**`config.json` missing, unreachable or unparseable** → do not crash, do not render a blank page. Log
the failure to the console (whoever deployed it needs the fact, not only the symptom) and fall back to
the defaults. Because the default `authority` is empty, the fallback lands on the next rule:

**Empty `oidc.authority` is a misconfiguration, not a mode** (ADR-0037). The panel must render a
terminal error state — a page saying the contour is not configured — and must let nobody in. There is
no unauthenticated mode, no read-only mode and no dev bypass. This mirrors the backend, which refuses
to start at all without `spring.security.oauth2.resourceserver.jwt.issuer-uri`.

Do not "helpfully" guess an issuer from the API base URL, and do not offer a form to type one.

## Local stand

`docker compose up -d` brings up a Keycloak on `http://localhost:8180` with realm `commhub`, imported
from `docker/keycloak/commhub-realm.json`. That is the same file the backend's integration tests load
into their container, so it cannot drift from what the tests prove.

Local `config.json`:

```json
{
  "apiBaseUrl": "/api/admin/v1",
  "oidc": {
    "authority": "http://localhost:8180/realms/commhub",
    "clientId": "commhub-admin",
    "scope": "openid profile"
  },
  "rolesClaim": "groups",
  "groupRoles": {}
}
```

Users in that realm (password = login): `demo` (ADMIN), `operator`, `template-manager`, `analyst`,
`viewer`, `auditor`. The realm's redirect URIs and web origins allow `http://localhost:5173`,
`http://127.0.0.1:5173` and `http://localhost:8080` — a dev server on a different port needs the realm
updated, or CORS will refuse the discovery request.

## What must *not* be in `config.json`

- Client secrets. The panel's client is public; a secret in a file the browser downloads is not a
  secret.
- Feature flags that change what a role may do. Authorization is the backend's, not a JSON file's.
- API paths per screen. There is one base URL; the rest comes from the OpenAPI document.
