/**
 * F03.4 残ギャップMVP — 承認モード／確定リマインド自動生成 実機フルスタックE2E
 * （実BE :8080・Bearer 認証・API 直叩き）
 *
 * 検証対象（4-F MVP）:
 *   ① AUTO 自動確定: チームポリシー approvalMode=AUTO のとき、会員の予約作成で即 CONFIRMED 着地する
 *      （旧挙動の「常に PENDING」を是正。reservation-f034-real.spec.ts シナリオB の番人コメントが指していた未実装）。
 *   ⑥ CONFIRMED 時リマインド自動生成: 予約 CONFIRMED 時に reservation_reminders へ
 *      remind_at = slot開始 - {24h, 1h} が生成される（過去はスキップ・上限3件。実送信はMVP外）。
 *   設定API: GET/PATCH /api/v1/teams/{teamId}/reservation-settings（PATCH=ADMIN限定）。
 *      approvalMode(AUTO|MANUAL) / cancelDeadlineHours(number) / remindBeforeHours(CSV 例 "24,1")。
 *
 * 重要な前提（テーブル設計に起因する直列実行制約）:
 *   - approvalMode は reservation_policies のチーム単位設定（teamId=1=fc-u-18）。スロット単位値は
 *     本テストでは設定しないため、全テストが同一チームのポリシーを共有する。AUTO/MANUAL を切り替える
 *     テストが並走するとレースになるため **describe.serial** + **--workers=1** で直列化する。
 *   - afterAll で必ず approvalMode=AUTO（既定）へ復帰させる。
 *
 * BE 仕様の根拠（実装読み取り済み）:
 *   - ReservationService.createReservation: resolveApprovalMode(team, slot) が AUTO のとき
 *     saved.confirm() → publishConfirmedEvent。MANUAL は PENDING のまま confirmReservation 待ち。
 *   - ReservationReminderEventListener: @TransactionalEventListener(AFTER_COMMIT)+@Transactional(REQUIRES_NEW)。
 *     remind_before_hours CSV をパースし remindAt=slotStartAt.minusHours(h) のうち未来のものだけ生成。
 *   - リマインド一覧 API: GET /api/v1/teams/{teamId}/reservations/{reservationId}/reminders。
 *     これがあるため DB 直接照会は不要（API で remind_at 実値を検証する）。
 *
 * ログイン作法は reservation-f034-real.spec.ts に倣う（worker スコープで直列＋指数バックオフ。
 * 並列ログインバーストで BE が一過性 5xx/429 を返すのを避ける）。
 *
 * テストアカウント（seed: backend/scripts/seed-e2e-data.js・全て TestPass2026!）:
 *   - ADMIN:  e2e-admin@test.mannschaft.local（fc-u-18 ADMIN + SYSTEM_ADMIN）
 *   - MEMBER: e2e-user@test.mannschaft.local（fc-u-18 MEMBER）
 */

import { test as base, expect, request as playwrightRequest, type APIRequestContext } from '@playwright/test'

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

/** worker スコープで ADMIN / MEMBER の accessToken を一度だけ取得する。 */
const test = base.extend<
  { storageStateOff: boolean },
  { tokens: { admin: string; member: string } }
>({
  // 本スペックは Bearer 認証で自己完結するため storageState（.auth/*.json）に依存しない。
  // 複数プロジェクト（chromium / chromium-admin など）が auth ファイルを要求するのを無効化する。
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
      const admin = await login(ADMIN.email, ADMIN.password)
      await new Promise((r) => setTimeout(r, 250))
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
// BE 直接ヘルパー
// ---------------------------------------------------------------------------

interface SettingsResponse {
  teamId: number
  hasBusinessHours: boolean
  allowPublicReservation: boolean
  approvalMode: 'AUTO' | 'MANUAL'
  cancelDeadlineHours: number
  remindBeforeHours: string
}

interface ReminderResponse {
  id: number
  reservationId: number
  remindAt: string
  status: string
  sentAt: string | null
  createdAt: string
}

interface CreatedReservation {
  id: number
  status: { status: string; confirmedAt: string | null }
  identifier: { reservationSlotId: number; lineId: number }
}

/** 予約作成レスポンスを型付きで取り出す。 */
async function postReservation(
  request: APIRequestContext,
  token: string,
  body: { reservationSlotId: number; lineId: number; userNote?: string },
): Promise<{ status: number; created: CreatedReservation; raw: string }> {
  const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
    headers: authHeaders(token),
    data: body,
  })
  const raw = await resp.text()
  const status = resp.status()
  const created = status === 201 ? (JSON.parse(raw).data as CreatedReservation) : ({} as CreatedReservation)
  return { status, created, raw }
}

