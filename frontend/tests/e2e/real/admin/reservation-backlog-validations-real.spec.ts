/**
 * F03.4 予約バックログ拡張 — バリデーション・制約系の実機フルスタックE2E
 * （実 Playwright APIRequestContext + 実BE :8080 直叩き）
 *
 * 御下命:
 *   #1680 / #1683 で main 着地した「段階拡張バリデーション」を実機で確証する。
 *   既存 reservation-f034-real.spec.ts §F034-VALIDATION の V-02 / V-03 は
 *   「未実装ゆえ通る（201）」を番人として固定していたが、本バックログ実装で
 *   30分刻み・過去日が 400 で弾かれるようになった。本スペックはその「正挙動」を assert する。
 *
 * 前提:
 *   - BE http://localhost:8080 稼働中（最新 main = バックログ全反映で再ビルド再起動済み・共有資源・再起動禁止）。
 *   - 本スペックは API 主体（ブラウザ UI は使わない）。:8080 を絶対URLで自前ログインし Bearer で自己完結する
 *     （memory: feedback_e2e_wsl2_cors_apibridge / feedback_e2e_real_full_crud）。
 *   - 実行は **--workers=1** 推奨（ログインバースト → AUTH 間欠失敗を避ける。
 *     reservation-f034-real.spec.ts と同じ理由）。
 *   - real/admin/ 配下のため chromium-real-admin プロジェクトで実行される。
 *
 * テストアカウント（seed: backend/scripts/seed-e2e-data.js）:
 *   - ADMIN:  e2e-admin@test.mannschaft.local / TestPass2026!（fc-u-18 ADMIN + SYSTEM_ADMIN）
 *   - MEMBER: e2e-user@test.mannschaft.local  / TestPass2026!（fc-u-18 MEMBER）
 *
 * BE 契約（検証対象・全て main 着地済み）:
 *   - ② 30分刻み: 枠作成で start/end の分が 00/30 以外、または枠長 < 30 分 → 400 RESERVATION_022（INVALID_SLOT_GRANULARITY）
 *   - ③ 過去日:   枠作成で slot_date が（注入 Clock 基準の）当日より前 → 400 RESERVATION_023（PAST_DATE_SLOT）
 *   - ④ 最大5本:  予約ライン作成でチーム合計 5 本超過 → 400 RESERVATION_024（LINE_LIMIT_EXCEEDED）
 *                 display_order が 1〜5 範囲外 → 400 RESERVATION_025（INVALID_DISPLAY_ORDER）
 *   - ⑤ cancel_deadline: 会員（USER）キャンセルで「枠開始 - cancelDeadlineHours」を過ぎていれば → 400 RESERVATION_026（CANCEL_DEADLINE_PASSED）
 *                 期限内は可。ADMIN キャンセルは締切に関係なく常時可。
 *                 設定は PATCH /api/v1/teams/{teamId}/reservation-settings { cancelDeadlineHours }
 *   - ⑧ 重複409: 同一会員が同一枠に重複予約 → 409 RESERVATION_013（DUPLICATE_RESERVATION）
 *
 * 後始末:
 *   作成した枠/ライン/予約は finally で必ず削除/キャンセルする。
 *   cancelDeadlineHours を変更したテストは finally で既定 24 へ復帰させる。
 */

import { test as base, expect, request as playwrightRequest, type APIRequestContext } from '@playwright/test'

const BE = 'http://localhost:8080'
const TEAM_SLUG = 'fc-u-18'

/** F03.4 ⑤ cancel_deadline_hours の既定値（BE: ReservationPolicy 既定 24）。テスト後はこの値へ復帰させる。 */
const DEFAULT_CANCEL_DEADLINE_HOURS = 24

const ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const MEMBER = {
  email: process.env.TEST_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_MEMBER_PASSWORD ?? 'TestPass2026!',
}

/**
 * ワーカースコープで ADMIN / MEMBER の accessToken を一度だけ取得する。
 * 直列ログイン + 指数バックオフは reservation-f034-real.spec.ts と同手法
 * （並列ログインバーストで BE が 429/400/一過性 500 を返すため）。
 */
