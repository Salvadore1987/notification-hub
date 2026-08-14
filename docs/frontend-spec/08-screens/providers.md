# Channels and providers

**Route** `/providers` · **Roles** ADMIN

Three tabs: **Providers** · **Channels** · **Test send**.

| Endpoint | Roles |
|---|---|
| `GET /providers` | A T |
| `GET /providers/adapters` | A |
| `POST /providers/{code}` | A |
| `PUT /providers/{providerId}` | A |
| `DELETE /providers/{providerId}` | A |
| `POST /providers/{providerId}/state/{state}` | A |
| `GET /channels` | A |
| `PUT /channels/{channel}` | A |
| `POST /channels/{channel}/state/{status}` | A |
| `POST /providers/test-send` | A |

---

## The three levels of configuration

The same words — *balancing*, *quiet hours* — appear on three screens. They are not three copies of
one setting; they are three levels, each with a different job. Get this into the field help, because
it is the single most confusing thing in the panel.

**Provider — the pipe.** Its profile holds only what describes the connection: credentials reference,
tariff, weight, rate limit, quota, health. **There is no balancing strategy and no quiet-hours window
on a provider profile.** Weight is there, but weight is not a strategy — it is an input to `WEIGHTED`.

**Channel — the mandatory bottom layer of policy.** Balancing strategy (**required** — it has no
default, and without it a channel profile cannot be created), fallback order, quiet hours, channel
quota. Applies to all traffic on the channel, whatever stream sent it.

**Stream — an optional override on top of the channel, for its own traffic only.** A stream may
override exactly two things: the balancing strategy and the quiet-hours window. It cannot define or
reorder providers.

Resolution order: balancing = routing policy → stream → channel; quiet hours = client setting →
stream → channel. **First one set wins, and wins wholly** — windows replace each other, they do not
intersect or narrow. So an empty field at the stream level means "the channel's applies", not "off".

---

## Tab 1 — Providers

`GET /providers` → array of `Provider`.

| Column | Source |
|---|---|
| Code | `code` |
| Channel | `channel` |
| Adapter type | `adapterType` |
| Weight | `weight` |
| Tariff | `tariff.perMessage` / `tariff.perSegment` |
| State | derived from `state.enabled` / `state.maintenance` |
| Health | `state.health` — **observation** |
| Selectable | `state.selectable` — **observation, read-only** |
| Actions | Edit · Enable / Disable / Maintenance · Delete |

### Health and selectability are not settings

`health` is computed passively from real delivery attempts (PR-02). A `DOWN` provider is not selected
by routing, and failover happens by itself (PR-01, FR-6.3). **There is no "mark this provider
healthy" button and there must not be one** — a provider with no traffic cannot produce the figures
that would clear it, so it recovers through `UNKNOWN` (selectable, untrusted) instead.

Render `UNKNOWN` as "no data", not as a fault.

### Provider form

| Field | Rule |
|---|---|
| `code` | Pattern `^[A-Z0-9][A-Z0-9_]{1,31}$` — **uppercase** (`PLAYMOBILE`, `SMS_GATE`). Not the same as the adapter type, which is lowercase. Locked when editing. |
| `channel` | **Read only at registration**; ignored on update. Locked when editing. |
| `adapterType` | **A picker over `GET /providers/adapters`, filtered by the chosen channel.** Locked when editing. |
| `weight` | integer; input to `WEIGHTED` |
| `tariff` | `perMessage`, `perSegment` — numbers; currency is implied |
| `rateLimit` | `tps`, `perMinute`, `perRecipientPerHour` |
| `quota` | as on a stream |
| `endpointConfig` | free-form key/value pairs (`Record<string,string>`) — originator, sender name, priorities, default TTL… |

**There is no credentials field, and there must not be one** (ADR-0044). Provider credentials come
from deployment properties (`commhub.provider.*`, fed from environment variables) and are never part
of the configuration an operator edits. A form field that nothing reads but that the panel invites the
operator to fill in is an invitation to put the secret itself in the database.

The same applies to `endpointConfig`: it is for sending knobs, not for tokens or passwords.

**Why `channel` and `adapterType` lock after registration:** a provider that changed its channel or
its adapter is a different provider, not an edit of this one. The backend ignores those fields on
`PUT`; showing them as editable would be a silent no-op. To move to another adapter, register a new
profile under a new code and switch the channel's fallback order.

**`adapterType` is a picker, not a text field** (ADR-0042). `GET /providers/adapters` returns the
`(adapterType, channel)` pairs whose channel-port beans **this contour actually deployed** — the same
beans a routed message is matched against, so a type the form offers is a type a send can resolve. The
set is a property of the deployment, not of the panel; on the local stand it includes the mock
adapters. If the lookup fails, degrade to a plain input — registration legitimately accepts any
syntactically valid type, because a profile may be created before its adapter ships.

