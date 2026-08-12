# Statistics

**Route** `/statistics` · **Roles** ADMIN, ANALYST

| Endpoint |
|---|
| `GET /statistics` |
| `GET /statistics/export` |

FR-6.2 reporting.

## Controls

**Dimension** first — it decides what the first column contains, and everything else only narrows the
selection:

| `dimension` | First column |
|---|---|
| `CHANNEL` (default) | channel |
| `PROVIDER` | provider code |
| `STREAM` | stream id |
| `BATCH` | batch id |
| `DAY` | date |
| `HOUR` | hour bucket |

**Filters**, all independent of the dimension: period · `channel` · `streamId` · `provider` ·
`batchId` · `includeTest`.

Filters and dimension are orthogonal — grouping by `PROVIDER` while filtering to one channel is a
legitimate and common request. Do not couple them.

The report re-fetches on any change; a Refresh button is still useful.

## Table — `StatisticsRow[]`

| Column | Source |
|---|---|
| Key | `key` — whatever the dimension groups by |
| Accepted | `accepted` |
| Delivered | `delivered` |
| Failed | `failed` |
| Rejected | `rejected` |
| In flight | `inFlight` |
| Segments | `segments` |
| Cost | `cost.amount` |
| Delivery rate | `cost.deliveryRate` |

Note the shape: `cost` is an object carrying both `amount` and `deliveryRate`. Read them from there.

The response is a **plain array — no paging**.

### The buckets deliberately do not add up

`delivered + failed + rejected` is less than `accepted`, and the difference is `inFlight`. Counters
are per message, not per transition. Do not "fix" this with a computed total, and do not draw a pie
chart that implies the parts are exhaustive.

## Export

`GET /statistics/export` with **exactly the same parameters as the screen**. UTF-8 with a BOM.
Save as `statistics-<dimension>.csv`.

An export that reads differently from the screen is an export nobody can reconcile with what they saw
— so send the same query, and do not re-render the file client-side.

Columns: `key, accepted, delivered, failed, rejected, inFlight, segments, cost, deliveryRate`.

## What operators need told

- **Test sends are excluded by default.** When the numbers do not match expectations after somebody
  has been checking the configuration, switching `includeTest` on is the first thing to try. FR-7.4 is
  implemented as a dimension, not as a deletion — the rows are there and stay visible to whoever ran
  the test.
- **92 days is the ceiling for one request.** A longer report is assembled from several. Say it in the
  period picker rather than letting a 400 explain it.

## Not here, deliberately

**No XLSX.** CSV only. Do not add a client-side XLSX writer to "improve" it — the decision was taken
and the Excel-compatible CSV is what implements it.

**No charts required.** The screen is a report. Charts are permitted, but the table is the deliverable
and must stay exportable-identical.

## Errors

| Situation | `code` |
|---|---|
| Period over 92 days | `VALIDATION_FAILED` (400) on `from` |
| `to` before `from` | `VALIDATION_FAILED` (400) on `to` |
| Unknown dimension | `VALIDATION_FAILED` (400) — cannot happen from a select |
