# 03 — Authorization

## The load-bearing rule

**The backend decides. The panel only hides.**

Every endpoint carries a server-side role check (`@PreAuthorize`). The panel duplicates the same map
for one reason: so that an operator is not shown a menu item, a tab or a button whose only possible
outcome is 403. That duplication is presentation. It is not a security control, and no part of the
panel may treat it as one.

Practical consequences:

- Never gate a *destructive* action on the client check alone and assume the server agrees — send the
  request and render its answer.
- Never "unlock" anything from a locally-decoded claim.
- A 403 arriving on a screen the panel believed was permitted is a **bug in this map**, not something
  to paper over — surface it as an error, do not silently hide the screen.

## The six roles

From SRS §10.1. These exact strings, uppercase:

```
ADMIN  OPERATOR  TEMPLATE_MANAGER  ANALYST  VIEWER  SECURITY_AUDITOR
```

**`ADMIN` inherits nothing implicitly.** Every union below spells `ADMIN` out. That mirrors the
backend, where `AdminAuthority` writes `hasAnyRole('ADMIN', …)` for each grouping rather than granting
ADMIN a blanket pass. Do not "simplify" the client map by making ADMIN a wildcard: the day a grouping
legitimately excludes ADMIN, the wildcard hides it.

### Deriving roles from the token

Read `claims[config.rolesClaim]` (default claim: `groups`) and:

1. If the value is an **array**, keep the string elements. If it is a **string**, split on whitespace
   and commas. Anything else → no roles.
2. Map each entry through `config.groupRoles` (`groupRoles[group] ?? group`) — the identity map when
   the SSO groups are already named after the roles.
3. Keep only values that are one of the six role names. **Drop unknown groups silently** — a corporate
   directory carries hundreds of groups an operator happens to be in, and none of them are errors.
4. De-duplicate.

A token with no recognisable role is a valid session with access to nothing. Render the shell, render
no sections, and say so — do not sign the operator out and do not show an empty menu with no
explanation.

## Section → roles

Seven groupings cover every screen. This is the map the navigation and the route guards use.

| Grouping | Roles | Used by |
|---|---|---|
| `any` | all six | Dashboard |
| `admin` | ADMIN | Streams, Providers, Routing, Administration |
| `operator` | ADMIN, OPERATOR | Send, DLQ, Suppressions |
| `operatorOrViewer` | ADMIN, OPERATOR, VIEWER | Batches, Messages |
| `analyst` | ADMIN, ANALYST | Statistics |
| `auditor` | ADMIN, SECURITY_AUDITOR | Audit |
| `templateManager` | ADMIN, TEMPLATE_MANAGER | Templates (catalogue and card) |

Note that section gating is coarser than endpoint gating in two places, and that is intentional:

- The **Templates** section is gated `templateManager`, but `GET /templates` (the catalogue) also
  admits OPERATOR — because the send form needs to *choose* a template. An OPERATOR reaches that
  endpoint through the Send screen, not through the Templates menu item.
- The **Providers** section is gated `admin`, but `GET /providers` also admits TEMPLATE_MANAGER —
  because the template card's provider-registration form needs the list of provider codes.

So: gate the *menu* by section, and let the *data* be fetched by whichever screen legitimately needs
it.

## Endpoint → authority

The complete matrix, taken from the `@PreAuthorize` on each controller method. `A` = ADMIN,
`O` = OPERATOR, `T` = TEMPLATE_MANAGER, `N` = ANALYST, `V` = VIEWER, `S` = SECURITY_AUDITOR.

### Dashboard
| Operation | Roles |
|---|---|
| `GET /dashboard` | A O T N V S |
| `GET /dashboard/stream` | A O T N V S |

### Batches
| Operation | Roles |
|---|---|
| `GET /batches` | A O V |
| `GET /batches/{batchId}` | A O V |
| `POST /batches/{batchId}/actions/{action}` | A O |

### Messages
| Operation | Roles |
|---|---|
| `GET /messages` | A O V |
| `GET /messages/{messageId}` | A O V |

### DLQ
| Operation | Roles |
|---|---|
| `GET /dlq` | A O |
| `POST /dlq/retry` | A O |
| `POST /dlq/archive` | A O |

### Streams
| Operation | Roles |
|---|---|
| `GET /streams` | A O |
| `POST /streams/{streamId}` | A |
| `PUT /streams/{streamId}` | A |
| `POST /streams/{streamId}/suspend` | A |
| `POST /streams/{streamId}/resume` | A |

`GET /streams` admits OPERATOR so the send form can offer a stream picker rather than a text field.
Editing stayed with ADMIN.