const test = base.extend<
  Record<string, never>,
  { tokens: { admin: string; member: string } }
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
        const maxAttempts = 6
        let lastErr = ''
        for (let attempt = 1; attempt <= maxAttempts; attempt++) {
          const res = await ctx.post(`${BE}/api/v1/auth/login`, {
            headers: { 'Content-Type': 'application/json' },
            data: { email, password },
          })
          if (res.ok()) return (await res.json()).data.accessToken as string
          const status = res.status()
          lastErr = `${status} ${await res.text()}`
          if (status === 401 || status === 403) break
          await new Promise((r) => setTimeout(r, 500 * attempt + Math.floor(Math.random() * 300)))
        }
        throw new Error(`ログイン失敗(${email}): ${lastErr}`)
      }
      const gap = (): Promise<void> => new Promise((r) => setTimeout(r, 250))
      const admin = await login(ADMIN.email, ADMIN.password)
      await gap()
      const member = await login(MEMBER.email, MEMBER.password)
      await ctx.dispose()
      await use({ admin, member })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(120_000)

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

// ---------------------------------------------------------------------------
// BE 直接ヘルパー（Bearer 認証）
// ---------------------------------------------------------------------------

interface ErrorBody {
  error?: { code?: string; message?: string }
  code?: string
  message?: string
}

/** レスポンス body から error code を抜き出す（ApiResponse のエラー形に依存しすぎないよう両形を見る）。 */
async function errorCode(resp: import('@playwright/test').APIResponse): Promise<string> {
  try {
    const body = (await resp.json()) as ErrorBody
    return body.error?.code ?? body.code ?? ''
  } catch {
    return ''
  }
}

async function createLine(
  request: APIRequestContext,
  token: string,
  body: { name: string; displayOrder?: number },
): Promise<{ id: number }> {
  const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
    headers: authHeaders(token),
    data: body,
  })
  if (!resp.ok()) throw new Error(`createLine 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data
}

async function deleteLine(request: APIRequestContext, token: string, lineId: number): Promise<void> {
  await request
    .delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines/${lineId}`, {
      headers: authHeaders(token),
    })
    .catch(() => {})
}

async function listLines(
  request: APIRequestContext,
  token: string,
): Promise<{ id: number }[]> {
  const resp = await request.get(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
    headers: authHeaders(token),
  })
  if (!resp.ok()) throw new Error(`listLines 失敗: ${resp.status()} ${await resp.text()}`)
  return ((await resp.json()).data ?? []) as { id: number }[]
}

/**
 * 予約に使うラインを 1 本確保する。
 * チームがライン上限（5 本）に達している場合は新規作成できない（RESERVATION_024）ため、
 * その場合は既存ラインを 1 本流用する。{@code created} が true のときだけ呼び出し側が削除する
 * （流用した既存ラインは他スペック資産の可能性があるため削除しない＝データ衛生）。
 *
 * 共有 dev DB には過去 E2E の後始末漏れラインが堆積し得るため、本ヘルパーで上限到達に耐える。
 */
async function getOrCreateLine(
  request: APIRequestContext,
  token: string,
  name: string,
): Promise<{ id: number; created: boolean }> {
  const createResp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
    headers: authHeaders(token),
    data: { name },
  })
  if (createResp.ok()) {
    return { id: (await createResp.json()).data.id as number, created: true }
  }
  const code = await errorCode(createResp)
  if (createResp.status() === 400 && code === 'RESERVATION_024') {
    // 上限到達: 既存ラインを流用する（堆積した後始末漏れラインがあるため）。
    const existing = await listLines(request, token)
    if (existing.length === 0) {
      throw new Error('ライン上限なのに既存ラインが 0 本という不整合（要調査）')
    }
    return { id: existing[0].id, created: false }
  }
  throw new Error(`getOrCreateLine 失敗: ${createResp.status()} ${await createResp.text()}`)
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
  await request
    .delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slotId}`, {
      headers: authHeaders(token),
    })
    .catch(() => {})
}

/** ADMIN 権限で確実にキャンセルして後片付けする（締切に関係なく成立する経路）。 */
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

/** cancelDeadlineHours を設定する（ADMIN 限定 PATCH /reservation-settings）。 */
async function setCancelDeadlineHours(
  request: APIRequestContext,
  token: string,
  hours: number,
): Promise<void> {
  const resp = await request.patch(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-settings`, {
    headers: authHeaders(token),
    data: { cancelDeadlineHours: hours },
  })
  if (!resp.ok()) {
    throw new Error(`cancelDeadlineHours 設定失敗(${hours}): ${resp.status()} ${await resp.text()}`)
  }
}