async function getSettings(request: APIRequestContext, token: string): Promise<SettingsResponse> {
  const resp = await request.get(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-settings`, {
    headers: authHeaders(token),
  })
  if (!resp.ok()) throw new Error(`getSettings 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data as SettingsResponse
}

async function patchApprovalMode(
  request: APIRequestContext,
  token: string,
  mode: 'AUTO' | 'MANUAL',
): Promise<SettingsResponse> {
  const resp = await request.patch(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-settings`, {
    headers: authHeaders(token),
    data: { approvalMode: mode },
  })
  if (!resp.ok()) throw new Error(`patchApprovalMode(${mode}) 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data as SettingsResponse
}

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
  await request
    .delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines/${lineId}`, { headers: authHeaders(token) })
    .catch(() => {})
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
    .delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slotId}`, { headers: authHeaders(token) })
    .catch(() => {})
}

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

async function listReminders(
  request: APIRequestContext,
  token: string,
  reservationId: number,
): Promise<ReminderResponse[]> {
  const resp = await request.get(
    `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/reminders`,
    { headers: authHeaders(token) },
  )
  expect(resp.status(), `reminders GET 失敗: ${resp.status()} ${await resp.text()}`).toBe(200)
  return ((await resp.json()).data ?? []) as ReminderResponse[]
}

function futureDate(daysAhead: number): string {
  const d = new Date()
  d.setDate(d.getDate() + daysAhead)
  return d.toISOString().slice(0, 10)
}

/**
 * リマインドの remind_at を「年-月-日T時:分」の分解能で比較するためのキーへ正規化する。
 * BE は JST(+09:00) のオフセット付き ISO を返す（例 "2026-06-21T10:00:00+09:00"）。
 * 検証は壁時計（slotDate/startTime と同じ JST 表現）で行うため、オフセットは切り落とす。
 */
function ymdHm(iso: string): string {
  return iso.slice(0, 16) // "YYYY-MM-DDTHH:mm"
}

