/**
 * F03.4 予約管理 — 実機フルスタックE2E（実ブラウザ Playwright + 実BE :8080）
 *
 * 御下命:
 *   1. 「SUPPORTER / MEMBER 個人が予約できるか」を実機で確認する（核心）。
 *   2. 設計書 F03.4 記載の主要機能（実装済みのもの）が実機で動くか検証する。
 *      reject は設計のみ(Phase2未実装)のため対象外。
 *
 * 前提:
 *   - BE http://localhost:8080 稼働中（共有資源・再起動禁止）。
 *   - 本テストは専用 dev サーバー :3001 で実行する（BASE_URL=http://localhost:3001）。
 *   - 既存の reservation-dashboard-real.spec.ts のログイン手法／APIブリッジに倣う
 *     （:8080 絶対URLで自前ログインし Bearer/Cookie で自己完結。memory:
 *      feedback_e2e_wsl2_cors_apibridge / feedback_e2e_real_full_crud）。
 *
 * テストアカウント（seed: backend/scripts/seed-e2e-data.js）:
 *   - ADMIN:     e2e-admin@test.mannschaft.local     / TestPass2026!（fc-u-18 ADMIN + SYSTEM_ADMIN）
 *   - MEMBER:    e2e-user@test.mannschaft.local       / TestPass2026!（fc-u-18 MEMBER）
 *   - SUPPORTER: e2e-supporter@test.mannschaft.local  / TestPass2026!（fc-u-18 SUPPORTER）
 *       ※ SUPPORTER アカウントは seed に無かったため、本検証用に seed スクリプトへ
 *         追加した（user_roles role_id=5 + memberships role_kind=SUPPORTER）。
 *         seed は冪等（INSERT IGNORE / 存在チェック）なので再実行で増殖しない。
 *
 * 実機検証で判明した F03.4 設計との相違（本物の挙動。握りつぶさず本テストで明示的に固定する）:
 *   - 承認モード AUTO の自動確定が未配線: createReservation は slot.approval_mode に
 *     関係なく常に PENDING で着地する（entity 既定 status=PENDING のまま confirm されない）。
 *     設計 §3 reservations では AUTO は PENDING をスキップして CONFIRMED 着地のはず。
 *     → 本テストは「会員/サポーターの予約は PENDING で成立し、ADMIN confirm で CONFIRMED」
 *        という実挙動を assert する（B シナリオ）。
 *   - 枠作成バリデーション未実装/相違（別途 §F034-VALIDATION で実挙動を記録）:
 *       30分単位チェックなし / 過去日チェックなし(枠作成) / ライン最大5本チェックなし /
 *       start>=end は 400 でなく 500（RESERVATION_007 が Severity.ERROR）/
 *       予約入り枠の DELETE が 409 でなく 204（予約がオーファン化）。
 *   - キャンセル期限(cancel_deadline_hours)未実装: ユーザーはいつでもキャンセル可（400 にならない）。
 *   - CONFIRMED 時のリマインド自動生成なし（手動作成のみ）。
 */