function futureDate(daysAhead: number): string {
  const d = new Date()
  d.setDate(d.getDate() + daysAhead)
  return d.toISOString().slice(0, 10)
}

/** 30分グリッドに乗った "HH:30" 形式の時刻を返す（枠長 30 分の枠を未来日に作るため）。 */
function hhmm(hour: number, minute: 0 | 30): string {
  return `${String(hour).padStart(2, '0')}:${minute === 0 ? '00' : '30'}`
}

// ===========================================================================
// ② 30分刻み（INVALID_SLOT_GRANULARITY / RESERVATION_022 / 400）
// ===========================================================================

test.describe('RSV-BL-GRANULARITY: 枠作成は30分グリッド + 最小30分を強制する', () => {
  test('RSV-BL-G-01: 分が 15（10:15-10:45）→ 400 RESERVATION_022', async ({ request, tokens }) => {
    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: futureDate(45), startTime: '10:15', endTime: '10:45' },
    })
    const code = await errorCode(resp)
    expect(resp.status(), `10:15 開始は 400 を期待: status=${resp.status()} code=${code}`).toBe(400)
    expect(code).toBe('RESERVATION_022')
    // 念のため: 万一通っていたら片付ける（通常は 400 で id 無し）
    if (resp.ok()) await deleteSlot(request, tokens.admin, (await resp.json()).data.id)
  })

  test('RSV-BL-G-02: 枠長 20 分（10:00-10:20、end が非グリッド）→ 400 RESERVATION_022', async ({
    request,
    tokens,
  }) => {
    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: futureDate(45), startTime: '10:00', endTime: '10:20' },
    })
    const code = await errorCode(resp)
    expect(resp.status(), `10:00-10:20 は 400 を期待: status=${resp.status()} code=${code}`).toBe(400)
    expect(code).toBe('RESERVATION_022')
    if (resp.ok()) await deleteSlot(request, tokens.admin, (await resp.json()).data.id)
  })

  test('RSV-BL-G-03: 30分グリッド（10:00-10:30）→ 201 で成功する', async ({ request, tokens }) => {
    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: futureDate(45), startTime: '10:00', endTime: '10:30' },
    })
    expect(resp.status(), `10:00-10:30 は 201 を期待: ${resp.status()} ${await resp.text()}`).toBe(201)
    const slot = (await resp.json()).data
    expect(slot.id).toBeTruthy()
    await deleteSlot(request, tokens.admin, slot.id)
  })
})

// ===========================================================================
// ③ 過去日（PAST_DATE_SLOT / RESERVATION_023 / 400）
// ===========================================================================

test.describe('RSV-BL-PASTDATE: 過去日には枠を作成できない', () => {
  test('RSV-BL-P-01: 昨日の日付 → 400 RESERVATION_023', async ({ request, tokens }) => {
    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: futureDate(-1), startTime: '10:00', endTime: '10:30' },
    })
    const code = await errorCode(resp)
    expect(resp.status(), `過去日は 400 を期待: status=${resp.status()} code=${code}`).toBe(400)
    expect(code).toBe('RESERVATION_023')
    if (resp.ok()) await deleteSlot(request, tokens.admin, (await resp.json()).data.id)
  })

  test('RSV-BL-P-02: 未来日（+45日）→ 201 で成功する', async ({ request, tokens }) => {
    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: futureDate(45), startTime: '11:00', endTime: '11:30' },
    })
    expect(resp.status(), `未来日は 201 を期待: ${resp.status()} ${await resp.text()}`).toBe(201)
    const slot = (await resp.json()).data
    expect(slot.id).toBeTruthy()
    await deleteSlot(request, tokens.admin, slot.id)
  })
})

