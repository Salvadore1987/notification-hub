# Dashboard

**Route** `/dashboard` · **Roles** all six · **Endpoint** `GET /dashboard`

The default landing screen and the one that stays open on a wall display. It answers one question —
"is anything wrong right now" — and it must answer it without the operator touching anything.

## Filters

| Control | Behaviour |
|---|---|
| Period | `from` / `to`, empty by default → server's last 24 h. Do not compute a default client-side. |
| `includeTest` | Checkbox, **off by default**. Off means test sends (FR-7.4) are excluded from every number on the screen. |

## Layout

**Tiles** — from `totals`:

`accepted` · `delivered` · `failed` · `rejected` · `inFlight` · `deliveryRate` · **`otpLatencyP99Millis`**

**Table "by channel"** — `byChannel[]`, which is an array of `StatisticsRow`: key (the channel),
accepted, delivered, failed, rejected, segments, cost.

**Table "providers"** — `providers[]`: provider code, channel, `health`, `selectable`. Colour by
health; render `UNKNOWN` as "no data", never as a failure (`06-vocabulary.md`).

**Card "active batches"** — `backlog.activeBatches[]`: batch id, stream, channel, status, total,
processed, `completionPercent`, created. The card heading carries `backlog.dlqPending` as
"in DLQ: N". Clicking a row opens that batch's card in the Batches section; clicking the DLQ count
goes to the DLQ.

**Kill-switch banner** — from `killSwitch`. When `active`, a red banner across the top of the screen
saying sending is stopped, with a second line that must say which of the two situations it is:

- `includesCriticalOtp: true` → "all traffic is stopped, including CRITICAL_OTP";
- `includesCriticalOtp: false` → "CRITICAL_OTP is still going".

That distinction is the whole point of the banner during an incident. Show `changedAt`.

## Polling

**Re-fetch every 15 seconds.** No manual refresh button is needed; leaving the tab open is the
interaction.

`GET /dashboard/stream` exists as an SSE alternative and the reference implementation does not use it.
Polling survives a load balancer, a corporate proxy and a laptop that went to sleep. If you implement
SSE, keep polling as the fallback.

Pause polling when the tab is hidden if you like — but resume with an immediate fetch, not after a
further 15 s.

## The one thing that must not be got wrong

**`otpLatencyP99Millis: null` means "no OTP traffic in this period". It is not zero latency and must
never render as `0`.**

A zero on that tile during an incident reads as "OTP is instant", which is the opposite of "no OTP is
flowing". Render the words, in a muted style.

## Not on this screen, deliberately

**No alerts.** Alertmanager already evaluates the OBS-04 rules and knows things a database query
cannot — a pod that stopped being scraped, for one. A second, weaker alert surface here would compete
with the real one.

**No provider health history graph.** Current state is here; history is in Grafana
(`commhub-providers`).

## Errors

A failed fetch must not blank the screen. Keep the last snapshot, show a non-blocking staleness
notice with the time of the last successful read, and keep polling. A dashboard that goes blank
because one request timed out is worse than a slightly old dashboard.

401 → the session is gone, hand over to the login form. 403 cannot happen here (all roles are
permitted); if it does, show it — it is a bug in the role map.
