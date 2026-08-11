# Administration

**Route** `/administration` · **Roles** ADMIN

| Endpoint |
|---|
| `GET /administration/kill-switch` |
| `POST /administration/kill-switch` |
| `GET /administration/parameters` |
| `PUT /administration/parameters/{key}` |
| `DELETE /administration/parameters/{key}` |

Three cards: **Kill switch** · **System parameters** · **SSO group → role mapping**.

---

## Card 1 — kill switch (FR-3.2)

`GET /administration/kill-switch` → `KillSwitch { active, includesCriticalOtp, changedAt }`.

**Normal state** — a calm/green card: "sending is running normally", with a **Stop all sending**
button.

**Active** — a red card stating which of the two situations applies:

- `includesCriticalOtp: true` → "all traffic is stopped, including CRITICAL_OTP";
- `includesCriticalOtp: false` → "CRITICAL_OTP is still going".

with `changedAt` and a **Resume sending** button. The same banner appears on the dashboard for
everyone.

### Activating

`POST /administration/kill-switch` (`KillSwitchRequest`):

| Field | Rule |
|---|---|
| `activate` | `true` |
| `reason` | **mandatory when activating** — in the body, not in a header. Block the submit on an empty value. |
| `includeCriticalOtp` | checkbox, **default false** |

**`CRITICAL_OTP` keeps flowing unless the box is ticked.** Everything that can be held without harming
a customer is held; one-time codes are not. Stopping those too is an explicit, separate decision, and
the checkbox must be unticked every time the dialogue opens.

"an audit line saying everything stopped at 03:14" is only useful with an answer to "why" — hence the
mandatory reason.

### Releasing

`POST` with `activate: false`, plus a confirmation: "release the kill switch? traffic resumes from
where it stopped."

That sentence is true and worth saying: **no message is rewritten.** The saga reads the switch on
every step and holds whatever it is allowed to hold, so releasing resumes exactly from the point of
the stop. Held traffic is not lost.

Note the JSON shape: a request body without `includeCriticalOtp` is exactly what releasing looks like.

---

## Card 2 — system parameters (NF-06)

`GET /administration/parameters` → `SystemParameter[] { key, value, description, updatedAt, updatedBy }`.

| Column | Source |
|---|---|
| Key | `key` |
| Value | `value` |
| Description | `description` |
| Updated | `updatedAt` |
| Updated by | `updatedBy` |
| Actions | Edit · Delete |

**Add / edit** — `PUT /administration/parameters/{key}` with `SystemParameterRequest { value,
description }`. The **key cannot be changed** on an existing parameter — disable the field when
editing.

**Delete** — `DELETE /administration/parameters/{key}`. Say what it means in the confirmation:
**deleting a parameter reverts to the deployment default**, it does not "switch a setting off".

Before and after values go into the audit journal (FR-7.3).

### What belongs here and what does not

This card holds the things an operator changes at night that must be identical on every instance.
Anything that has a form of its own — routing, providers, quotas, quiet hours — lives in its own
aggregate and its own screen (AD-07). Do not turn this into a second configuration surface.

---

## Card 3 — SSO group → role mapping (§10.1)

A **read-only** table rendered from `config.groupRoles`: SSO group → panel role.

An empty table means a group named after a role passes through as itself — which is the shipped
configuration. Say that, or an empty table reads as "nothing is configured".

This is deployment configuration, not Hub data. There is no endpoint behind it and there is nothing to
save.

---

## The absence that must stay

**There is no user management here, and there must never be.** The Hub stores no accounts and no
passwords (§10.1): identity comes from the corporate SSO, and a role comes from a group. Access
requests go there, not to the Hub.

A panel that grew a user list would be a second identity store with no lifecycle, no password policy
and no offboarding — which is exactly what §10.1 avoids.

## Errors

| Situation | `code` |
|---|---|
| Activating with no reason | `VALIDATION_FAILED` (400) — prevent it in the form |
| Deleting a missing parameter | `NOT_FOUND` (404) — re-fetch |
| Any write by a non-ADMIN | `FORBIDDEN` (403) — should be unreachable; show it if it happens |