// ===========================================================================
// ④ 最大5本（LINE_LIMIT_EXCEEDED / RESERVATION_024 / 400）+ display_order 範囲（RESERVATION_025）
// ===========================================================================

test.describe('RSV-BL-LINELIMIT: 予約ラインはチーム合計 5 本まで', () => {
  test('RSV-BL-L-01: 合計 5 本まで作成でき、6 本目は 400 RESERVATION_024', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const created: number[] = []
    try {
      // 既存ライン数を把握し、5 本まで埋める（他テストの後始末漏れ等で既存があり得るため適応的に）。
      const existing = (await listLines(request, admin)).length
      const toFill = Math.max(0, 5 - existing)
      for (let i = 0; i < toFill; i++) {
        const line = await createLine(request, admin, { name: `BL_L01_fill_${Date.now()}_${i}` })
        created.push(line.id)
      }
      // ここでチーム合計はちょうど 5 本（既存 + 補充）。次の 1 本は上限超過で 400。
      const overResp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
        headers: authHeaders(admin),
        data: { name: `BL_L01_over_${Date.now()}` },
      })
      const code = await errorCode(overResp)
      expect(
        overResp.status(),
        `6 本目は 400 を期待: status=${overResp.status()} code=${code}`,
      ).toBe(400)
      expect(code).toBe('RESERVATION_024')
      // 万一通ってしまった場合は片付ける（退行検知の保険）
      if (overResp.ok()) created.push((await overResp.json()).data.id)
    } finally {
      // 後始末: 本テストで作成したラインを全削除（既存ラインは触らない）
      for (const id of created) await deleteLine(request, admin, id)
    }
  })

  test('RSV-BL-L-02: display_order 範囲外（6）→ 400 RESERVATION_025', async ({ request, tokens }) => {
    const admin = tokens.admin
    // display_order 検証（RESERVATION_025）は BE では count 上限チェック（RESERVATION_024）の後段。
    // よってライン数が 5 本未満でないと 024 に先取りされ 025 を観測できない。
    // 共有 dev DB には過去 E2E の後始末漏れラインが堆積していることがあるため、
    // 上限到達時は「新しい（id が大きい）順に余剰ラインを削除して 4 本まで落とす」ことで
    // 検証可能な状態を決定論的に作る（id の小さい seed 由来ラインは温存。これは
    // 御下命「必要なら先に既存を削除orカウント把握」に沿った正当なテストデータ衛生）。
    const lines = await listLines(request, admin)
    if (lines.length >= 5) {
      const sortedDesc = [...lines].sort((a, b) => b.id - a.id)
      const toRemove = sortedDesc.slice(0, lines.length - 4) // 4 本まで落とす
      for (const l of toRemove) await deleteLine(request, admin, l.id)
    }
    const remaining = (await listLines(request, admin)).length
    expect(remaining, `display_order 検証の前提（4 本以下）が満たせない: ${remaining} 本`).toBeLessThanOrEqual(4)

    const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
      headers: authHeaders(admin),
      data: { name: `BL_L02_${Date.now()}`, displayOrder: 6 },
    })
    const code = await errorCode(resp)
    expect(
      resp.status(),
      `display_order=6 は 400 を期待: status=${resp.status()} code=${code}`,
    ).toBe(400)
    expect(code).toBe('RESERVATION_025')
    // 万一通ってしまった場合は片付ける（退行検知の保険）
    if (resp.ok()) await deleteLine(request, admin, (await resp.json()).data.id)
  })
})

// ===========================================================================
// ⑤ cancel_deadline（CANCEL_DEADLINE_PASSED / RESERVATION_026 / 400）
//   決定論的に締切超過/期限内を作り分ける:
//     - 開始が「+2 時間後」の近接枠を用意（30分グリッド・本日）。
//     - cancelDeadlineHours=24 のとき deadline = 開始 - 24h（過去）→ now は deadline 超過 → 会員キャンセル 400。
//     - cancelDeadlineHours=1 へ変更すると deadline = 開始 - 1h（まだ未来）→ now は期限内 → 会員キャンセル成功。
//     - ADMIN は締切に関係なく常時キャンセル可。
//   ※ 本日近接枠は「過去日チェック(③)」に抵触しない（過去日チェックは slot_date が当日より前のときのみ）。
//     当日 + 開始時刻が現在より後 なら ③ を通過する。
// ===========================================================================

