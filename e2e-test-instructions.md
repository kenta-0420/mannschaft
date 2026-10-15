# E2E & Real-Device Test Automation — Implementation Instructions

> **How to use**: Hand this file to the implementation model with:
> `/goal e2e-test-instructions.md に書かれたテストケースの自動化を完遂しろ`
>
> Body is English. Japanese supplementary notes (補足) and questions (確認事項) are inline where the
> nuance matters. **Read "Stop And Ask Conditions" before writing any assertion whose expected value
> you are not 100% sure of.**

---

## 0. Context You Must Internalize First (証拠ベース)

This is **Mannschaft**, a multi-tenant organization / team / community management SaaS
(`README.md`: sports teams, clinics, schools, companies, salons, care facilities, community circles…).

| Layer | Tech | Where | Port |
|---|---|---|---|
| Backend | Spring Boot (Java 21), MySQL 8, Valkey | `backend/` | 8080 |
| Frontend | Nuxt 3 / Vue 3 / PrimeVue / Pinia | `frontend/` | dev 3000 (or 8081 — see below) |

Account model (`README.md` §アカウント構造): **3 layers — organization → team → user**, with roles
(ADMIN / DEPUTY / MEMBER / SUPPORTER) and a central **content-visibility resolver (F00)**
(`docs/features/F00_content_visibility_resolver.md`). Visibility ladder is `_AND_ABOVE` style
(MEMBERS_AND_ABOVE excludes supporters; SCOPE_AFFILIATED includes them).

### The existing test suite (do NOT rebuild — extend it)

- **299 Playwright E2E specs** under `frontend/tests/e2e/`, **441 vitest unit specs**.
- Config: `frontend/playwright.config.ts`. There are **two tiers**, and you must understand which you
  are writing for *before* touching anything:

| Tier | Playwright project | Dir | Backend | Auth | CI today |
|---|---|---|---|---|---|
| **Mock** | `chromium` (+ `chromium-admin`) | `tests/e2e/**` (except `real/`) | None — every API stubbed via `page.route()` | `storageState: tests/e2e/.auth/user.json` | `frontend-e2e-ci.yml` runs **only** an F15.4 + F22.1 smoke subset (full suite ≈ 7.5h, deliberately not run) |
| **Real** | `chromium-real` (+ `chromium-real-admin`) | `tests/e2e/real/**` | Live BE at `:8080`, DB seeded by `backend/scripts/seed-e2e-data.js` | `loginViaApi()` → storageState `real-user.json` | `e2e-real-smoke.yml` does **API smoke only** (no Playwright UI run) |

- Canonical login util: `frontend/tests/e2e/fixtures/auth.ts` → `loginViaApi(page, {email,password})`
  (POSTs `/api/v1/auth/login`, then writes `localStorage['currentUser']`). UI-form login is
  `loginAs()` (PrimeVue inputs need `click()` + `pressSequentially()`, NOT `fill()`).
- Seed accounts (`seed-e2e-data.js`): `e2e-user@test.mannschaft.local` / `TestPass2026!` (member of
  team **FC東京U-18**) and `e2e-admin@test.mannschaft.local`. Real-tier helpers resolve IDs by name at
  runtime (`tests/e2e/real/helpers/seed-api.ts`, `getE2eTeamId`) — never hardcode numeric IDs.
- `data-testid` convention already exists (kebab-case, feature-namespaced, e.g.
  `action-memo-edit-dialog-save`, `todo-complete-checkbox`). 186 component files use it.

### ⚠️ The single most important finding — existing real-tier write tests are NOT a safety net

`frontend/tests/e2e/real/write-ops.spec.ts` (and similar real specs) are full of:
```ts
if (!(await x.isVisible(...).catch(() => false))) { test.skip(true, '…見つからないためスキップ'); return }
// …
expect(page.url()).not.toContain('/error')   // ← passes even if the feature silently failed
```
These **pass green while the feature is broken** (selector not found → skip; no real assertion on the
result). This is exactly the "meaningless assertion / fake safety net" we must eliminate. Hardening
these into real assertions is a **first-class deliverable**, not optional polish.