### Channels
| Operation | Roles |
|---|---|
| `GET /channels` | A |
| `PUT /channels/{channel}` | A |
| `POST /channels/{channel}/state/{status}` | A |

### Providers
| Operation | Roles |
|---|---|
| `GET /providers` | A T |
| `GET /providers/adapters` | A |
| `POST /providers/{code}` | A |
| `PUT /providers/{providerId}` | A |
| `DELETE /providers/{providerId}` | A |
| `POST /providers/{providerId}/state/{state}` | A |
| `POST /providers/test-send` | A |

`GET /providers/adapters` is deliberately narrower than `GET /providers`: its only consumer is the
provider registration form, which is ADMIN anyway.

### Routing
| Operation | Roles |
|---|---|
| `GET /routing/policies` | A |
| `POST /routing/policies` | A |
| `PUT /routing/policies/{policyId}` | A |
| `POST /routing/policies/{policyId}/state/{enabled}` | A |
| `DELETE /routing/policies/{policyId}` | A |
| `POST /routing/evaluate` | A |

### Templates
| Operation | Roles |
|---|---|
| `GET /templates` | A T O |
| `POST /templates/import` | A T |
| `GET /templates/{code}` | A T |
| `POST /templates/{code}` | A T |
| `PUT /templates/{code}` | A T |
| `DELETE /templates/{code}` | A T |
| `POST /templates/{code}/restore` | A T |
| `PUT /templates/{code}/versions` | A T |
| `POST /templates/{code}/versions/{locale}/{version}/state/{status}` | A T |
| `POST /templates/{code}/preview` | A T |
| `PUT /templates/{code}/providers/{providerCode}` | A T |
| `DELETE /templates/{code}/providers/{providerCode}` | A T |

### Suppressions
| Operation | Roles |
|---|---|
| `GET /suppressions` | A O |
| `GET /suppressions/check` | A O |
| `POST /suppressions` | A O |
| `POST /suppressions/import` | A O |
| `DELETE /suppressions/{entryId}` | A O |

### Statistics
| Operation | Roles |
|---|---|
| `GET /statistics` | A N |
| `GET /statistics/export` | A N |

### Audit
| Operation | Roles |
|---|---|
| `GET /audit` | A S |
| `GET /audit/export` | A S |

### Send
| Operation | Roles |
|---|---|
| `POST /send/estimate` | A O |
| `POST /send/message` | A O |
| `POST /send/batch` | A O |

### Administration
| Operation | Roles |
|---|---|
| `GET /administration/kill-switch` | A |
| `POST /administration/kill-switch` | A |
| `GET /administration/parameters` | A |
| `PUT /administration/parameters/{key}` | A |
| `DELETE /administration/parameters/{key}` | A |

### Contract
| Operation | Roles |
|---|---|
| `GET /openapi.yaml` | A O T N V S |

## 401 versus 403

They mean different things and must be handled differently.

**401 — "who are you".** The token is missing, expired or not accepted by the issuer. Under
`/api/admin/v1` this comes from the framework's default entry point, so **the body is not
problem+json** — do not attempt to parse one. Treat any 401 as *session gone*: clear the stored
session and show the login form. If a refresh is already in flight, let it finish first; a single
401 during renewal is normal.

**403 — "may you".** The token is valid; the roles do not cover the endpoint. The body **is**
problem+json, `code: FORBIDDEN`. Render it as an explanation on the screen. Never sign the operator
out on a 403 and never retry it.

Unauthenticated callers never reach a controller under `/api/admin/v1` — the whole prefix is
`authenticated()` on the chain — so 403 always means a role problem and never an anonymous call.

## Address masking is the server's job

`AdminMasking`, applied on the way out of the BFF:

- **ADMIN and OPERATOR** see full recipient addresses.
- **Every other role** receives them already masked: `99890***4567` for numbers, `f***l@domain` for
  email.

The panel does not mask what the server sends and must not try to unmask it. Two corollaries:

- Never re-mask a value from the API — you would be masking a mask.
- Masking is decided by the *column content*, not by the message's channel: an `@` makes it an email.

Client-side masking exists for exactly one purpose: **echoing back a value the operator typed
themselves** in a confirmation dialogue — test send and route dry-run show the address masked so the
confirmation screenshot an operator pastes into a ticket does not carry a customer's number. See
`07-conventions.md`.

## Reading customer data is audited

`GET /messages` writes one SEC-08 audit entry **per search**, not per row, keyed by the address hash.
The panel needs no code for this — but it must not paper over it: do not fire a search on every
keystroke, and do not poll the message list. Each search is a line in a journal that answers "who has
been looking at this customer".
