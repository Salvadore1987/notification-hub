# 05 — Error model

## The shape

Every refusal that reaches a controller is RFC 9457 `application/problem+json`:

```json
{
  "type":     "https://docs.hamkorbank.uz/commhub/problems/template-not-published",
  "title":    "Template not published",
  "status":   422,
  "detail":   "OTP_LOGIN has no published version in UZ",
  "instance": "/api/admin/v1/send/message",
  "code":     "TEMPLATE_NOT_PUBLISHED",
  "field":    "templateCode",
  "messageId": "018f-…"
}
```

| Member | Use it for |
|---|---|
| `code` | **Branching.** The machine-readable reason. Stable. |
| `title` | A short heading. Stable English wording. |
| `detail` | Showing to the operator. Free text; **may change between releases** — never branch on it. |
| `field` | Attaching the message to a form field, when the failure is about one. |
| `status` | Nothing you can't get from `code`. Useful only for the transport-level cases below. |
| `type` | A link, if you want one. |
| `messageId` | Present on a submission rejection; give the operator a way to copy it. |
| `instance` | Diagnostics. |

## The rule: branch on `code`, never on `status`

Several codes share a status and mean entirely different things. `429` is `QUOTA_EXCEEDED`
("this stream has spent its daily budget", an operator problem), `FREQUENCY_CAPPED`
("this recipient has had enough messages today", a compliance outcome) or `RATE_LIMITED`
("you are going too fast", wait and retry). Three different sentences and three different next
actions behind one number.

This is also why the panel and a source system get the same vocabulary: two callers who hit the same
wall are told the same thing.

## The catalogue

All 25 codes. "Treatment" is what the screen should do.

### Pipeline refusals — a message was refused, and why

| `code` | HTTP | Title | Treatment |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | Validation failed | Attach to `field` if present, otherwise show `detail` above the form. The operator can fix this. |
| `DUPLICATE` | 409 | Duplicate submission | **Not an error.** Say plainly that nothing was sent again because an identical submission is inside the dedup window. Do not offer a retry. |
| `STREAM_SUSPENDED` | 409 | Stream suspended | Name the stream and, for an ADMIN, link to the Streams screen where it can be resumed. |
| `QUOTA_EXCEEDED` | 429 | Quota exceeded | The stream, channel or provider has spent its count or cost budget. Not time-based — retrying in a minute changes nothing. Point at quota configuration. |
| `TEMPLATE_NOT_PUBLISHED` | 422 | Template not published | The code or the locale has no published version. Link to the template card. Common on the Send screen. |
| `TEMPLATE_VARIABLE_MISSING` | 422 | Template variable missing | A merge field is empty. The sending path is strict; the preview is not. Point at the variable list. |
| `SUPPRESSED` | 422 | Recipient suppressed | The address is on the suppression list. Offer the Suppressions screen for an operator who may lift it. |
| `OPT_OUT` | 422 | Recipient opted out | Terminal for this traffic. Do not offer a retry. |
| `QUIET_HOURS` | 422 | Quiet hours | Inside the configured window and the behaviour is REJECT (rather than DEFER). Say which window. |
| `FREQUENCY_CAPPED` | 429 | Frequency cap reached | This recipient's cap for the period. Distinct from a rate limit — this is compliance, not throughput. |
| `PAN_DETECTED` | 422 | Card number in content | A full card number was found. **Never echo the offending content anywhere.** Say the content must be corrected. |
| `NO_ROUTE_AVAILABLE` | 503 | No route available | Nothing can serve this message: the channel is unconfigured or disabled, no provider is selectable, or the fallback order is empty. On a fresh contour this is the single most common failure. Point at Providers → Channels. |
| `KILL_SWITCH` | 503 | Sending stopped | The global stop is on. Show the kill-switch state and, for an ADMIN, a link to release it. |
| `SEND_STOPPED` | 409 | Send stopped | The batch or stream this message belongs to was stopped. |
| `TTL_EXPIRED` | 422 | Message expired | Its time-to-live elapsed before a provider took it. |
| `PROVIDER_REJECTED` | 422 | Provider rejected the message | Permanent refusal from the provider. `detail` carries the provider's own words. |
| `ATTEMPTS_EXHAUSTED` | 422 | Delivery attempts exhausted | Retries and fallbacks ran out. This is what DLQ entries look like. |

### Transport and access

| `code` | HTTP | Title | Treatment |
|---|---|---|---|
| `NOT_FOUND` | 404 | Not found | Nothing matches the identifier. On a card opened by URL, render a "not found" page, not an empty card. |
| `METHOD_NOT_ALLOWED` | 405 | Method not allowed | A client bug. Carries an `Allow` header. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Unsupported media type | A client bug — usually a CSV endpoint called with a JSON body. Carries `Accept`. |
| `NOT_ACCEPTABLE` | 406 | Not acceptable | A client bug in content negotiation. |
| `FORBIDDEN` | 403 | Forbidden | Roles do not cover this endpoint. Explain; never sign out; never retry. |
| `CONFLICT` | 409 | Conflicting state | The aggregate is not in a state that allows the action — a state transition that does not exist, a duplicate configuration key, a template version that may not be rewritten. **Refresh the object and re-render**: the operator is looking at stale state. |
| `RATE_LIMITED` | 429 | Rate limit exceeded | Carries `Retry-After`. Disable the action and count down. |
| `INTERNAL_ERROR` | 500 | Internal error | Nothing was accepted. Show a generic message plus the correlation id. Do not auto-retry. |

## Two documented gaps

**1. The yaml's prose lists 22 codes; the backend emits 25.** `METHOD_NOT_ALLOWED`,
`UNSUPPORTED_MEDIA_TYPE` and `NOT_ACCEPTABLE` are produced but not mentioned in the document's
`Problem` response description. Handle all 25. (They are client bugs, so a generated app should never
see them — but "should never" is not "cannot".)

**2. A 401 is not problem+json.** Under `/api/admin/v1` the whole prefix requires authentication, and
an unauthenticated call is refused by the framework's default entry point before any handler runs.
There is no problem body to parse. Treat a bare 401 as *session gone*:

```
if (status === 401) { clearSession(); showLoginForm(); return; }
```

Do this before attempting to read a problem body, or the error handler will throw on empty JSON while
handling an error.

## Unknown codes

The catalogue can grow. An unrecognised `code` must render as `title` + `detail` — never as "unknown
error" and never as a blank dialog. A screen that only understands the codes it was written against
becomes silent about the ones added after it.

## What to show

Compose the operator-facing message from, in order of preference:

1. a translated sentence for the `code`, where the panel has one;
2. `detail` from the server;
3. `title`;
4. `HTTP {status}`.

Add `Retry-After` as a countdown when present, and the correlation id on 500. Keep the technical
identifiers copyable — an operator pasting a message id or a correlation id into a ticket is the whole
point.

Never show a raw stack trace, a URL with a recipient address in it, or any content that triggered
`PAN_DETECTED`.