import { test as base, expect, request as playwrightRequest, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = 'http://localhost:8080'
const TEAM_SLUG = 'fc-u-18'

const ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const MEMBER = {
  email: process.env.TEST_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_MEMBER_PASSWORD ?? 'TestPass2026!',
}
const SUPPORTER = {
  email: process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-supporter@test.mannschaft.local',
  password: process.env.TEST_SUPPORTER_PASSWORD ?? 'TestPass2026!',
}

interface MeProfile {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

/**
 * ワーカースコープで ADMIN / MEMBER / SUPPORTER の accessToken を一度だけ取得する。
 * 多並列の同時ログインで稀に BE が 500 を返すため、ログイン回数を worker あたり最小に抑える。
 */
const test = base.extend<
  { adminToken: string; adminInit: boolean },
  { tokens: { admin: string; member: string; supporter: string; adminMe: MeProfile } }
>({
  // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
  storageState: async ({}, use) => {
    await use(undefined)
  },
  tokens: [
    // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
    async ({}, use) => {
      const ctx = await playwrightRequest.newContext()
      const login = async (email: string, password: string): Promise<string> => {
        const res = await ctx.post(`${BE}/api/v1/auth/login`, {
          headers: { 'Content-Type': 'application/json' },
          data: { email, password },
        })
        if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
        return (await res.json()).data.accessToken as string
      }
      const admin = await login(ADMIN.email, ADMIN.password)
      const member = await login(MEMBER.email, MEMBER.password)
      const supporter = await login(SUPPORTER.email, SUPPORTER.password)
      const meRes = await ctx.get(`${BE}/api/v1/users/me`, {
        headers: { Authorization: `Bearer ${admin}` },
      })
      if (!meRes.ok()) throw new Error(`/users/me 失敗: ${meRes.status()}`)
      const adminMe = (await meRes.json()).data as MeProfile
      await ctx.dispose()
      await use({ admin, member, supporter, adminMe })
    },
    { scope: 'worker' },
  ],
  adminToken: async ({ tokens }, use) => {
    await use(tokens.admin)
  },
  // UI テスト用: ブラウザに ADMIN の認証 Cookie + localStorage を仕込む
  adminInit: [
    async ({ page, tokens }, use) => {
      await page.request.post(`${BE}/api/v1/auth/login`, {
        headers: { 'Content-Type': 'application/json' },
        data: { email: ADMIN.email, password: ADMIN.password },
      })
      const me = tokens.adminMe
      const currentUser = {
        id: me.id,
        email: me.email,
        fullName: `${me.lastName} ${me.firstName}`,
        profileImageUrl: me.avatarUrl,
        systemRole: me.systemRole ?? undefined,
        timezone: me.timezone ?? undefined,
      }
      const farFuture = Date.now() + 24 * 60 * 60 * 1000
      await page.addInitScript(
        ({ user, expiresAt }) => {
          localStorage.setItem('currentUser', JSON.stringify(user))
          localStorage.setItem('tokenExpiresAt', String(expiresAt))
        },
        { user: currentUser, expiresAt: farFuture },
      )
      await page.goto('/')
      await use(true)
    },
    { auto: true },
  ],
})

test.setTimeout(120_000)

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

/** APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。:3001 は BE CORS 非許可のため中継する。 */
async function installApiBridge(
  page: import('@playwright/test').Page,
  token: string,
): Promise<void> {
  const pageOrigin = new URL(page.url() || 'http://localhost:3001').origin
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const target = `http://127.0.0.1:8080${url.pathname}${url.search}`
    const headers: Record<string, string> = {
      ...req.headers(),
      origin: 'http://localhost:3000',
      referer: 'http://localhost:3000/',
      authorization: `Bearer ${token}`,
    }
    const relay = await page.request.fetch(target, {
      method: req.method(),
      headers,
      data: req.postData() ?? undefined,
      maxRedirects: 0,
    })
    const respHeaders: Record<string, string> = { ...relay.headers() }
    respHeaders['access-control-allow-origin'] = pageOrigin
    respHeaders['access-control-allow-credentials'] = 'true'
    await route.fulfill({ status: relay.status(), headers: respHeaders, body: await relay.body() })
  })
}

// ---------------------------------------------------------------------------
// BE 直接ヘルパー（Bearer 認証）
// ---------------------------------------------------------------------------

async function createLine(
  request: APIRequestContext,
  token: string,
  name: string,
): Promise<{ id: number }> {
  const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
    headers: authHeaders(token),
    data: { name },
  })
  if (!resp.ok()) throw new Error(`createLine 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data
}

async function deleteLine(request: APIRequestContext, token: string, lineId: number): Promise<void> {
  await request.delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines/${lineId}`, {
    headers: authHeaders(token),
  })
}

async function createSlot(
  request: APIRequestContext,
  token: string,
  body: { slotDate: string; startTime: string; endTime: string },
): Promise<{ id: number }> {
  const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
    headers: authHeaders(token),
    data: body,
  })
  if (!resp.ok()) throw new Error(`createSlot 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data
}

async function deleteSlot(request: APIRequestContext, token: string, slotId: number): Promise<void> {
  await request.delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slotId}`, {
    headers: authHeaders(token),
  })
}

