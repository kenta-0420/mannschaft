/**
 * F03.4 枠（slot）単位の承認モード上書き 実機フルスタックE2E
 * （実BE :8080・Bearer 認証・API 直叩き / マスター御裁可②「チーム既定＋枠で上書き」の実機確証）
 *
 * 検証対象（#1660 SET API）:
 *   - 枠作成 POST /api/v1/teams/{teamId}/reservation-slots（CreateSlotRequest.approvalMode? = "AUTO"|"MANUAL"。
 *     省略=NULL=チーム既定継承）。
 *   - 枠編集 PATCH .../reservation-slots/{slotId}（approvalMode? と clearApprovalMode?: boolean。
 *     clearApprovalMode=true で上書き解除→継承に戻す）。
 *   - 枠取得 GET .../reservation-slots/{slotId} の policy.approvalMode（nullable）で現在値確認。
 *   - 承認モード解決（ReservationPolicyService.resolveApprovalMode）:
 *     「枠値あればそれ→無ければチーム設定→無ければAUTO」。AUTO=予約即CONFIRMED / MANUAL=PENDING。
 *
 * シナリオ:
 *   1. チームAUTO・枠MANUAL上書き: 枠B(継承)→CONFIRMED / 枠A(MANUAL上書き)→PENDING（御裁可②の核心）。
 *   2. 上書き解除: 枠A を clearApprovalMode=true → policy.approvalMode=null → 新規予約→CONFIRMED（継承→AUTO）。
 *   3. チームMANUAL・枠AUTO上書き: 枠C(AUTO上書き)→CONFIRMED（枠がチームMANUALに勝つ）/ 枠D(継承)→PENDING。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 【実機E2Eで発見した既知バグ → 根治済み（#1674・2026-06-19）】
 *   かつて `ReservationSlotService.updateSlot` の **PATCH 更新がDBに永続化されない**バグがあった。
 *   実装が `slotRepository.save(entity.toBuilder().build())` で「管理下エンティティの
 *   detached コピー」を保存していたため、PATCH レスポンスは新値を返すのに DB は旧値のままだった
 *   （PATCH approvalMode/clearApprovalMode/title いずれも resp=新値 だが GET=旧値）。
 *   - 根治: closeSlot 同様、managed entity を in-place mutate して save する形に揃えた（#1674）。
 *   - RSV-SLOT-02（clearApprovalMode→GETで null 永続化→新規予約CONFIRMED）はこのバグを暴く
 *     検証だったため一時 test.fixme 化していたが、根治・main 着地・稼働BE反映を確認し fixme を解除。
 *     アサーションは当時の正しい契約（PATCH→GET で反映 / policy.approvalMode=null / 新規予約CONFIRMED）
 *     のまま厳格に検証する。
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 重要な前提（テーブル設計に起因する直列実行制約）:
 *   - approvalMode はチーム単位設定（teamId=1=fc-u-18）を枠単位値が上書きする構造。AUTO/MANUAL を切り替える
 *     テストが並走するとチーム設定がレースになるため **describe.serial** + **--workers=1** で直列化する。
 *   - afterAll で必ずチーム approvalMode=AUTO（既定）へ復帰させる。
 *
 * ログイン作法は reservation-policy-mvp-real.spec.ts に倣う（worker スコープで直列＋指数バックオフ）。
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
  // Bearer 認証で自己完結するため storageState（.auth/*.json）に依存しない。
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

type ApprovalMode = 'AUTO' | 'MANUAL'

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

// ---------------------------------------------------------------------------
// BE 直接ヘルパー
// ---------------------------------------------------------------------------

interface SettingsResponse {
  teamId: number
  approvalMode: ApprovalMode
  cancelDeadlineHours: number
  remindBeforeHours: string
}

interface SlotResponse {
  id: number
  teamId: number
  policy: { approvalMode: string | null } | null
}

interface CreatedReservation {
  id: number
  status: { status: string; confirmedAt: string | null }
  identifier: { reservationSlotId: number; lineId: number }
}

async function getSettings(request: APIRequestContext, token: string): Promise<SettingsResponse> {
  const resp = await request.get(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-settings`, {
    headers: authHeaders(token),
  })
  if (!resp.ok()) throw new Error(`getSettings 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data as SettingsResponse
}

async function patchTeamApprovalMode(
  request: APIRequestContext,
  token: string,
  mode: ApprovalMode,
): Promise<SettingsResponse> {
  const resp = await request.patch(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-settings`, {
    headers: authHeaders(token),
    data: { approvalMode: mode },
  })
  if (!resp.ok()) throw new Error(`patchTeamApprovalMode(${mode}) 失敗: ${resp.status()} ${await resp.text()}`)
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

/** 枠を作成する。approvalMode を渡すと枠単位上書き、省略するとチーム既定継承（NULL）。 */
async function createSlot(
  request: APIRequestContext,
  token: string,
  body: { slotDate: string; startTime: string; endTime: string; approvalMode?: ApprovalMode },
): Promise<SlotResponse> {
  const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
    headers: authHeaders(token),
    data: body,
  })
  if (!resp.ok()) throw new Error(`createSlot 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data as SlotResponse
}

/** 枠を PATCH する。approvalMode で上書き、clearApprovalMode=true で上書き解除（継承へ戻す）。 */
async function patchSlot(
  request: APIRequestContext,
  token: string,
  slotId: number,
  body: { approvalMode?: ApprovalMode; clearApprovalMode?: boolean },
): Promise<SlotResponse> {
  const resp = await request.patch(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slotId}`, {
    headers: authHeaders(token),
    data: body,
  })
  if (!resp.ok()) throw new Error(`patchSlot 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data as SlotResponse
}

/** 枠を GET して policy.approvalMode の現在値（nullable）を確認する。 */
async function getSlot(
  request: APIRequestContext,
  token: string,
  slotId: number,
): Promise<SlotResponse> {
  const resp = await request.get(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slotId}`, {
    headers: authHeaders(token),
  })
  if (!resp.ok()) throw new Error(`getSlot 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data as SlotResponse
}

async function deleteSlot(request: APIRequestContext, token: string, slotId: number): Promise<void> {
  await request
    .delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slotId}`, { headers: authHeaders(token) })
    .catch(() => {})
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
// 直列実行（チーム単位ポリシーを共有するため）。--workers=1 と併用すること。
// ===========================================================================
test.describe.serial('RSV-SLOT-OVERRIDE: 枠単位の承認モード上書き', () => {
  // 各テストが万一中断しても次テストへ汚染を残さないよう、describe 終了時に AUTO へ復帰する。
  test.afterAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    await patchTeamApprovalMode(ctx, tokens.admin, 'AUTO').catch(() => {})
    await ctx.dispose()
  })

  // -------------------------------------------------------------------------
  // シナリオ1: チームAUTO・枠MANUAL上書き（御裁可②の核心）
  //   枠B(継承)→CONFIRMED / 枠A(MANUAL上書き)→PENDING
  // -------------------------------------------------------------------------
  test('RSV-SLOT-01: チームAUTO下で、枠MANUAL上書きはPENDING・枠継承はCONFIRMED', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const teamSettings = await patchTeamApprovalMode(request, admin, 'AUTO')
    expect(teamSettings.approvalMode, 'チーム設定 AUTO への切替に失敗').toBe('AUTO')

    const slotDate = futureDate(5)
    const line = await createLine(request, admin, `SLOT_OVR_S1_${Date.now()}`)
    // 枠A = MANUAL 上書き / 枠B = approvalMode 省略（継承）
    const slotA = await createSlot(request, admin, {
      slotDate,
      startTime: '10:00',
      endTime: '10:30',
      approvalMode: 'MANUAL',
    })
    const slotB = await createSlot(request, admin, { slotDate, startTime: '11:00', endTime: '11:30' })

    let resB: number | null = null
    let resA: number | null = null
    try {
      // 枠の現在値を GET で確証（A=MANUAL 保存・B=null 継承）。
      const gotA = await getSlot(request, admin, slotA.id)
      const gotB = await getSlot(request, admin, slotB.id)
      expect(gotA.policy?.approvalMode, '枠Aの上書き値が保存されていない').toBe('MANUAL')
      expect(gotB.policy?.approvalMode ?? null, '枠B（継承）は policy.approvalMode=null のはず').toBeNull()

      // 枠B（継承→チームAUTO）→ 即 CONFIRMED
      const rB = await postReservation(request, tokens.member, {
        reservationSlotId: slotB.id,
        lineId: line.id,
        userNote: '継承枠Bの自動確定検証',
      })
      expect(rB.status, `枠B予約作成失敗: ${rB.status} ${rB.raw}`).toBe(201)
      resB = rB.created.id
      expect(rB.created.status.status, '枠B(継承→チームAUTO)なのにCONFIRMEDでない').toBe('CONFIRMED')
      expect(rB.created.status.confirmedAt, '枠B CONFIRMED なのに confirmedAt が空').toBeTruthy()

      // 枠A（MANUAL上書き）→ PENDING（御裁可②の核心）
      const rA = await postReservation(request, tokens.member, {
        reservationSlotId: slotA.id,
        lineId: line.id,
        userNote: 'MANUAL上書き枠Aの手動承認検証',
      })
      expect(rA.status, `枠A予約作成失敗: ${rA.status} ${rA.raw}`).toBe(201)
      resA = rA.created.id
      expect(
        rA.created.status.status,
        'チームAUTOでも枠MANUAL上書きならPENDINGのはず（枠上書きが効いていない）',
      ).toBe('PENDING')
      expect(rA.created.status.confirmedAt, '枠A PENDING なのに confirmedAt が打たれている').toBeFalsy()
    } finally {
      if (resA) await adminCancel(request, admin, resA)
      if (resB) await adminCancel(request, admin, resB)
      await deleteSlot(request, admin, slotA.id)
      await deleteSlot(request, admin, slotB.id)
      await deleteLine(request, admin, line.id)
    }
  })

  // -------------------------------------------------------------------------
  // シナリオ2: 上書き解除（clearApprovalMode=true で継承に戻す）
  //   枠 MANUAL → clear → policy.approvalMode=null → 新規予約 CONFIRMED（継承→チームAUTO）
  // -------------------------------------------------------------------------
  // 【根治済み・#1674】updateSlot(PATCH) の未永続化バグ（ヘッダー記載）は in-place 永続化に
  // 是正され main 着地・稼働BEへ反映済み。fixme を外し本来の厳格 assert で緑を確認する。
  test('RSV-SLOT-02: clearApprovalMode=true で上書き解除→policy=null→新規予約がCONFIRMED', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    await patchTeamApprovalMode(request, admin, 'AUTO')

    const slotDate = futureDate(6)
    const line = await createLine(request, admin, `SLOT_OVR_S2_${Date.now()}`)
    const slot = await createSlot(request, admin, {
      slotDate,
      startTime: '10:00',
      endTime: '10:30',
      approvalMode: 'MANUAL',
    })
    let res: number | null = null
    try {
      // 上書き前の確認: MANUAL 保存
      const before = await getSlot(request, admin, slot.id)
      expect(before.policy?.approvalMode, '上書き解除前は MANUAL のはず').toBe('MANUAL')

      // 上書き解除
      const cleared = await patchSlot(request, admin, slot.id, { clearApprovalMode: true })
      expect(
        cleared.policy?.approvalMode ?? null,
        'clearApprovalMode 後 PATCH レスポンスの policy.approvalMode が null でない',
      ).toBeNull()

      // GET で再確認（永続化されている）
      const after = await getSlot(request, admin, slot.id)
      expect(after.policy?.approvalMode ?? null, 'GET でも policy.approvalMode=null のはず').toBeNull()

      // 解除後の新規予約 → 継承→チームAUTO で CONFIRMED
      const r = await postReservation(request, tokens.member, {
        reservationSlotId: slot.id,
        lineId: line.id,
        userNote: '上書き解除後の継承確定検証',
      })
      expect(r.status, `解除後予約作成失敗: ${r.status} ${r.raw}`).toBe(201)
      res = r.created.id
      expect(r.created.status.status, '上書き解除→継承→チームAUTO なのに CONFIRMED でない').toBe('CONFIRMED')
      expect(r.created.status.confirmedAt, 'CONFIRMED なのに confirmedAt が空').toBeTruthy()
    } finally {
      if (res) await adminCancel(request, admin, res)
      await deleteSlot(request, admin, slot.id)
      await deleteLine(request, admin, line.id)
    }
  })

  // -------------------------------------------------------------------------
  // シナリオ3: チームMANUAL・枠AUTO上書き
  //   枠C(AUTO上書き)→CONFIRMED（枠がチームMANUALに勝つ）/ 枠D(継承)→PENDING（チームMANUAL）
  // -------------------------------------------------------------------------
  test('RSV-SLOT-03: チームMANUAL下で、枠AUTO上書きはCONFIRMED・枠継承はPENDING', async ({
    request,
    tokens,
  }) => {
    const admin = tokens.admin
    const teamSettings = await patchTeamApprovalMode(request, admin, 'MANUAL')
    expect(teamSettings.approvalMode, 'チーム設定 MANUAL への切替に失敗').toBe('MANUAL')

    const slotDate = futureDate(7)
    const line = await createLine(request, admin, `SLOT_OVR_S3_${Date.now()}`)
    // 枠C = AUTO 上書き / 枠D = 継承
    const slotC = await createSlot(request, admin, {
      slotDate,
      startTime: '10:00',
      endTime: '10:30',
      approvalMode: 'AUTO',
    })
    const slotD = await createSlot(request, admin, { slotDate, startTime: '11:00', endTime: '11:30' })

    let resC: number | null = null
    let resD: number | null = null
    try {
      const gotC = await getSlot(request, admin, slotC.id)
      const gotD = await getSlot(request, admin, slotD.id)
      expect(gotC.policy?.approvalMode, '枠Cの上書き値(AUTO)が保存されていない').toBe('AUTO')
      expect(gotD.policy?.approvalMode ?? null, '枠D（継承）は policy.approvalMode=null のはず').toBeNull()

      // 枠C（AUTO上書き）→ チームMANUAL に勝って CONFIRMED
      const rC = await postReservation(request, tokens.member, {
        reservationSlotId: slotC.id,
        lineId: line.id,
        userNote: 'AUTO上書き枠Cの自動確定検証',
      })
      expect(rC.status, `枠C予約作成失敗: ${rC.status} ${rC.raw}`).toBe(201)
      resC = rC.created.id
      expect(
        rC.created.status.status,
        'チームMANUALでも枠AUTO上書きならCONFIRMEDのはず（枠上書きがチームに勝てていない）',
      ).toBe('CONFIRMED')
      expect(rC.created.status.confirmedAt, '枠C CONFIRMED なのに confirmedAt が空').toBeTruthy()

      // 枠D（継承→チームMANUAL）→ PENDING
      const rD = await postReservation(request, tokens.member, {
        reservationSlotId: slotD.id,
        lineId: line.id,
        userNote: '継承枠Dの手動承認検証',
      })
      expect(rD.status, `枠D予約作成失敗: ${rD.status} ${rD.raw}`).toBe(201)
      resD = rD.created.id
      expect(rD.created.status.status, '枠D(継承→チームMANUAL)なのにPENDINGでない').toBe('PENDING')
      expect(rD.created.status.confirmedAt, '枠D PENDING なのに confirmedAt が打たれている').toBeFalsy()
    } finally {
      if (resC) await adminCancel(request, admin, resC)
      if (resD) await adminCancel(request, admin, resD)
      await deleteSlot(request, admin, slotC.id)
      await deleteSlot(request, admin, slotD.id)
      await deleteLine(request, admin, line.id)
      // 次テスト・他スペックのため AUTO へ復帰。
      await patchTeamApprovalMode(request, admin, 'AUTO').catch(() => {})
    }
  })

  // -------------------------------------------------------------------------
  // シナリオ4(締め): チーム設定が AUTO（既定）へ復帰していることを確認
  // -------------------------------------------------------------------------
  test('RSV-SLOT-04: 検証後にチーム設定が既定AUTOへ復帰している', async ({ request, tokens }) => {
    const s = await getSettings(request, tokens.admin)
    expect(s.approvalMode, '検証後のチーム設定が AUTO（既定）に戻っていない').toBe('AUTO')
  })
})
