# 08.00 — Navigation and shell

## The section list is declared once

Menu items, routes and role gating must all come from **one declaration**. Three lists that have to be
kept in step is how a screen ends up reachable by a role that cannot use it, or unreachable by one
that can.

One entry per section: `key`, `route`, `label key`, `icon`, `section` (the role grouping from
`03-authorization.md`).

| # | key | Route | Label (RU / EN) | Section grouping |
|---|---|---|---|---|
| 1 | `dashboard` | `/dashboard` | Дашборд / Dashboard | `any` |
| 2 | `batches` | `/batches` | Рассылки / Batches | `operatorOrViewer` |
| 3 | `send` | `/send` | Отправка / Send | `operator` |
| 4 | `messages` | `/messages` | Сообщения / Messages | `operatorOrViewer` |
| 5 | `dlq` | `/dlq` | DLQ | `operator` |
| 6 | `streams` | `/streams` | Входящие потоки / Streams | `admin` |
| 7 | `providers` | `/providers` | Каналы и провайдеры / Channels and providers | `admin` |
| 8 | `routing` | `/routing` | Маршрутизация / Routing | `admin` |
| 9 | `templates` | `/templates` | Шаблоны / Templates | `templateManager` |
| 10 | `suppressions` | `/suppressions` | Suppression list | `operator` |
| 11 | `statistics` | `/statistics` | Статистика / Statistics | `analyst` |
| 12 | `audit` | `/audit` | Аудит / Audit | `auditor` |
| 13 | `administration` | `/administration` | Администрирование / Administration | `admin` |

Order matters — it is the operator's mental order: watch, then intervene, then configure, then account
for it.

## Extra routes

| Route | Screen | Gated as |
|---|---|---|
| `/` | redirect to `/dashboard` | — |
| `/templates/:code` | template card | `templateManager` |
| `*` | not-found page with a link back to the dashboard | — |

`/templates/:code` is the only screen with its own route outside the section list. It is a deep link
an operator shares during a content review, so it must be addressable.

## Gating

- The menu renders only the sections the current roles can see.
- **Every route is still guarded independently.** A hidden menu item is not protection: an operator
  pasting `/streams` must get a "you do not have the role for this section" page, not the screen.
- The guard renders a 403-style page, it does not redirect. A redirect loses the URL and makes the
  refusal look like a bug.
- A session with no recognised roles renders the shell and an explanatory empty state.

## Shell

**Sider** — the filtered menu. Collapsible; collapse below a tablet breakpoint. The selected item is
the one whose route prefixes the current path, so `/templates/OTP_LOGIN` keeps Templates highlighted.

**Header** — product name, language switch (`Рус` / `O'z` / `Eng`), and a menu under the operator's
name (`preferred_username`) containing sign-out.

**Content** — the routed screen.

The language switch is a control and needs an accessible name; a flag or a bare code is not one.

## Loading and session states

| State | What renders |
|---|---|
| Config loading | nothing (bootstrap has not finished) |
| Config loaded, `authority` empty | terminal "contour not configured" page — no shell, no login form |
| No session | login form **in place of the content, at the current URL** — see `02-authentication.md` |
| Restoring a session | full-screen spinner with "signing in" |
| Session expired mid-work | login form with a "session expired, sign in again" notice |
| Signed in | the shell |

Because the login form takes the place of content rather than a route, a deep link survives sign-in
with no `returnTo` machinery. Do not add one.

## Cross-screen links

The panel is a set of screens an operator moves between during an incident. These links must exist:

| From | To | Carrying |
|---|---|---|
| Dashboard → active batch row | Batches, card open | `batchId` |
| Dashboard → "in DLQ: N" | DLQ | — |
| Batch card → "batch messages" | Messages | `?batchId=` filter pre-filled |
| Send → after a bulk send | Batches, card open | `batchId` |
| Templates catalogue row | Template card | `code` |
| Any `NO_ROUTE_AVAILABLE` error (ADMIN) | Providers → Channels | — |
| Any `KILL_SWITCH` error (ADMIN) | Administration | — |

**There is no "messages of this batch" endpoint.** Drill-down is the message list with a `batchId`
filter — same paging, same masking, same SEC-08 audit entry. Read the filter from the query string so
the link is shareable.

## Field help

Every form field carries a help affordance next to its label that explains **what the field affects**,
not what its name means. In the forms without labels — route dry-run, address check, template preview —
the hint appears on the control itself.

This is not decoration. Half of the configuration screens use words ("balancing strategy", "quiet
hours", "stream") that appear at more than one level of the configuration and mean something slightly
different at each; the hint is where that gets explained. See `providers.md` for the three levels.
