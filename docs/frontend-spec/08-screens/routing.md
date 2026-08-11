# Routing

**Route** `/routing` · **Roles** ADMIN

| Endpoint |
|---|
| `GET /routing/policies` |
| `POST /routing/policies` |
| `PUT /routing/policies/{policyId}` |
| `POST /routing/policies/{policyId}/state/{enabled}` |
| `DELETE /routing/policies/{policyId}` |
| `POST /routing/evaluate` |

Two cards: **Routing policies** and **Route check (dry-run)**.

## Card 1 — policies

`GET /routing/policies` returns the policies **in application order**. No paging.

| Column | Source |
|---|---|
| Priority | `priority` — **lower means earlier** |
| Match | `match` summarised |
| Action | `action` summarised |
| Enabled | `enabled` — a toggle |
| Actions | Edit · Delete |

The first matching policy decides. Render the list in the order the server returned it and show the
priority number; do not re-sort client-side.

### Policy form (`RoutingPolicyRequest`)

**Match** — every field optional; an empty field widens the condition:

| Field | Type |
|---|---|
| `streamId` | picker over streams |
| `trafficClass` | `TrafficClass` |
| `minPriority` | `Priority` |
| `channel` | `Channel` |

**Action**:

| Field | Type |
|---|---|
| `channel` | `Channel` |
| `providerOrder[]` | ordered multi-select of provider codes |
| `balancingStrategy` | `BalancingStrategy` |

**`priority`** — integer, lower first.

`match` and `action` are both required by the schema.

### Edit replaces the whole policy

`PUT` takes the complete `RoutingPolicyRequest`; there is no patch. Half a rule is not a rule. The
edit form must therefore load the current policy and submit all of it, never a diff.

### The enable toggle

```
POST /routing/policies/{policyId}/state/{true|false}
```

A separate operation, and **it does not ask for a justification** — it applies immediately. It exists
because disabling is how a routing edit is rolled back fast: one click, no re-submission of a body
somebody else may have changed in the meantime.

Make the toggle visibly distinct from Delete.

## Card 2 — route dry-run

`POST /routing/evaluate` (`RouteEvaluationRequest`):

| Field | Note |
|---|---|
| `streamId` | **required** |
| `recipient` | **required** |
| `channel`, `trafficClass`, `priority` | optional |
| `text` | needed to compute segments and cost (§18.3) |

Response (`RouteEvaluation`): `routed`, `channel`, `provider`, `fallbackProviders[]`, `strategy`,
`segments`, `estimatedCost`, and a nullable `rejection { reason, detail }`.

Render the rejection prominently when `routed` is false — that is the answer the operator came for.

Mask the operator-typed recipient in the result summary (`07-conventions.md`).

### What a dry-run is and is not

**Nothing is sent and nothing is stored.** A message is built in memory and run through the *real*
router against the *real* configuration — there is no second implementation of the routing rules in
the system and there will not be one.

It is a check of the **rules**, not of the **provider**. For the second one there is the test send on
the Providers screen. Say which is which; operators reach for the wrong one.

## Guidance to put on the screen

Do not delete a policy "to see what happens". The enable toggle and the dry-run exist for that;
a deleted policy has to be reassembled by hand.

## Errors

| Situation | `code` |
|---|---|
| Malformed policy | `VALIDATION_FAILED` (400) with `field` |
| Editing/deleting a policy someone already removed | `NOT_FOUND` (404) — re-fetch the list |
| Dry-run with a bad address | `VALIDATION_FAILED` (400) — attach to the recipient field |