### State and delete

```
POST /providers/{providerId}/state/{ENABLED|DISABLED|MAINTENANCE}     X-Commhub-Reason
DELETE /providers/{providerId}                                        X-Commhub-Reason
```

Both ask for a justification.

**`MAINTENANCE` is not `DISABLED`.** A disabled profile is off by an administrator's decision;
maintenance is planned work at the provider. Both make it unselectable, but in the audit journal and
in an incident review they are different entries. Offer both, and label them differently.

**Delete is refused while anything still routes through the provider** — 409. That is deliberate: a
provider pulled out from under a fallback order is a channel that stops working at the next failover
rather than at the moment of the edit. On the 409, tell the operator to remove it from the channel's
fallback order and from any routing policy first.

---

## Tab 2 — Channels

### The rule that decides this tab's shape

**Build the rows from the `Channel` enum — `SMS`, `EMAIL`, `PUSH` — not from what `GET /channels`
returned.**

Channels are not created or deleted; their set is fixed by the release (AR-05). But a channel
*profile* — the balancing strategy, the fallback order, the quiet hours, the quota — is a row somebody
has to create, and `PUT /channels/{channel}` is an upsert that creates it.

A tab that renders only stored rows therefore shows an **empty table on a fresh contour with no way to
open anything**. The first send then fails with `NO_ROUTE_AVAILABLE / channel SMS is not configured`
and the panel offers no way out. That failure is exactly what this tab exists to prevent.

So:

- Three rows always, one per channel.
- A channel with no stored profile renders as **"not configured"** with a single action: **Configure**
  (which opens the same form and does the upsert).
- **Its state buttons are hidden**, not disabled: `POST /channels/{channel}/state/{status}` on a
  profile that does not exist is a guaranteed 409.

### Table

| Column | Source |
|---|---|
| Channel | the enum value |
| Status | `status` (`ChannelStatus`) or "not configured" |
| Balancing | `balancingStrategy` |
| Fallback order | `fallbackOrder[]`, in order |
| Quiet hours | `quietHours` |
| Available | `available` |
| Actions | Edit / Configure · Enable / Disable / Maintenance |

### Channel form — `PUT /channels/{channel}` (`ChannelRequest`)

| Field | Rule |
|---|---|
| `balancingStrategy` | **Required.** No default. |
| `fallbackOrder` | An **ordered multi-select of provider codes** filtered to this channel. The order of selection is the order of failover — the whole list is sent every time, because the order *is* the configuration. |
| `quietHours` | start, end, zone, behaviour |
| `quota` | daily/monthly count and cost, behaviour — **`behavior` required as soon as any ceiling is filled, no default** (`streams.md`) |

**Fill `fallbackOrder` immediately.** Routing takes its providers only from that list, so a channel
with an empty order looks configured and routes nothing. Warn on save when it is empty.

### Channel state

```
POST /channels/{channel}/state/{ACTIVE|MAINTENANCE|DISABLED}     X-Commhub-Reason
```

Asks for a justification. Hidden entirely for an unconfigured channel.

---

## Tab 3 — Test send

`POST /providers/test-send` → `MessageAccepted`.

| Field | Note |
|---|---|
| `streamId` | required |
| `channel` | required |
| `recipient` | required — MSISDN / email / push token by channel; `clientId` optional |
| `provider` | optional — **pins the send to one provider, excluding the rest**. An unusable provider gives an ordinary `NO_ROUTE_AVAILABLE`, not a special refusal. |
| `subject`, `text` | optional free content — this endpoint is the one place free text is allowed, because it is a configuration check rather than customer communication |

Result card: message id, status, and the **recipient masked** (`07-conventions.md`) — this is an
operator-typed value being echoed back, and a screenshot of it should not carry a real number.

### This is not a simulation, and the screen must say so

The message goes through the **normal pipeline and really reaches the subscriber**: stream quotas,
channel filters, real provider credentials, platform sandbox for push (PU-13). That is precisely why
it verifies the configuration (FR-7.4).

It is tagged `TEST`, so it stays out of business statistics until "include test" is switched on — a
dimension, not a deletion.

Because it is the real pipeline, it is also refused by the real pipeline, and a refusal comes back as a
problem document with its own code (`429` for an exhausted quota, `422` for a suppressed address, and
so on) rather than a `200` carrying `"status":"REJECTED"`. Render it through the ordinary error path:
finding out *why* the configuration refused the message is the entire point of the button.

---

## Configuration takes up to 30 seconds to apply

Edits need no restart and reach every instance within 30 seconds (AD-07, NF-07). If nothing changed
immediately after a save, that is expected. Say so next to the save button — otherwise the operator
edits again, and again.
