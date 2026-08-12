# 09 — Testing

Two levels, answering different questions. Both are required; neither substitutes for the other.

| Level | Question | Backend |
|---|---|---|
| Unit / component | does this module or screen behave as specified? | stubbed at the network boundary |
| End-to-end | does an operator's whole task work in a browser? | BFF stubbed, **issuer real** |

The Hub's own behaviour is what the backend's 900+ unit tests and 160+ integration tests are for. The
panel's tests must not try to re-prove it; they prove what the panel sends and what the operator sees.

---

## Level 1 — unit and component

### What to cover

**Pure modules — everything, no exceptions:**

- role derivation from a claim (array, string, unknown groups, `groupRoles` remapping);
- the OIDC token client: discovery, password grant, refresh, the **three failure classifications**,
  and that the password appears in no thrown message;
- config loading and the merge over defaults, including the empty-`authority` outcome;
- time formatting and the UTC ↔ `Asia/Tashkent` conversions;
- masking (both forms, both edge cases);
- identifier patterns (the case inversion between stream and template codes);
- the reason header: percent-encoding, and that an empty reason produces no header;
- error description composition from a problem document.

**Components whose breakage shows on every screen:**

- the server table — paging, sorting, the stale-response guard, the error state with retry;
- the justification prompt — **`null` aborts, `''` confirms without a header**;
- the route guard and the role-filtered menu;
- the DLQ (selection semantics + explicit id lists);
- the send form (the estimate gate).

### Stubbing the backend

Stub at the **`fetch` boundary**, not at the module boundary. A test that mocks your own API wrapper
proves the screen calls the wrapper; a test that stubs `fetch` proves what actually goes on the wire —
which method, which path, which query, which headers, which body.

Declare routes as `"<METHOD> <path>"` and record every call, so **one test can assert both the request
and what the operator saw**. That pairing is what catches "the screen renders fine but sends the wrong
filter".

> **Order matters.** Many typed clients capture `globalThis.fetch` **at client creation**. Install the
> stub **before** creating the client, or the client keeps a reference to the real `fetch` and every
> stub silently misses. Make the test helper create the client itself, so the order cannot be got
> wrong.

Return `application/problem+json` for statuses ≥ 400 and `application/json` otherwise, so the error
middleware is exercised for real.

### Tests worth writing that are easy to skip

- A **403** on a screen renders an explanation, not a blank page.
- A **401** clears the session and shows the login form, **without parsing a body** (a 401 has none).
- A **409** re-fetches before showing the error.
- A **429** surfaces `Retry-After`.
- An **unknown problem `code`** renders `title` + `detail` rather than "unknown error".
- **`otpLatencyP99Millis: null`** renders as "no OTP traffic" and the string `0` appears nowhere on the
  tile.
- The Channels tab renders **three rows against an empty `GET /channels` response**, each offering
  Configure and **no state buttons**.
- The send button is disabled until an estimate exists, and **re-disabled after any form edit**.

---

## Level 2 — end-to-end

### The shape

**Stub the BFF at the network level. Do not stub the issuer — sign in for real.**

- Precondition: `docker compose up -d keycloak`. Fail fast with that message if the discovery document
  is unreachable, rather than letting every test time out on a login form.
- Every test signs in through the panel's own form as `demo` / `demo`.
- The BFF stub must hold **live state**: pausing a batch changes the card's status on the next read,
  retrying a DLQ row removes it. A stub that always returns the same body cannot express the four
  scenarios below.
- Record requests, and allow a test to override one route with a failure so the refusal path can be
  asserted.

### Why nothing is reused between tests, and cannot be

The token lives in `sessionStorage`, which browser-automation "storage state" does **not** carry
(SEC-02). There is therefore no sign-in-once setup project; each test signs in. Put the sign-in and
the wait-for-the-screen in the shared page fixture, not in the specs — a forgotten wait is a flaky
test rather than an error.

### Required scenarios (QA-07)

1. **Batch pause** — find a batch, open the card, pause it with a justification, see the status
   change.
2. **DLQ retry** — filter, select rows, retry, see the applied/skipped answer and the list update.
3. **Template publish** — walk `DRAFT → ON_REVIEW → PUBLISHED` and see the previous version in that
   locale archived.
4. **Test send** — submit, and assert the confirmation shows the **masked** address and that the raw
   MSISDN appears nowhere on the page.

Plus the ones the login flow made necessary:

5. **A deep link survives sign-in** — open `/dlq` unauthenticated, sign in, land on the DLQ.
6. **A wrong password** shows the credentials message and does not sign in.
7. **Sign-out** returns the form.
8. **A BFF refusal reads correctly** — stub a 409 or a 422 and assert the operator sees an explanation.

### Accessibility

Run an automated auditor (axe or equivalent) over at least five screens **plus the login form**,
against WCAG 2.1 A/AA.

- Exclude **contrast** if the palette comes from a component library rather than your code — you
  cannot fix what you did not choose, and a permanently failing check gets ignored.
- Record any component-library markup issue as an **explicit known exception with its selector**.
  Never disable a rule globally: a disabled rule hides your own future mistakes too.
- Assert `<html lang>` follows the language switch.

---

## Contract drift tests

The panel is outside the backend's contract tests, so add your own. They are cheap and they catch the
class of bug that has already happened twice in this project:

- **Enum lists against the generated schema.** Every hand-maintained list (filter options, colour
  maps) must equal the generated union. A new `MessageStatus` must not fall out of a filter silently.
- **Dictionary parity.** The three locales must have identical key sets and identical interpolation
  placeholders.
- **Key coverage.** Every key the screens ask for must exist in the fallback locale — scan the source
  for translation calls and compare.

---

## What not to test

- The backend's routing, segmentation, dedup or status machine. Those have their own tests, and a
  panel test that asserts them is a test that fails when the backend legitimately changes.
- Component-library internals.
- Exact pixel layout.
- The real provider path. There is a mock provider on the local stand for anything that needs a send
  to complete (ADR-0041).