/** 任意ロールの予約を ADMIN 権限で確実にキャンセルして後片付けする。 */
async function adminCancel(
  request: APIRequestContext,
  token: string,
  reservationId: number,
): Promise<void> {
  await request
    .post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/cancel`, {
      headers: authHeaders(token),
      data: { reason: 'E2E cleanup' },
    })
    .catch(() => {})
}

function futureDate(daysAhead: number): string {
  const d = new Date()
  d.setDate(d.getDate() + daysAhead)
  return d.toISOString().slice(0, 10)
}

// ===========================================================================
// シナリオA: ロール別の予約可否（御下命の核心）
//   ADMIN がライン+枠を用意 → MEMBER / SUPPORTER がそれぞれ予約成立すること
// ===========================================================================

test.describe('RSV-F034-A: ロール別の予約可否（MEMBER / SUPPORTER）', () => {
  // (ロールトークン, ラベル) の組み合わせでパラメタライズ
  const cases: { label: string; tokenKey: 'member' | 'supporter' }[] = [
    { label: 'MEMBER', tokenKey: 'member' },
    { label: 'SUPPORTER', tokenKey: 'supporter' },
  ]

  for (const { label, tokenKey } of cases) {
    test(`RSV-F034-A-${label}: ${label} は予約を成立できる（PENDING で着地）`, async ({
      request,
      tokens,
    }) => {
      const admin = tokens.admin
      const actorToken = tokens[tokenKey]

      const line = await createLine(request, admin, `F034A_${label}_${Date.now()}`)
      const slot = await createSlot(request, admin, {
        slotDate: futureDate(33),
        startTime: '10:00',
        endTime: '10:30',
      })
      let reservationId: number | null = null
      try {
        const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
          headers: authHeaders(actorToken),
          data: { reservationSlotId: slot.id, lineId: line.id, userNote: `${label} 予約` },
        })
        // 核心の assert: ロールゲート無し（認証のみ）なので MEMBER も SUPPORTER も 201 で成立する
        expect(
          resp.status(),
          `${label} の予約作成が失敗: ${resp.status()} ${await resp.text()}`,
        ).toBe(201)
        const created = (await resp.json()).data
        reservationId = created.id
        expect(created.id).toBeTruthy()
        // 予約者は actor 本人
        expect(created.identifier.lineId).toBe(line.id)
        expect(created.identifier.reservationSlotId).toBe(slot.id)
        // 実挙動: AUTO 自動確定は未配線のため PENDING で着地する
        expect(created.status.status).toBe('PENDING')

        // ADMIN の予約一覧(PENDING)に actor の予約が現れる
        const list = await request.get(
          `${BE}/api/v1/teams/${TEAM_SLUG}/reservations?status=PENDING`,
          { headers: authHeaders(admin) },
        )
        expect(list.status()).toBe(200)
        const body = await list.json()
        const found = (body.data ?? []).find((r: { id: number }) => r.id === reservationId)
        expect(found, `${label} の予約が PENDING 一覧に出ない`).toBeTruthy()
      } finally {
        if (reservationId) await adminCancel(request, admin, reservationId)
        await deleteSlot(request, admin, slot.id).catch(() => {})
        await deleteLine(request, admin, line.id).catch(() => {})
      }
    })
  }

  test('RSV-F034-A-ROLE: SUPPORTER の実効ロールが SUPPORTER であることを確認', async ({ tokens }) => {
    // 「SUPPORTER として」予約できたことの裏取り。実効ロール解決(memberships 統合)が SUPPORTER を返す。
    const ctx = await playwrightRequest.newContext()
    const resp = await ctx.get(`${BE}/api/v1/teams/${TEAM_SLUG}/me/permissions`, {
      headers: authHeaders(tokens.supporter),
    })
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(body.data.roleName).toBe('SUPPORTER')
    await ctx.dispose()
  })
})

// ===========================================================================
// シナリオB: 承認モード（PENDING → ADMIN confirm → CONFIRMED）
//   実装は approval_mode に関係なく常に PENDING 着地（AUTO 自動確定は未配線）。
//   ADMIN による手動 confirm で CONFIRMED になる経路を一気通貫で検証する。
// ===========================================================================

test.describe('RSV-F034-B: 承認フロー PENDING→CONFIRMED', () => {
  test('RSV-F034-B-01: MEMBER 予約(PENDING) → ADMIN confirm → CONFIRMED → complete', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const line = await createLine(request, admin, `F034B_${Date.now()}`)
    const slot = await createSlot(request, admin, {
      slotDate: futureDate(34),
      startTime: '11:00',
      endTime: '11:30',
    })
    let reservationId: number | null = null
    try {
      const createResp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
        headers: authHeaders(tokens.member),
        data: { reservationSlotId: slot.id, lineId: line.id },
      })
      expect(createResp.status()).toBe(201)
      const created = (await createResp.json()).data
      reservationId = created.id
      expect(created.status.status).toBe('PENDING')

      // ADMIN confirm → CONFIRMED
      const confirmResp = await request.post(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/confirm`,
        { headers: authHeaders(admin) },
      )
      expect(confirmResp.ok(), `confirm 失敗: ${confirmResp.status()} ${await confirmResp.text()}`).toBe(true)
      const confirmed = (await confirmResp.json()).data
      expect(confirmed.status.status).toBe('CONFIRMED')
      expect(confirmed.status.confirmedAt).toBeTruthy()

      // complete → COMPLETED
      const completeResp = await request.post(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/complete`,
        { headers: authHeaders(admin) },
      )
      expect(completeResp.ok()).toBe(true)
      expect((await completeResp.json()).data.status.status).toBe('COMPLETED')
      reservationId = null // COMPLETED は terminal。cancel 不要
    } finally {
      if (reservationId) await adminCancel(request, admin, reservationId)
      await deleteSlot(request, admin, slot.id).catch(() => {})
      await deleteLine(request, admin, line.id).catch(() => {})
    }
  })

  test('RSV-F034-B-02: CONFIRMED 予約を no-show にできる', async ({ request, tokens }) => {
    const admin = tokens.admin
    const line = await createLine(request, admin, `F034BNS_${Date.now()}`)
    const slot = await createSlot(request, admin, {
      slotDate: futureDate(35),
      startTime: '12:00',
      endTime: '12:30',
    })
    let reservationId: number | null = null
    try {
      const created = (
        await (
          await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
            headers: authHeaders(tokens.member),
            data: { reservationSlotId: slot.id, lineId: line.id },
          })
        ).json()
      ).data
      reservationId = created.id
      await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/confirm`, {
        headers: authHeaders(admin),
      })
      const nsResp = await request.post(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/no-show`,
        { headers: authHeaders(admin) },
      )
      expect(nsResp.ok(), `no-show 失敗: ${nsResp.status()} ${await nsResp.text()}`).toBe(true)
      expect((await nsResp.json()).data.status.status).toBe('NO_SHOW')
      reservationId = null // NO_SHOW は terminal
    } finally {
      if (reservationId) await adminCancel(request, admin, reservationId)
      await deleteSlot(request, admin, slot.id).catch(() => {})
      await deleteLine(request, admin, line.id).catch(() => {})
    }
  })
})

// ===========================================================================
// シナリオC: F03.4 主要機能（実装済み）の一気通貫
//   reschedule / user cancel / リマインド手動作成 / 横断API
// ===========================================================================

test.describe('RSV-F034-C: 主要機能（reschedule / cancel / reminder / 横断API）', () => {
  test('RSV-F034-C-01: ユーザーは自分の PENDING 予約をキャンセルできる', async ({ request, tokens }) => {
    const admin = tokens.admin
    const line = await createLine(request, admin, `F034C1_${Date.now()}`)
    const slot = await createSlot(request, admin, {
      slotDate: futureDate(36),
      startTime: '13:00',
      endTime: '13:30',
    })
    try {
      const created = (
        await (
          await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
            headers: authHeaders(tokens.member),
            data: { reservationSlotId: slot.id, lineId: line.id },
          })
        ).json()
      ).data
      // ユーザー本人キャンセル（cancel_deadline_hours は未実装のため、いつでも 200）
      const cancelResp = await request.post(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${created.id}/cancel`,
        { headers: authHeaders(tokens.member), data: { reason: '都合により' } },
      )
      expect(cancelResp.ok(), `ユーザーキャンセル失敗: ${cancelResp.status()} ${await cancelResp.text()}`).toBe(true)
      expect((await cancelResp.json()).data.status.status).toBe('CANCELLED')
    } finally {
      await deleteSlot(request, admin, slot.id).catch(() => {})
      await deleteLine(request, admin, line.id).catch(() => {})
    }
  })

  test('RSV-F034-C-02: reschedule で別枠・別ラインへ移動できる', async ({ request, tokens }) => {
    const admin = tokens.admin
    const line = await createLine(request, admin, `F034C2_${Date.now()}`)
    const slotA = await createSlot(request, admin, {
      slotDate: futureDate(37),
      startTime: '14:00',
      endTime: '14:30',
    })
    const slotB = await createSlot(request, admin, {
      slotDate: futureDate(37),
      startTime: '15:00',
      endTime: '15:30',
    })
    let reservationId: number | null = null
    try {
      const created = (
        await (
          await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
            headers: authHeaders(tokens.member),
            data: { reservationSlotId: slotA.id, lineId: line.id },
          })
        ).json()
      ).data
      reservationId = created.id
      const reResp = await request.post(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/reschedule`,
        { headers: authHeaders(admin), data: { newSlotId: slotB.id } },
      )
      expect(reResp.ok(), `reschedule 失敗: ${reResp.status()} ${await reResp.text()}`).toBe(true)
      const rescheduled = (await reResp.json()).data
      // 移動先スロットに付け替わっている
      expect(rescheduled.identifier.reservationSlotId).toBe(slotB.id)
    } finally {
      if (reservationId) await adminCancel(request, admin, reservationId)
      await deleteSlot(request, admin, slotA.id).catch(() => {})
      await deleteSlot(request, admin, slotB.id).catch(() => {})
      await deleteLine(request, admin, line.id).catch(() => {})
    }
  })

  test('RSV-F034-C-03: リマインドを手動作成・取得できる（自動生成はされない）', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const line = await createLine(request, admin, `F034C3_${Date.now()}`)
    const slot = await createSlot(request, admin, {
      slotDate: futureDate(38),
      startTime: '16:00',
      endTime: '16:30',
    })
    let reservationId: number | null = null
    try {
      const created = (
        await (
          await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
            headers: authHeaders(tokens.member),
            data: { reservationSlotId: slot.id, lineId: line.id },
          })
        ).json()
      ).data
      reservationId = created.id
      await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/confirm`, {
        headers: authHeaders(admin),
      })
      // 実挙動: CONFIRMED でも自動リマインドは生成されない（設計は2件自動生成）
      const before = await request.get(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/reminders`,
        { headers: authHeaders(admin) },
      )
      expect(before.status()).toBe(200)
      expect(((await before.json()).data ?? []).length).toBe(0)
      // 手動作成は可能
      const remindAt = new Date(Date.now() + 37 * 24 * 60 * 60 * 1000)
        .toISOString()
        .slice(0, 19)
      const addResp = await request.post(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/reminders`,
        { headers: authHeaders(admin), data: { remindAt } },
      )
      expect(addResp.status(), `リマインド作成失敗: ${await addResp.text()}`).toBe(201)
      const after = await request.get(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/reminders`,
        { headers: authHeaders(admin) },
      )
      expect(((await after.json()).data ?? []).length).toBe(1)
    } finally {
      if (reservationId) await adminCancel(request, admin, reservationId)
      await deleteSlot(request, admin, slot.id).catch(() => {})
      await deleteLine(request, admin, line.id).catch(() => {})
    }
  })

  test('RSV-F034-C-04: 横断API /reservations/my /upcoming /stats が 200 で応答する', async ({
    request,
    tokens,
  }) => {
    const my = await request.get(`${BE}/api/v1/reservations/my`, {
      headers: authHeaders(tokens.member),
    })
    expect(my.status()).toBe(200)
    expect(await my.json()).toHaveProperty('data')

    const upcoming = await request.get(`${BE}/api/v1/reservations/upcoming`, {
      headers: authHeaders(tokens.member),
    })
    expect(upcoming.status()).toBe(200)
    expect(await upcoming.json()).toHaveProperty('data')

    const stats = await request.get(
      `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/stats?from=${futureDate(0)}&to=${futureDate(60)}`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(stats.status()).toBe(200)
    const statsBody = (await stats.json()).data
    // 実装は件数サマリ（totalReservations / *Count）を返す
    expect(statsBody).toHaveProperty('totalReservations')
    expect(statsBody).toHaveProperty('confirmedCount')
  })
})

// ===========================================================================
// §F034-VALIDATION: 設計書のバリデーションと実装の相違を実機で固定する
//   ※ いずれも「設計では弾く/別ステータスのはず」だが実装は通す/別コードを返す。
//     根治はしない（殿の指示待ち）。本物の挙動を assert して退行検知の番人とする。
//     コメントで設計の正解を併記し、後でバグ修正したらこのテストが落ちて気付ける。
// ===========================================================================

test.describe('RSV-F034-VALIDATION: 設計との相違（実挙動の番人。設計の正解はコメント）', () => {
  test('RSV-F034-V-01: start>=end は 500 を返す（設計の正解=400。RESERVATION_007 が Severity.ERROR）', async ({
    request,
    tokens,
  }) => {
    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: futureDate(40), startTime: '11:00', endTime: '10:30' },
    })
    // 実挙動: 500。設計通りなら 400 にすべき（INVALID_TIME_RANGE を WARN へ）。
    expect(resp.status()).toBe(500)
  })

  test('RSV-F034-V-02: 30分単位でない時刻(10:15)でも枠作成が通る（設計の正解=400）', async ({
    request,
    tokens,
  }) => {
    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: futureDate(40), startTime: '10:15', endTime: '10:45' },
    })
    expect(resp.status()).toBe(201) // 実挙動: 通る。設計は 30分単位チェックで 400。
    await deleteSlot(request, tokens.admin, (await resp.json()).data.id).catch(() => {})
  })

  test('RSV-F034-V-03: 過去日でも枠作成が通る（設計の正解=400）', async ({ request, tokens }) => {
    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: futureDate(-2), startTime: '10:00', endTime: '10:30' },
    })
    expect(resp.status()).toBe(201) // 実挙動: 通る。設計は過去日チェックで 400。
    await deleteSlot(request, tokens.admin, (await resp.json()).data.id).catch(() => {})
  })

  test('RSV-F034-V-04: 予約が入っている枠の DELETE が 204 で通る（設計の正解=409。予約がオーファン化）', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const line = await createLine(request, admin, `F034V4_${Date.now()}`)
    const slot = await createSlot(request, admin, {
      slotDate: futureDate(39),
      startTime: '17:00',
      endTime: '17:30',
    })
    const created = (
      await (
        await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
          headers: authHeaders(tokens.member),
          data: { reservationSlotId: slot.id, lineId: line.id },
        })
      ).json()
    ).data
    // 実挙動: 予約入りでも 204 で削除できてしまう（予約がオーファン化する重大な相違）
    const delResp = await request.delete(
      `${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slot.id}`,
      { headers: authHeaders(admin) },
    )
    expect(delResp.status()).toBe(204)
    // 後片付け: オーファンになった予約を ADMIN がキャンセル試行（slot 消失でキャンセル不能なため best-effort）
    await adminCancel(request, admin, created.id)
    await deleteLine(request, admin, line.id).catch(() => {})
  })
})

// ===========================================================================
// シナリオ(UI): 会員が実ブラウザで「予約する」タブから予約を成立させる
//   FE→BE 契約一致（#1597 根治）の再発防止。ADMIN セッションで操作する。
// ===========================================================================

test.describe('RSV-F034-UI: 実ブラウザで予約管理ページが描画され予約導線が動く', () => {
  test('RSV-F034-UI-01: /teams/fc-u-18/reservations が描画され致命エラーが出ない', async ({
    page,
  }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })
    await page.goto(`/teams/${TEAM_SLUG}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.waitForTimeout(2_000)
    const bodyText = (await page.locator('body').textContent()) ?? ''
    expect(page.url()).toContain(`/teams/${TEAM_SLUG}/reservations`)
    const hasReservationUi =
      bodyText.includes('予約') || (await page.getByRole('tab').count()) > 0
    expect(hasReservationUi).toBe(true)
    const fatal = consoleErrors.filter((e) => /Cannot read|undefined is not|Hydration|TypeError/i.test(e))
    expect(fatal.length, `致命的コンソールエラー: ${fatal.join(' / ')}`).toBe(0)
  })

  test('RSV-F034-UI-02: UI で「予約する」→ライン/スロット選択→送信で予約が成立する', async ({
    page,
    request,
    adminToken,
  }) => {
    const lineName = `F034UI_${Date.now()}`
    const line = await createLine(request, adminToken, lineName)
    const slotDate = futureDate(40)
    const startM = Date.now() % 60
    const startTime = `14:${String(startM).padStart(2, '0')}`
    const endTotalMin = 14 * 60 + startM + 30
    const endTime = `${String(Math.floor(endTotalMin / 60)).padStart(2, '0')}:${String(endTotalMin % 60).padStart(2, '0')}`
    const slot = await createSlot(request, adminToken, { slotDate, startTime, endTime })
    let reservationId: number | null = null

    try {
      await installApiBridge(page, adminToken)
      await page.goto(`/teams/${TEAM_SLUG}/reservations`, { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)

      const bookTab = page.getByRole('tab', { name: '予約する' })
      if (await bookTab.count()) await bookTab.click()

      const lineSelect = page.locator('.p-select').first()
      await lineSelect.waitFor({ state: 'visible', timeout: 15_000 })
      await lineSelect.click()
      await page.getByRole('option', { name: lineName }).click()

      const ymd = slotDate.replaceAll('-', '/')
      const dateInput = page.locator('.p-datepicker-input').first()
      await dateInput.waitFor({ state: 'visible', timeout: 15_000 })
      await dateInput.click()
      await dateInput.press('Escape')
      await dateInput.fill(ymd)
      await dateInput.press('Enter')
      await dateInput.press('Tab')

      const slotTimeRe = new RegExp(`${startTime}:00.*空きあり`)
      const slotButton = page.getByRole('button', { name: slotTimeRe }).first()
      await slotButton.waitFor({ state: 'visible', timeout: 15_000 })
      await slotButton.click()

      const dialog = page.getByRole('dialog')
      await dialog.waitFor({ state: 'visible', timeout: 10_000 })
      await dialog.getByRole('button', { name: '予約する' }).click()

      await expect(page.getByText('予約が完了しました')).toBeVisible({ timeout: 15_000 })

      // BE 裏取り: UI 操作の結果が一覧(PENDING)に反映される
      const pendingList = await request.get(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations?status=PENDING`,
        { headers: authHeaders(adminToken) },
      )
      expect(pendingList.status()).toBe(200)
      const created = ((await pendingList.json()).data ?? []).find(
        (r: { id: number; identifier?: { reservationSlotId?: number } }) =>
          r.identifier?.reservationSlotId === slot.id,
      )
      expect(created, `UI から作成した予約が PENDING 一覧に出ない（slotId=${slot.id}）`).toBeTruthy()
      reservationId = created.id
    } finally {
      if (reservationId) await adminCancel(request, adminToken, reservationId)
      await deleteSlot(request, adminToken, slot.id).catch(() => {})
      await deleteLine(request, adminToken, line.id).catch(() => {})
    }
  })
})