test.describe('RSV-BL-CANCELDEADLINE: 会員キャンセルは締切を実適用する', () => {
  /**
   * 「本日・現在より約2時間後・30分グリッド」の枠を作る。
   * 30分グリッド制約(②)を満たすため、現在時刻を切り上げて 2 時間後の直近 30 分境界に合わせる。
   * もし当日の残り時間が足りず時刻が翌日にあふれる場合は翌日の朝の枠で代替する
   * （その場合でも cancelDeadlineHours=24 なら deadline は概ね過去〜近接で締切超過判定になり得るため、
   *   このケースでは締切超過の決定論性が崩れるので skip 扱いにする）。
   */
  test('RSV-BL-CD-01: 締切超過→会員キャンセル400 / 締切内→会員キャンセル成功 / ADMINは常時可', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const member = tokens.member

    // 現在時刻（JST 実行・playwright.config の timezoneId=Asia/Tokyo）から +2h を 30 分境界へ切り上げ。
    const now = new Date()
    const startMs = now.getTime() + 2 * 60 * 60 * 1000
    const start = new Date(startMs)
    // 30 分境界へ切り上げ
    const m = start.getMinutes()
    if (m === 0 || m === 30) {
      // ちょうどならそのまま（秒以下を 0 に）
    } else if (m < 30) {
      start.setMinutes(30, 0, 0)
    } else {
      start.setHours(start.getHours() + 1, 0, 0, 0)
    }
    start.setSeconds(0, 0)

    // 当日内に収まり、かつ枠開始が現在より十分先（最低 +1h）であることを確認。
    const sameDay =
      start.getFullYear() === now.getFullYear() &&
      start.getMonth() === now.getMonth() &&
      start.getDate() === now.getDate()
    const startsLaterToday = start.getTime() - now.getTime() >= 60 * 60 * 1000
    // 終了が当日 23:30 を超えると翌日にあふれるため、開始が 23:00 までであること。
    const endFitsToday = start.getHours() < 23 || (start.getHours() === 23 && start.getMinutes() === 0)
    test.skip(
      !(sameDay && startsLaterToday && endFitsToday),
      `現在 ${now.toISOString()} では本日近接枠を作れず締切超過を決定論化できないため skip（深夜帯の実行）`,
    )

    const slotDate = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}-${String(start.getDate()).padStart(2, '0')}`
    const startTime = hhmm(start.getHours(), (start.getMinutes() === 0 ? 0 : 30) as 0 | 30)
    const endDate = new Date(start.getTime() + 30 * 60 * 1000)
    const endTime = hhmm(endDate.getHours(), (endDate.getMinutes() === 0 ? 0 : 30) as 0 | 30)

    const line = await getOrCreateLine(request, admin, `BL_CD_${Date.now()}`)
    let slotId: number | null = null
    let memberResId: number | null = null
    let adminResId: number | null = null
    try {
      // 既定 24h であることを保証（前テストの残骸対策）。
      await setCancelDeadlineHours(request, admin, DEFAULT_CANCEL_DEADLINE_HOURS)

      const slot = await createSlot(request, admin, { slotDate, startTime, endTime })
      slotId = slot.id

      // --- (A) 締切超過: cancelDeadlineHours=24・開始は約2h後 → deadline は過去 → 会員キャンセル 400 ---
      const r1 = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
        headers: authHeaders(member),
        data: { reservationSlotId: slot.id, lineId: line.id, userNote: '締切超過テスト' },
      })
      expect(r1.status(), `会員予約(A)作成失敗: ${r1.status()} ${await r1.text()}`).toBe(201)
      memberResId = (await r1.json()).data.id

      const cancelOver = await request.post(
        `${BE}/api/v1/reservations/${memberResId}/cancel`,
        { headers: authHeaders(member), data: { reason: '締切超過のはず' } },
      )
      const overCode = await errorCode(cancelOver)
      expect(
        cancelOver.status(),
        `締切超過の会員キャンセルは 400 を期待: status=${cancelOver.status()} code=${overCode}`,
      ).toBe(400)
      expect(overCode).toBe('RESERVATION_026')

      // --- (B) ADMIN は締切超過でもキャンセルできる（同じ予約を ADMIN がキャンセル）---
      const adminCancelResp = await request.post(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${memberResId}/cancel`,
        { headers: authHeaders(admin), data: { reason: 'ADMIN は締切無視' } },
      )
      expect(
        adminCancelResp.ok(),
        `ADMIN キャンセルは締切超過でも成功を期待: ${adminCancelResp.status()} ${await adminCancelResp.text()}`,
      ).toBe(true)
      expect((await adminCancelResp.json()).data.status.status).toBe('CANCELLED')
      memberResId = null // 既に CANCELLED（terminal）

      // --- (C) 締切内: cancelDeadlineHours=1 → deadline = 開始 - 1h（まだ未来）→ 会員キャンセル成功 ---
      await setCancelDeadlineHours(request, admin, 1)
      const r2 = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
        headers: authHeaders(member),
        data: { reservationSlotId: slot.id, lineId: line.id, userNote: '締切内テスト' },
      })
      expect(r2.status(), `会員予約(C)作成失敗: ${r2.status()} ${await r2.text()}`).toBe(201)
      adminResId = (await r2.json()).data.id // 変数名は便宜上。次行で会員キャンセルする

      const cancelInTime = await request.post(
        `${BE}/api/v1/reservations/${adminResId}/cancel`,
        { headers: authHeaders(member), data: { reason: '締切内なので可' } },
      )
      expect(
        cancelInTime.ok(),
        `締切内(cancelDeadlineHours=1)の会員キャンセルは成功を期待: ${cancelInTime.status()} ${await cancelInTime.text()}`,
      ).toBe(true)
      expect((await cancelInTime.json()).data.status.status).toBe('CANCELLED')
      adminResId = null // 既に CANCELLED（terminal）
    } finally {
      // 後始末: 予約 → cancelDeadlineHours を既定へ復帰 → 枠 → ライン の順。
      if (memberResId) await adminCancel(request, admin, memberResId)
      if (adminResId) await adminCancel(request, admin, adminResId)
      await setCancelDeadlineHours(request, admin, DEFAULT_CANCEL_DEADLINE_HOURS).catch(() => {})
      if (slotId) await deleteSlot(request, admin, slotId)
      if (line.created) await deleteLine(request, admin, line.id)
    }
  })
})