/** slotDate(YYYY-MM-DD) + startTime(HH:mm) から h 時間前の壁時計キー("YYYY-MM-DDTHH:mm")を作る。 */
function expectedRemindKey(slotDate: string, startTime: string, hoursBefore: number): string {
  // JST 壁時計のまま計算する（UTC 変換を挟まないよう Z を付けない素朴な Date 構築は避け、手計算する）。
  const dateParts = slotDate.split('-').map(Number)
  const timeParts = startTime.split(':').map(Number)
  const y = dateParts[0] ?? 0
  const mo = dateParts[1] ?? 1
  const d = dateParts[2] ?? 1
  const hh = timeParts[0] ?? 0
  const mm = timeParts[1] ?? 0
  // ローカルタイムゾーン非依存にするため UTC ベースで分単位演算し、結果を UTC 壁時計として読む
  // （入出力ともに同じ規約で扱えば相対計算は正しい）。
  const baseUtc = Date.UTC(y, mo - 1, d, hh, mm)
  const remind = new Date(baseUtc - hoursBefore * 60 * 60 * 1000)
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${remind.getUTCFullYear()}-${pad(remind.getUTCMonth() + 1)}-${pad(remind.getUTCDate())}T${pad(remind.getUTCHours())}:${pad(remind.getUTCMinutes())}`
}

// ===========================================================================
// 直列実行（チーム単位ポリシーを共有するため）。--workers=1 と併用すること。
// ===========================================================================
test.describe.serial('RSV-POLICY-MVP: 承認モード／確定リマインド自動生成', () => {
  // 各テストが万一中断しても次テストへ汚染を残さないよう、describe 終了時に AUTO へ復帰する。
  test.afterAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    await patchApprovalMode(ctx, tokens.admin, 'AUTO').catch(() => {})
    await ctx.dispose()
  })

  // -------------------------------------------------------------------------
  // シナリオ1: 設定API GET（既定値）
  // -------------------------------------------------------------------------
  test('RSV-POLICY-01: GET reservation-settings が既定ポリシー(AUTO/24/"24,1")を返す', async ({
    request,
    tokens,
  }) => {
    // 前提を AUTO（既定）に揃える。
    await patchApprovalMode(request, tokens.admin, 'AUTO')
    const s = await getSettings(request, tokens.admin)
    expect(s.teamId).toBe(1)
    expect(s.approvalMode).toBe('AUTO')
    expect(s.cancelDeadlineHours).toBe(24)
    expect(s.remindBeforeHours).toBe('24,1')
  })

  // -------------------------------------------------------------------------
  // シナリオ2+3: AUTO 自動確定 → 確定リマインド2件自動生成
  // -------------------------------------------------------------------------
  test('RSV-POLICY-02: AUTO で会員予約が即 CONFIRMED 着地し、リマインドが slot-24h/-1h で2件生成される', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    await patchApprovalMode(request, admin, 'AUTO')

    const slotDate = futureDate(3) // 十分未来。24h/1h 前とも未来になる。
    const startTime = '10:00'
    const line = await createLine(request, admin, `POLICY_AUTO_${Date.now()}`)
    const slot = await createSlot(request, admin, { slotDate, startTime, endTime: '10:30' })
    let reservationId: number | null = null
    try {
      const { status, created, raw } = await postReservation(request, tokens.member, {
        reservationSlotId: slot.id,
        lineId: line.id,
        userNote: 'AUTO 自動確定検証',
      })
      expect(status, `予約作成失敗: ${status} ${raw}`).toBe(201)
      reservationId = created.id

      // ① AUTO 自動確定: PENDING を経ず即 CONFIRMED。confirmedAt が打たれている。
      expect(created.status.status, 'AUTO なのに即 CONFIRMED でない').toBe('CONFIRMED')
      expect(created.status.confirmedAt, 'confirmedAt が空').toBeTruthy()

      // ⑥ 確定リマインド自動生成: AFTER_COMMIT 非同期のため少し待ってから照会する。
      let reminders: ReminderResponse[] = []
      for (let i = 0; i < 10; i++) {
        reminders = await listReminders(request, admin, created.id)
        if (reminders.length >= 2) break
        await new Promise((r) => setTimeout(r, 500))
      }
      expect(reminders.length, `リマインドは2件のはず（slot-24h, slot-1h）。実際: ${JSON.stringify(reminders)}`).toBe(2)

      const keys = reminders.map((r) => ymdHm(r.remindAt)).sort()
      const expected = [
        expectedRemindKey(slotDate, startTime, 24),
        expectedRemindKey(slotDate, startTime, 1),
      ].sort()
      expect(keys, `remind_at が slot開始-24h/-1h と一致しない`).toEqual(expected)
      // 生成直後は全て PENDING。
      for (const r of reminders) expect(r.status).toBe('PENDING')
    } finally {
      if (reservationId) await adminCancel(request, admin, reservationId)
      await deleteSlot(request, admin, slot.id)
      await deleteLine(request, admin, line.id)
    }
  })

  // -------------------------------------------------------------------------
  // シナリオ4: MANUAL 承認制 → PENDING 着地 → ADMIN confirm → CONFIRMED + リマインド生成
  // -------------------------------------------------------------------------
  test('RSV-POLICY-03: MANUAL で会員予約は PENDING 着地（自動確定しない）→ ADMIN confirm で CONFIRMED + リマインド2件', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const updated = await patchApprovalMode(request, admin, 'MANUAL')
    expect(updated.approvalMode).toBe('MANUAL')

    const slotDate = futureDate(4)
    const startTime = '11:00'
    const line = await createLine(request, admin, `POLICY_MANUAL_${Date.now()}`)
    const slot = await createSlot(request, admin, { slotDate, startTime, endTime: '11:30' })
    let reservationId: number | null = null
    try {
      const { status, created } = await postReservation(request, tokens.member, {
        reservationSlotId: slot.id,
        lineId: line.id,
      })
      expect(status).toBe(201)
      reservationId = created.id

      // MANUAL: 自動確定しない＝ PENDING 着地。confirmedAt は空。
      expect(created.status.status, 'MANUAL なのに自動確定してしまった').toBe('PENDING')
      expect(created.status.confirmedAt).toBeFalsy()

      // confirm 前はリマインド0件（確定経路でのみ生成されるため）。
      const beforeConfirm = await listReminders(request, admin, created.id)
      expect(beforeConfirm.length, '未確定なのにリマインドが生成されている').toBe(0)

      // ADMIN 手動承認 → CONFIRMED
      const confirmResp = await request.post(
        `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${created.id}/confirm`,
        { headers: authHeaders(admin) },
      )
      expect(confirmResp.ok(), `confirm 失敗: ${confirmResp.status()} ${await confirmResp.text()}`).toBe(true)
      expect((await confirmResp.json()).data.status.status).toBe('CONFIRMED')

      // 手動承認時もリマインド2件生成（設計 §3。手動承認も確定経路）。
      let reminders: ReminderResponse[] = []
      for (let i = 0; i < 10; i++) {
        reminders = await listReminders(request, admin, created.id)
        if (reminders.length >= 2) break
        await new Promise((r) => setTimeout(r, 500))
      }
      expect(reminders.length, `手動承認後のリマインドは2件のはず。実際: ${JSON.stringify(reminders)}`).toBe(2)
      const keys = reminders.map((r) => ymdHm(r.remindAt)).sort()
      const expected = [
        expectedRemindKey(slotDate, startTime, 24),
        expectedRemindKey(slotDate, startTime, 1),
      ].sort()
      expect(keys).toEqual(expected)
    } finally {
      if (reservationId) await adminCancel(request, admin, reservationId)
      await deleteSlot(request, admin, slot.id)
      await deleteLine(request, admin, line.id)
      // 次テスト・他スペックのため AUTO へ復帰。
      await patchApprovalMode(request, admin, 'AUTO').catch(() => {})
    }
  })

  // -------------------------------------------------------------------------
  // シナリオ5: 過去スキップ（直近スロットでは 24h 前が過去になるためスキップ、1h 前のみ生成）
  // -------------------------------------------------------------------------
  test('RSV-POLICY-04: 直近(2h後)スロットの AUTO 確定では 24h 前リマインドが過去スキップされ 1件のみ生成', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    await patchApprovalMode(request, admin, 'AUTO')

    // 今から約2時間後のスロットを作る。slot-1h は未来、slot-24h は過去になる想定。
    // 30分丸めの境界やテスト実行の遅延で「ちょうど境界」を踏まないよう 2 時間以上先を選ぶ。
    const now = new Date()
    const start = new Date(now.getTime() + 2 * 60 * 60 * 1000)
    // 分は 00 か 30 へ切り下げ（BE は 30分単位を要求しないが、枠表現を素直にする）。
    start.setMinutes(start.getMinutes() < 30 ? 0 : 30, 0, 0)
    const end = new Date(start.getTime() + 30 * 60 * 1000)
    const pad = (n: number): string => String(n).padStart(2, '0')
    const slotDate = `${start.getFullYear()}-${pad(start.getMonth() + 1)}-${pad(start.getDate())}`
    const startTime = `${pad(start.getHours())}:${pad(start.getMinutes())}`
    const endTime = `${pad(end.getHours())}:${pad(end.getMinutes())}`

    const line = await createLine(request, admin, `POLICY_PAST_${Date.now()}`)
    const slot = await createSlot(request, admin, { slotDate, startTime, endTime })
    let reservationId: number | null = null
    try {
      const { status, created } = await postReservation(request, tokens.member, {
        reservationSlotId: slot.id,
        lineId: line.id,
      })
      expect(status).toBe(201)
      reservationId = created.id
      expect(created.status.status).toBe('CONFIRMED')

      let reminders: ReminderResponse[] = []
      for (let i = 0; i < 8; i++) {
        reminders = await listReminders(request, admin, created.id)
        if (reminders.length >= 1) break
        await new Promise((r) => setTimeout(r, 500))
      }
      // slot-24h は過去のためスキップ。slot-1h（=約1時間後）のみ未来で生成される。
      expect(
        reminders.length,
        `2h後スロットでは 1h 前のみ生成され1件のはず。実際: ${JSON.stringify(reminders)}`,
      ).toBe(1)
      const only = reminders[0]
      expect(only, 'リマインドが生成されていない').toBeTruthy()
      expect(ymdHm(only!.remindAt)).toBe(expectedRemindKey(slotDate, startTime, 1))
    } finally {
      if (reservationId) await adminCancel(request, admin, reservationId)
      await deleteSlot(request, admin, slot.id)
      await deleteLine(request, admin, line.id)
    }
  })
})