---

## 1. Objective

Build a **trustworthy E2E / real-device safety net** so that during refactors and new-feature work, we
can in one command prove that **the core user journeys still work end-to-end against a real backend** —
not merely that pages render. Concretely:

1. **Harden** the weak real-tier write flows so a regression makes them **fail**, not skip.
2. **Fill P0/P1 gaps** in real-tier coverage (auth, billing, tournament/match, authz boundaries,
   withdrawal) with deterministic, idempotent, parallel-safe specs.
3. Keep additions **stable (non-flaky)** and runnable as a fast confidence gate.

Coverage for its own sake is explicitly NOT the goal. Every new assertion must encode a real,
user-visible behavioral contract.

---

## 2. Project & UX Understanding

Primary value: any small organization runs its whole operation (membership, schedule, chat, money,
records) in one app. The journeys below are the product's spine.

**Core workflows (証拠: `docs/features/`, `frontend/app/pages/`, memory index):**

1. **Auth lifecycle** — register (+ birth-date / minor → parental consent F01.9), email verify, login,
   2FA, password reset, logout. (`pages/`: `parental-consent/`, specs `auth/*`.)
2. **Org/Team/Role/Visibility** — create/join team & org, role assignment, content visibility per F00.
   (`pages/teams/**` 112 files, `organizations/**` 91 files.)
3. **Team daily ops** — timeline post/like/comment, TODO, chat (channels + reactions), schedule/events,
   surveys, bulletin/corkboard, action-memo.
4. **Tournaments & multi-sport live match recording (F08.10/F08.7)** — fixtures → live record (soccer,
   futsal, basketball, volleyball/sets, shogi/go turn-based, scored figure) → standings reflection;
   WebSocket live spectating with F00-delegated subscription authz. (`tests/e2e/real/f0810-*`,
   `real/tournament/*`.)
5. **Membership billing (F08.9)** — paid membership, Stripe payment, role-gated billing CRUD.
   (`real/f089-billing-crud-roles.spec.ts`.)
6. **Wallet / point cards (F18)**, **shift budget (F08.7)**, **recruitment/job matching (F13.1)**,
   **market (F22.1)**, **villages/community**, **slug navigation + 301 redirect (F15.4)**.
7. **Admin / system-admin** — announcements, audit logs, email outbox, error reports, moderation.
8. **GDPR withdrawal / anonymization (2-stage)** — immediate weak + 30-day strong anonymization.

**Data lifecycle**: Nuxt page → Pinia store / composable (`app/composables/use*Api.ts`) →
`/api/v1/**` → Spring service → MySQL. Generated types in `app/types/generated/index.ts` (do not edit).

**External boundaries needing mock/stub decisions**: Stripe (billing), S3/R2 presigned upload
(attachments/avatars), WebSocket/STOMP (live match, chat, lobby presence), email (outbox), Push.

---

## 3. Target Scenarios to Automate

Legend — **P0** service-critical (login/payment/data-save), **P1** major UX, **P2** edge/补完,
**Out** out of scope. Implement **P0 → P1**, then P2 as a separate phase.
"New" = no spec yet · "Harden" = spec exists but is a fake-pass · "Reinforce" = real but thin.

### P0 — Critical paths (real-BE tier unless noted)

