# 02 — Authentication

**The panel has its own login form.** Username and password go straight to the issuer's token endpoint
as a direct access grant (`grant_type=password`, RFC 6749 §4.3). There is no redirect to the issuer, no
authorization-code exchange, no `/auth/callback` route and no `returnTo` parameter. (ADR-0043.)

## Why, and what it costs

The decision was taken so that the operator never leaves the Hub's address: an internal panel that
bounces to a Keycloak login page on a different host looks, to a bank employee trained to be
suspicious, exactly like the thing they are trained to be suspicious of.

The price is written down rather than discovered:

- the password passes through the SPA, where an authorization-code flow would never have shown it;
- the resource-owner password grant is **removed in OAuth 2.1** and is deprecated practice;
- a contour with MFA, an AD federation, or any issuer-side step-up **cannot use it** — the grant has
  nowhere to put a second factor.

If such a contour appears, the answer is to move to a hosted login page (a themed Keycloak login is
the obvious one), not to bolt a second factor onto this form. A new implementation may make that
choice differently — but if it does, it must say so, because the backend does not care either way:
**the backend was not changed at all for this and validates any valid token from the configured
issuer.**

## The protocol, end to end

All of it should live in exactly one module. Nothing else in the application should know that OIDC
exists.

### 1. Discovery

```
GET {authority}/.well-known/openid-configuration     (cache: no-store)
```

Take `token_endpoint` (required) and `end_session_endpoint` (optional). Cache the result for the
lifetime of the tab — the document does not change under a running page.

**Cache the success, never the failure.** If discovery fails, evict the cached promise, so that
pressing "Sign in" again is a genuine retry and not a replay of the same error.

Do not construct the token endpoint by string concatenation from the issuer. It happens to be
predictable for Keycloak; hard-coding it hard-codes Keycloak into the panel, and `authority` is
deployment configuration precisely so that it does not have to be.

### 2. Sign-in

```
POST {token_endpoint}
Content-Type: application/x-www-form-urlencoded

grant_type=password&client_id={clientId}&scope={scope}&username=…&password=…
```

Success gives `access_token`, usually `refresh_token`, and `expires_in`. Convert `expires_in` to an
**absolute** `expiresAt = now + expires_in * 1000` at the moment of issue, and store that. A relative
lifetime carried around is a lifetime that silently stops being true.

If `expires_in` is absent, assume **60 seconds**. Too short is safe (an early refresh); too long is a
window in which every request 401s.

### 3. Renewal

```
POST {token_endpoint}
grant_type=refresh_token&client_id={clientId}&refresh_token=…
```

Schedule it for **30 seconds before `expiresAt`**, with a floor of 1 second so a nearly-expired token
does not schedule in the past. On success, replace the stored token set and reschedule. On failure,
clear the session and show the login form with a "session expired" notice — do not retry in a loop and
do not leave the operator on a screen whose next click will 401.

The password is never stored, so renewal is the only way to extend a session.

### 4. Sign-out

1. Clear the local store and the in-memory token immediately.
2. Then `POST {end_session_endpoint}` with `client_id` and `refresh_token`, **best effort** — swallow
   every error.

Order matters: the local sign-out has already happened, and the issuer being unreachable must not
leave an operator who pressed "Sign out" still signed in. Ending the session at the issuer is still
mandatory to attempt: a direct grant creates a session there, and leaving it alive leaves open a door
the operator believes they closed.

## Token storage — `sessionStorage`, not `localStorage`

SEC-02. The session dies with the tab. This is a shared-workstation control and is not negotiable.

Store the whole token set (`accessToken`, `refreshToken`, `expiresAt`) under one key. On read,
validate the shape (`accessToken` is a string, `expiresAt` is a number) and treat anything else as
absent. Wrap every storage access so a browser with storage disabled degrades to "no session
restored" rather than throwing during bootstrap.

Two consequences that follow from this and are easy to get wrong:

- **A second tab is a second sign-in.** That is intended.
- **End-to-end tests cannot reuse a saved storage state.** See `09-testing.md`.

## Session restore on load

After config load, before first render:

- no stored session → show the login form;
- stored session with `expiresAt - 30s > now` → use it as-is;
- stored session expired but with a refresh token → refresh silently, showing a loading state;
- expired with no refresh token → clear and show the login form.

F5 must not ask for a password again while the session is alive.

## Deep links survive login for free

**The login form renders in place of the page content, at the current URL.** There is no navigation to
a `/login` route and no `returnTo` to preserve. An operator who opens
`/messages?batchId=018f-…` unauthenticated sees the form at that address, signs in, and the message
list renders — because the router never moved.

Implement it as a gate around the authenticated content, not as a route.

## Attaching the token

Every request to `/api/admin/v1/**` carries `Authorization: Bearer {accessToken}`. Apply it in
request middleware on the shared client, once — never at a call site.

Requests to the issuer (discovery, token, end-session) carry no bearer token.

## Failure modes the operator must be able to tell apart

The issuer's answers collapse into three, and the screen must distinguish them because the fix differs:

| Failure | Detected by | What the operator is told |
|---|---|---|
| `invalidCredentials` | HTTP not-ok **and** `error === "invalid_grant"` | "Wrong username or password." Nothing more — Keycloak answers identically for an unknown user and a wrong password, and that is correct behaviour the panel must not undo. |
| `issuerUnavailable` | `fetch` threw · discovery not-ok · discovery without `token_endpoint` | "The authentication service is unavailable." This is a contour fault, not an operator mistake. |
| `rejected` | any other not-ok, or 2xx without `access_token` | Show the issuer's own `error_description` (or `error`) **verbatim**. "Account is not fully set up" tells the operator more than any wording of ours. |

**The password must appear in no error message, no log, no console line and no store.** The only call
that ever sees it is the token request itself.

## What the backend expects of the token

- Signed by the configured issuer; the backend validates it as a JWT resource server.
- Roles come from the **`groups` claim** (name configurable, mirror it in `config.json`).
- Group names arrive **short, without a path** — `ADMIN`, not `/ADMIN`. The backend uppercases the
  claim value and prefixes `ROLE_`, stripping nothing, so a `/`-prefixed group becomes the useless
  authority `ROLE_/ADMIN`. In the shipped realm this is handled by the group mapper's
  `full.path=false`; a contour wiring its own SSO must reproduce it.
- The operator's name for the audit journal is taken from `preferred_username`.

## Reading claims in the panel

The panel decodes the access token payload **without verifying the signature** — base64url-decode the
middle segment, parse the JSON, and fall back to an empty claim set on any failure. This is legitimate
because nothing security-relevant depends on it: the claims drive which menu items render, and the
backend re-derives everything from the verified token on every call. Do not build any refusal on a
locally-decoded claim that the backend does not also enforce.

From the claims take:

- `claims[rolesClaim]` → roles (see `03-authorization.md` for the parsing rules);
- `preferred_username ?? sub` → the name shown in the header.

## Keycloak client requirements

For reference, what the shipped realm configures for `commhub-admin`, and what any other issuer must
match:

- **public client** (no secret);
- **direct access grants enabled** — without this the password grant returns `unauthorized_client`;
- standard flow enabled, implicit disabled, service accounts disabled;
- web origins including the panel's origin, or discovery and the token call fail on CORS;
- a group-membership mapper emitting `groups` with `full.path=false`, present in the access token
  (the backend reads it) and the id token (the panel reads it);
- every user has an email — otherwise Keycloak's Verify Profile required action fires and the password
  grant answers "Account is not fully set up" rather than a token.