// ===========================================================================
// ⑧ 重複予約 409（DUPLICATE_RESERVATION / RESERVATION_013 / 409）
// ===========================================================================

test.describe('RSV-BL-DUPLICATE: 同一会員の同一枠への重複予約は 409', () => {
  test('RSV-BL-D-01: 会員が同一枠に 2 回予約 → 1 回目 201 / 2 回目 409 RESERVATION_013', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const member = tokens.member
    const line = await getOrCreateLine(request, admin, `BL_D01_${Date.now()}`)
    const slot = await createSlot(request, admin, {
      slotDate: futureDate(46),
      startTime: '13:00',
      endTime: '13:30',
    })
    let reservationId: number | null = null
    try {
      const first = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
        headers: authHeaders(member),
        data: { reservationSlotId: slot.id, lineId: line.id, userNote: '1 回目' },
      })
      expect(first.status(), `1 回目予約失敗: ${first.status()} ${await first.text()}`).toBe(201)
      reservationId = (await first.json()).data.id

      const second = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
        headers: authHeaders(member),
        data: { reservationSlotId: slot.id, lineId: line.id, userNote: '2 回目（重複）' },
      })
      const code = await errorCode(second)
      expect(
        second.status(),
        `2 回目（重複）は 409 を期待: status=${second.status()} code=${code}`,
      ).toBe(409)
      expect(code).toBe('RESERVATION_013')
      // 万一通ってしまった場合は片付ける（退行検知の保険）
      if (second.ok()) await adminCancel(request, admin, (await second.json()).data.id)
    } finally {
      if (reservationId) await adminCancel(request, admin, reservationId)
      await deleteSlot(request, admin, slot.id)
      if (line.created) await deleteLine(request, admin, line.id)
    }
  })
})