| ID | Scenario | Status | Evidence / file | Assertion contract |
|---|---|---|---|---|
| P0-AUTH-01 | UI login (real form) → land on `/my/dashboard`, authenticated state persists across reload | Reinforce | `real/auth-flow.spec.ts`, `fixtures/auth.ts` | After login URL leaves `/login`; `localStorage.currentUser` set; reload keeps session; a known username/avatar element visible |
| P0-AUTH-02 | Login with wrong password → stays on `/login`, shows error toast/message, no session | New (mock OK) | `auth/login.spec.ts` (mock) | Error message visible; `currentUser` absent; URL still `/login` |
| P0-AUTH-03 | Logout → session cleared, protected route redirects to `/login` | New | auth flow | After logout, `goto('/my/dashboard')` redirects to `/login` |
| P0-TEAM-POST-01..04 | Team timeline: **create → appears → edit → delete (gone)** as hard assertions | **Harden** | `real/write-ops.spec.ts` WRITE-005..009 | Posted text MUST be visible (not `void isVisible`); after delete MUST be absent. Remove skip-on-missing-selector |
| P0-TODO-01..03 | Team TODO: create → visible → complete toggles → delete gone | **Harden** | `real/write-ops.spec.ts` WRITE-010..014 | Same: assert presence/absence, not just `url !== /error` |
| P0-CHAT-01 | Send chat message → message text visible in channel | **Harden** | `real/write-ops.spec.ts` WRITE-015 | Sent text visible; remove `void isVisible` |
| P0-SCHED-01..03 | Team event create → list shows it → delete gone | **Harden** | `real/write-ops.spec.ts` WRITE-018..020 | Created event visible in `/teams/:id/events`; absent after delete |
| P0-BILL-01 | Membership billing CRUD respects role gate (admin can, member cannot) | Reinforce | `real/f089-billing-crud-roles.spec.ts` | Admin sees/creates plan; member is denied (UI hides action AND API 403) |
| P0-BILL-02 | Stripe payment happy path (**real gateway, TEST MODE** — D2) | New | F08.9 docs | Payment completes via Stripe test-mode; membership becomes active. Use test cards (e.g. 4242…); never live keys |
| P0-MATCH-01 | Fixture → record match result → standings reflect score | Reinforce | `real/f0810-entry1-fixture-record.spec.ts` | After recording, standings/table shows updated score (the regression caught in #1444) |
| P0-VIS-01 | F00 visibility: supporter/non-member CANNOT see MEMBERS_AND_ABOVE content; member can | Reinforce | `real/tournament/f087-visibility-matrix.spec.ts`, F00 docs | 4-role matrix: each role sees exactly the allowed visibility levels; **leakage = hard fail** |
| P0-AUTHZ-01 | Non-admin user hitting `/admin/**` or `/system-admin/**` is blocked (UI guard + redirect) | New | `pages/admin/**`, `system-admin/**` | Member navigating to admin route → redirected / 403 page, not the admin UI |

### P1 — Major UX

| ID | Scenario | Status | Evidence | Assertion |
|---|---|---|---|---|
| P1-REG-01 | Register new user (unique email) → email-verify pending state | Reinforce | `auth/register*.spec.ts` | Use timestamped email; assert pending/verify screen |
| P1-CONSENT-01 | Minor birth-date at register → parental-consent flow triggered | Reinforce | F01.9, `parental-consent/` | Consent-required screen appears for under-age DOB |
| P1-SLUG-01 | Old slug → 301 → new slug; team/org reachable by slug not numeric id | Covered (mock) + Reinforce real | `F15.4-slug-navigation.spec.ts` | 301 redirect; canonical slug URL |
| P1-CHAT-REACT | Message reaction add/remove | Harden | WRITE-016 | Reaction badge count changes; revert |
| P1-TIMELINE-LIKE | Post like toggles count | Harden | WRITE-006 | Like count increments then reverts |
| P1-SHIFT-01 | Shift budget alert/allocation display | Covered (mock) | `F08.7_shift_budget/*` | Keep; add real-tier smoke if env allows |
| P1-MATCH-SPORTS | Per-sport recording: basketball continuous, volleyball sets, shogi/go turns | Covered (real) | `real/f0810-*` | Verify these are real assertions, not skips |
| P1-WS-01 | Live match spectator receives WS score updates; unauthorized subscriber gets nothing | Reinforce | `real/f0810-live-spectator-ws.spec.ts` | Authorized client sees push; unauthorized sees 0 leakage |
| P1-MARKET-01 | Create listing → publish → appears in market | Covered (real) | `real/market.crud.real.spec.ts` | Keep/verify |
| P1-WALLET-01 | Wallet balance / point card stamp | Covered | `real/wallet.spec.ts`, `wallet*` | Keep |
| P1-WITHDRAW-01 | Account withdrawal → immediate weak anonymization of notification/calendar settings | New — **see Q4** | withdrawal docs | ⚠️ destructive; needs disposable account |
| P1-ADMIN-01 | Admin announcement create → visible to members | Covered (mock) | `admin/announcements.spec.ts` | Keep; consider real-tier |

### P2 — Edge / 補完

| ID | Scenario | Status | Notes |
|---|---|---|---|
| P2-NET-01 | Network failure on a write → error toast shown, no partial state | New (mock: `page.route` abort) | Mock tier only — deterministic |
| P2-VALIDATION | Required-field / format validation messages on key forms | Partly covered (`validation/*`) | Extend to billing/match forms |
| P2-AUTH-EXPIRE | Expired/invalid session mid-session → redirect to login | New (mock) | Simulate 401 via route |
| P2-TZ-01 | Schedule datetime round-trips in Asia/Tokyo | Covered (`real/schedule-55-tz-real.spec.ts`) | Keep |
| P2-LONGMSG | 100-char chat message | Harden | WRITE-017 |
| P2-RESPONSIVE | Mobile viewport core nav | Partly (`responsive/*`) | Low priority |

### Out of scope (this round)

- Native mobile / camera / GPS / push-tap / background-resume — **no mobile app in this repo** (web
  only; Nuxt SSR). `Patrol`/`Flutter Driver`/`Integration Test` are N/A.
- Lighthouse/perf (separate `lighthouse-ci.yml`), security scan (`security-scan.yml`), infra IaC.
- Full visual-regression / pixel diffs.
- Email/Push actual delivery (assert via outbox API or mock, not real SES).

---

## 3a. MANDATORY — Role × CRUD Matrix (D1 御裁可・最優先要件)

Per D1, every core resource must be verified for **all four roles** through the **full CRUD lifecycle**,
driving the real UI as an actual user. A role being able to do something it **should not** (leakage /
privilege escalation) is a **hard failure**, not a skip.

**Roles under test** (seed via D3):

| Role | Meaning | Expected baseline on a team resource |
|---|---|---|
| **ADMIN** | Org/team administrator | Full CRUD + management actions |
| **MEMBER** | Regular member (`e2e-user`) | Create/read own; read member-visible content; edit/delete own |
| **SUPPORTER** | Supporter (応援者) — NOT internal | Read SCOPE_AFFILIATED only; **excluded** from MEMBERS_AND_ABOVE; no admin/write to internal content |
| **PUBLIC** | Non-member / unauthenticated | Public content only; **everything internal is denied/redirected** |

**Required matrix** — for each target resource (at minimum: **team timeline post, TODO, chat message,
schedule/event**, plus **membership-billing** and **match record**), assert per role:

| Op | ADMIN | MEMBER | SUPPORTER | PUBLIC |
|---|---|---|---|---|
| **Create** | ✅ succeeds | ✅ succeeds (own) | ❌ denied (UI hidden/disabled **and** API 403) | ❌ denied / redirected to `/login` |
| **Read** | ✅ all | ✅ member-visible | ✅ supporter-visible only / ❌ member-only **must not leak** | ✅ public-only / ❌ internal must not leak |
| **Update** | ✅ | ✅ own / ❌ others' | ❌ | ❌ |
| **Delete** | ✅ | ✅ own / ❌ others' | ❌ | ❌ |

Assertion rules for the negative cells:
- A **denied** write must be proven at **both** layers: the UI control is hidden/disabled **and** the
  underlying API returns **403** (intercept via `page.waitForResponse` or a direct `page.request` call
  with that role's session). UI-only hiding is insufficient — an attacker bypasses the UI.
- A **must-not-leak** read must assert the protected content is **absent** for that role (negative
  assertion), routed through the F00 resolver semantics (`docs/features/F00_content_visibility_resolver.md`).
- Drive each role with its own `storageState` (`chromium-real` for member/supporter/admin via separate
  `*.setup.ts`; PUBLIC = a fresh context with **no** storageState).

Suggested structure: one parametrized spec per resource that loops the 4 roles, or a
`real/role-matrix/<resource>.spec.ts` family. Keep each role's data unique & self-cleaned (§5).

---

## 4. Behaviors To Preserve (壊してはいけない既存挙動)

1. **Both Playwright tiers keep working.** Do not change `playwright.config.ts` projects, storageState
   paths, or `webServer` in ways that break the mock `chromium` smoke that `frontend-e2e-ci.yml` runs
   (`F15.4-org-team-search`, `f221-swipe-dashboard`, `F15.4-slug-navigation`).
2. **`loginViaApi` / `loginAs` semantics** in `fixtures/auth.ts` — many specs depend on them. Extend,
   don't rewrite signatures.
3. **Seed contract** — `seed-e2e-data.js` accounts/teams (e2e-user, e2e-admin, FC東京U-18) and the
   ID-by-name resolution in `real/helpers/seed-api.ts`. Do not hardcode IDs.
4. **Generated types** `app/types/generated/index.ts` — never hand-edit (auto-generated from
   `docs/openapi.json`).
5. **Existing real-tier cleanup discipline** — real write specs restore state (delete what they
   create). Preserve this; never leave residue in the shared DB.
6. **i18n** — assert on existing locale keys / visible JA text; do not hardcode new UI strings into the
   product to make a test pass.

---

## 5. Test Infrastructure & Mocking Rules

### Tier choice (default)

- **Default new P0/P1 functional flows → real-BE tier (`tests/e2e/real/**`)**, because the goal is a
  *real* safety net (memory `feedback_e2e_real_full_crud`: read-only/mock paths miss POST casing,
  validation, authz, 500s). Use `loginViaApi` storageState.
- Use the **mock tier (`page.route`)** only for deterministic things that are awkward or destructive on
  a real backend: network-failure handling, 401-expiry, validation-only checks, and the CI smoke
  subset. Mock specs must mock **every** API they touch (backend-independent), per existing pattern.

### External dependency policy (confirm Q2 before implementing P0-BILL-02 / uploads)

| Dependency | Default approach | Note |
|---|---|---|
| Stripe (billing) | **Real gateway in TEST MODE (D2)** — test-mode publishable/secret keys, Stripe test cards (`4242 4242 4242 4242`, any future expiry/CVC) | Never live keys / never production charges |
| S3 / R2 upload (presign) | Prefer backend `ci`/`local` profile that returns presigned URL to a local/stub bucket; assert UI states, not byte delivery | `e2e-real-smoke.yml` uses `ci` profile |
| WebSocket / STOMP | Real BE WS for live-match/chat real specs (already done in `f0810-live-spectator-ws`); assert message arrival + **zero leakage** for unauthorized | Valkey absent in CI → fail-open; presence/ratelimit out of CI scope |
| Email / Push | Assert via outbox/admin API or mock; never assert real delivery | `admin/system-admin-email-outbox` |

### Parallel-execution safety (必須)

- `playwright.config.ts` has `fullyParallel: true`. Assume specs run concurrently and across
  `--shard`. **No test may depend on global mutable state created by another test.**
- Real tier shares ONE seed account (`e2e-user`) and ONE team (FC東京U-18). Therefore:
  - Each write test **creates its own uniquely-named record** (`` `…${Date.now()}` `` is already the
    pattern) and **filters to that record** for every assertion — never "the first item".
  - **Never assert global counts** ("there are 3 todos") — concurrent tests change them.
  - If two specs must mutate the *same* shared singleton (e.g. profile display name), either keep them
    in the **same `describe` (serial)** or guard with unique values + restore. Do not split such
    mutations across parallel files. **See Q3** about seeding additional dedicated accounts/teams.
- Respect `workers`/`--shard`: never write a spec that assumes worker index 0 or a specific order.

---

## 6. Non-Negotiables (絶対遵守)

1. **No fake assertions.** Forbidden as the *only* assertion of a test:
   `expect(page.url()).not.toContain('/error')`, `void isVisible`, and `test.skip()` triggered by a
   missing selector on the happy path. A P0/P1 test must assert the **actual user-visible outcome**
   (element appears / disappears / text/count changes / value persists). `test.skip` is allowed ONLY
   for environment-gated reasons (feature flag off, external dep unavailable) — never to dodge a
   missing element on a path that is supposed to work.
2. **Idempotent.** Every test yields the same result on repeated back-to-back runs. Write tests either
   delete prior data first or use unique identifiers (`Date.now()` / UUID). End state == start state.
3. **Independent.** No test depends on another test's side effects. Each `describe` self-provisions
   (`beforeAll` logs in / resolves IDs) and self-cleans (`afterEach`/`afterAll` deletes what it made,
   even on failure — use `try/finally` or an `afterEach` cleanup).
4. **Stable selectors.** Prefer `getByRole`/`getByLabel`/`getByText` for user-facing intent, and
   **`data-testid`** for anything ambiguous or dynamic. Do NOT rely on PrimeVue internal classes
   (`.p-datatable-tbody tr`, `.p-dialog`) as primary selectors for new tests — add a `data-testid`
   (see §7).
5. **Language**: comments / test titles / descriptions in **Japanese** (CLAUDE.md communication rule).
   Test IDs and code identifiers in English. Make titles convey intent (e.g.
   `'P0-TODO-01: TODO作成→一覧表示→削除で消えることを保証'`).
6. **No hardcoded IDs / no hardcoded base URL.** Use `seed-api.ts` resolvers and `BASE_URL` env.
7. **No incidental refactors.** Touch only test files and the minimal `data-testid` additions. Do not
   "tidy" unrelated product code.

---

## 7. Adding `data-testid` to Product Code (safe, additive only)

Many older flows (timeline, todo, chat, schedule in `real/write-ops`) lack testids, forcing loose
selectors. You may add testids, under strict rules:

- **Additive attribute only.** Add `data-testid="kebab-name"` to existing elements. Do NOT change tags,
  classes, layout, props, v-model, or any logic. Zero visual/behavioral delta.
- Follow the existing convention (feature-namespaced kebab-case; see `components/action-memo/*`).
  Suggested set for the harden targets: `team-timeline-composer`, `team-timeline-submit`,
  `team-timeline-post` (+ `…-delete`, `…-edit`), `team-todo-create`, `team-todo-row`,
  `team-todo-complete`, `team-todo-delete`, `team-chat-input`, `team-chat-send`, `team-chat-message`,
  `team-event-create`, `team-event-row`, `team-event-delete`.
- Add testids in a **separate commit** from the test logic so it is trivially revertible.
- ⚠️ This repo **forbids direct commits on the honjin working dir** (`C:\Claude\mannschaft`) — work in a
  git worktree (see §11). Confirm your CWD first.

---

## 8. Stop And Ask Conditions (実装を止めて質問せよ)

Stop and ask the human (do NOT guess the expected value) when:

1. You cannot tell from code/docs what the **correct** behavior is (e.g. exact error message text,
   whether an action should be hidden vs. disabled vs. 403 for a given role).
2. The current implementation **looks buggy** but you cannot confirm whether that bug is the
   intended/"正" current behavior to lock in.
3. A test would require **destructive or irreversible** real-data ops (withdrawal/anonymization,
   payment capture, deleting seed accounts).
4. External-dependency mocking strategy is undecided (Stripe, real upload bucket).
5. A flow needs a **test account/role that the seed does not provide** (e.g. an org-ADMIN, a SUPPORTER,
   a second non-member user for authz-negative tests).

Use this format inline in any spec/PR where you proceed on an assumption:
```
▎ ⚠️ UNVERIFIED: この期待値はコードからの推測です。
▎ 実装前に人間に確認: 「<具体的な質問>」
```

---

## 9. Baseline Test Commands

From `frontend/`:

```bash
# install (first time)
npm ci
npx playwright install --with-deps chromium

# Mock tier — single spec / pattern (backend NOT required)
npx playwright test <pattern> --project=chromium --workers=1

# Mock CI smoke (what frontend-e2e-ci.yml runs)
npx playwright test F15.4-org-team-search f221-swipe-dashboard F15.4-slug-navigation --project=chromium --workers=1

# Real tier (D1/D5 — local, NOT CI) — REQUIRES backend up at :8080 + seed applied
#   1) start backend (ci/local profile)  2) node backend/scripts/seed-e2e-data.js (role accounts — D3)
#   3) start frontend on an open port (8081) and set BASE_URL to match
BASE_URL=http://localhost:8081 npx playwright test --project=chromium-real <pattern>
BASE_URL=http://localhost:8081 npx playwright test --project=chromium-real-admin <pattern>
# Stripe test-mode env (D2): export STRIPE_*_KEY test keys for the backend before billing specs

# Reports
npx playwright show-report        # last HTML report
npx playwright test <pattern> --trace on   # debug a flake
```

Notes:
- `playwright.config.ts` `webServer` auto-starts `npm run dev` on `BASE_URL`'s port (default 8081).
  For the **real tier**, start the frontend yourself and set `BASE_URL` to match (the older real specs
  comment `:3000`). Confirm the port you actually run on.
- `retries: 2` and `workers: 1` are set under `CI`. Locally retries=0 — a test that needs retries to
  pass is flaky and must be fixed, not retried.

---

## 10. Implementation Phases (small, safe, ordered)

> Run `git status` first. Keep your test changes isolated from any pre-existing uncommitted changes.

**Phase 0 — Environment & baseline (no test logic yet)**
- Confirm CWD is a worktree (§11), `npm ci`, browsers installed.
- Bring up backend + seed (for real tier) OR confirm mock tier runs.
- Run the existing CI smoke and 1 existing real spec to confirm the harness works on this machine.
  Record the exact commands that worked.

**Phase 1 — Add missing `data-testid` (separate commit)**
- Add the §7 testids to the harden-target components. Verify build/lint unaffected
  (`npm run typecheck && npm run lint`). No behavior change.

**Phase 2 — Simplest deterministic happy paths first**
- P0-AUTH-01/03 (login persists / logout redirects), P0-AUTHZ-01 (admin route guard).
  These are low-dependency and prove the auth + routing spine.

**Phase 3 — Harden the fake-pass write flows (highest ROI)**
- P0-TEAM-POST, P0-TODO, P0-CHAT, P0-SCHED: replace skip-fallbacks + `url !== /error` with real
  presence/absence assertions using the new testids. Keep the existing cleanup. One `describe` at a
  time; run after each.

**Phase 4 — Role × CRUD matrix (§3a — MANDATORY per D1) + critical state-change flows**
- Implement the **4-role × CRUD matrix (§3a)** for team timeline / TODO / chat / schedule — this is the
  core of D1 and the highest-value safety net. Seed the role accounts first (D3).
- Then P0-BILL-01 (role gate), **P0-BILL-02 (Stripe test-mode, D2)**, P0-MATCH-01 (standings reflect),
  P1-WS-01 (live + zero-leakage).

**Phase 5 — Abnormal / boundary (mock tier where deterministic)**
- P2-NET-01, P2-AUTH-EXPIRE, P2-VALIDATION, P0-AUTH-02.

**Phase 6 — Stability hardening**
- Run each new/hardened spec **5× consecutively**; any non-deterministic pass/fail must be root-caused
  (replace `waitForTimeout` with state-based waits: `expect(locator).toBeVisible()`, spinner-detached,
  `waitForResponse`). Do not paper over with longer timeouts or retries.

---

## 11. Constraints On You (the implementing model)

- **First action: `git status`.** Do not mix your test changes with unrelated pre-existing diffs.
- **CWD discipline (CLAUDE.md / memory `feedback_branch_isolation`):** the honjin dir
  `C:\Claude\mannschaft` rejects direct commits via pre-commit hook. Work in a dedicated git worktree
  / feature branch (`feature/e2e-...`). Confirm `pwd` before editing.
- Keep changes **small and revertible**; testid additions and test logic in **separate commits**.
- When adding a `data-testid`, change nothing else about that element — zero impact on look/logic.
- **Never invent expected values.** If correctness is unclear, stop and ask (§8) — do not encode a
  guessed assertion.
- Run/verify **after each scenario** (not at the very end).
- **No incidental refactoring** of product code while writing tests.
- Final report: the exact last command run + the pass/fail result table (§13).

---

## 12. Verification & CI Requirements

- A new spec is "done" only when it **passes 5/5 consecutive local runs** at `--workers=1` AND once
  with default parallel workers (to catch shared-state collisions).
- No `waitForTimeout`-driven assertions on new code; use web-first assertions / `waitForResponse` /
  spinner-detached.
- Each spec is independently runnable: `npx playwright test <thatFile>` passes from a clean DB state.
- Cleanup proven: after a full run, the shared seed DB has no leftover `E2Eテスト…${ts}` records.
- (Stretch, propose only) If real-tier specs should join CI, that needs a backend-up job like
  `e2e-real-smoke.yml` extended to run Playwright — **do not enable this without sign-off** (cost/time;
  see Q1).

---

## 13. Reporting Format (完了報告)

Report back to the human in this shape:

```
## E2E自動化 完了報告
- 対象フェーズ: <Phase N / シナリオID群>
- ブランチ / worktree: <path / branch>
- 追加/強化したファイル:
  - tests/e2e/real/<file>.spec.ts (P0-TODO-01..03 をハード化)
  - app/components/.../<Comp>.vue (data-testid 追加のみ・別コミット)

## 実行コマンド（最後に流したもの）
$ BASE_URL=http://localhost:3000 npx playwright test real/write-ops --project=chromium-real --workers=1

## 結果
| ID | 結果 | 安定性(5回) | 備考 |
|----|------|-------------|------|
| P0-TODO-01 | PASS | 5/5 | |
| P0-TODO-02 | PASS | 5/5 | |
| P0-BILL-02 | 未着手 | - | ⚠️ Q2(Stripeモック方針)未確定のため保留 |

## UNVERIFIED / 要確認
- <stop&ask に該当して保留した項目を列挙>

## クリーンアップ確認
- 共有DBに E2Eテスト 残データ無し（確認方法: ...）
```

---

## 14. Resolved Decisions (確定事項 — マスター御裁可済み)

These were open questions; the human has decided. **Implement to these, do not re-ask.**

1. **D1 — Run real-BE Playwright E2E locally (not a CI gate).** Target the **real tier**
   (`chromium-real` / `chromium-real-admin`) running locally against a live backend. **Do NOT add a
   real-BE CI job.** Use whatever **open port** (e.g. **8081**) for the frontend; set `BASE_URL`
   accordingly. Tests must simulate **realistic user operation** (drive the actual UI as a user would).
   → **MANDATORY: full CRUD coverage AND a role check across all 4 roles —
   MEMBER / SUPPORTER / PUBLIC(=non-member/unauthenticated) / ADMIN.** See §3a (Role × CRUD matrix).
2. **D2 — Stripe: hit the real gateway in TEST MODE.** Use Stripe **test-mode keys** against the real
   Stripe gateway for P0-BILL-02 (no live keys, no production charges). File upload similarly uses the
   real presign flow against the test/local bucket.
3. **D3 — Extend the seed with dedicated accounts/roles (whatever makes testing easiest).** You MAY (and
   should) extend `backend/scripts/seed-e2e-data.js` to provision the role matrix and avoid shared-state
   collisions: a **non-member/PUBLIC user**, an explicit **org/team ADMIN**, a **SUPPORTER**, and
   **MEMBER** (existing `e2e-user`), plus **per-shard dedicated teams** if parallel collisions appear.
   Keep additions idempotent (`INSERT IGNORE` / deterministic IDs by name).
4. **D4 — Withdrawal (P1-WITHDRAW-01): your call (よきにはからえ).** Recommended: use a **dedicated
   disposable account** that the seed re-provisions each run, perform the **real** withdrawal, and
   assert the immediate weak-anonymization outcome. If re-provisioning a withdrawn account cannot be
   made idempotent within one seed run, fall back to **mock-only** (assert the UI flow + the
   `UserAnonymizedEvent`/settings-reset via API) and mark it `▎ ⚠️ UNVERIFIED` with the reason.
5. **D5 — Frontend port: any open port (use 8081).** Run the real-tier frontend on an open port (8081
   recommended for consistency with the config default) and pass `BASE_URL=http://localhost:8081`.
