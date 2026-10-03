/**
 * F03.4 予約 + 緊急休業 — メンバー視点の実機フルスタック E2E（御下命 4 条件）
 *
 * ■ 位置づけ
 *   real/ 配下の「実機（モックなし）」回帰資産。CI スモークの対象外
 *   （project_real_admin_e2e_excluded_from_ci_smoke）で、手動実走で 4 条件の一気通貫を裏取りする。
 *   実 BE(:8080) を使い、API ブリッジ（feedback_e2e_wsl2_cors_apibridge）で
 *   ブラウザの /api/v1 呼び出しを 127.0.0.1:8080 へ Bearer 付きで中継する（モックではなく実 BE 中継）。
 *   認証は「注入 Bearer（page.route 中継）＋ localStorage currentUser」で成立させ、
 *   storageState は使わない（複数ユーザーを spec 内で切り替えるため）。
 *
 * ■ 検証する 4 条件（team-000092 / teamId=92）
 *   条件1: MEMBER1 が UI で予約を作成でき、実際に成立する（reservations 行）。
 *   条件2: その予約が MEMBER1 本人の個人「マイ予約」(/my/reservations) に反映される。
 *   条件3: MEMBER2 の「予約一覧」タブ（非管理者=mine）に MEMBER1 の予約が出ない・予約者氏名列が無い（#2097）。
 *   条件4: ADMIN 緊急休業送信 → MEMBER1 の通知に「休業を確認」ボタン → 押下 → 確認済み（confirmations 行 confirmed）。
 *
 * ■ 実行方法（例）
 *   検証用 worktree で FE を :3001 起動（本陣 :3000/:8080 と衝突させない）:
 *     cd frontend && npm run dev -- --port 3001
 *   実行:
 *     cd frontend && BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *       npx playwright test tests/e2e/real/admin/reservation-f034-member-emergency-real.spec.ts \
 *       --project=chromium-real-admin --workers=1
 *   ※ 本陣 FE(:3000) を使う場合は BASE_URL=http://localhost:3000。BE の CORS 許可 origin は
 *     :3000/:8080 のみだが、本 spec は API ブリッジで中継するため FE ポートに依存しない。
 *
 * ■ 前提条件（実行前に整っている必要がある・本 spec は作成しない）
 *   1) 稼働 BE が予約認可ゲート反映版（#2099 以降。MEMBER の管理 API が 403 になること）。
 *   2) team-000092（数値 id=92）で予約モジュールが有効。予約対象(ライン)が最低 1 本存在する
 *      （本 spec は既存アクティブラインを再利用する。無い場合は ADMIN で 1 本作成しておく）。
 *   3) 下記アカウントが seed 済みでログイン可能（パスワード TestPass2026!）:
 *        - e2e-admin  : SYSTEM_ADMIN。isScopeAdmin がシステム管理者を許可するため team92 管理者として作用する。
 *        - e2e-user   : team92 の MEMBER。user_roles(MEMBER) と memberships(role_kind=MEMBER) の両系統に登録されていること
 *                       （project_role_effective_resolution_dual_source）。
 *        - e2e-dummy-6: 第 2 の一般 MEMBER。同上、両系統に team92 MEMBER として登録されていること。
 *   ※ 下記のメール定数はハードコード（seed 依存）。数値 ID(23/8) はアサーション文言・可読性のための注記であり、
 *     判定は実行時に /users/me から取得した id を用いる。seed のユーザー構成が変わったらメール定数を更新すること。
 */
import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const TEAM_SLUG = 'team-000092'
const TEAM_ID = 92
const ADMIN_EMAIL = 'e2e-admin@test.mannschaft.local'
const MEMBER1_EMAIL = 'e2e-user@test.mannschaft.local'   // userId 23
const MEMBER2_EMAIL = 'e2e-dummy-6@test.mannschaft.local' // userId 8

interface Me { id: number; email: string; lastName: string; firstName: string; avatarUrl: string | null; systemRole: string | null; timezone: string | null }

async function login(ctx: APIRequestContext, email: string): Promise<string> {
  const res = await ctx.post(`${BE}/api/v1/auth/login`, { data: { email, password: PASSWORD } })
  if (!res.ok()) throw new Error(`login ${email}: ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}
async function fetchMe(ctx: APIRequestContext, token: string): Promise<Me> {
  const res = await ctx.get(`${BE}/api/v1/users/me`, { headers: { Authorization: `Bearer ${token}` } })
  if (!res.ok()) throw new Error(`me: ${res.status()}`)
  return (await res.json()).data as Me
}

const test = base.extend<
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- test-scoped の追加 fixture は無い（worker-scoped の ctx のみ）
  {},
  {
    ctx: { admin: string; m1: string; m1Me: Me; m2: string; m2Me: Me; lineId: number; slotId: number; slotDate: string; startTime: string; endTime: string }
  }
>({
  // 複数ユーザーを spec 内で切り替えるため storageState は使わない
  // eslint-disable-next-line no-empty-pattern
  storageState: async ({}, use) => { await use(undefined) },
  ctx: [
    // eslint-disable-next-line no-empty-pattern
    async ({}, use) => {
      const rc = await playwrightRequest.newContext()
      const admin = await login(rc, ADMIN_EMAIL)
      const m1 = await login(rc, MEMBER1_EMAIL)
      const m1Me = await fetchMe(rc, m1)
      const m2 = await login(rc, MEMBER2_EMAIL)
      const m2Me = await fetchMe(rc, m2)
      // ADMIN が予約対象(ライン) + 本日枠を用意（マトリックスの既定表示週 = 今週に本日が含まれる）
      const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${admin}` }
      const slotDate = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Tokyo' }) // YYYY-MM-DD JST
      // 既存のアクティブな予約対象(ライン)を再利用する（チームあたり最大5本制約を回避）
      const linesRes = await rc.get(`${BE}/api/v1/teams/${TEAM_ID}/reservation-lines`, { headers: auth })
      if (!linesRes.ok()) throw new Error(`listLines ${linesRes.status()}: ${await linesRes.text()}`)
      const linesData = (await linesRes.json()).data as Array<{ id: number; meta?: { isActive?: boolean } }>
      const active = linesData.find(l => l.meta?.isActive) ?? linesData[0]
      if (!active) throw new Error('team 92 に予約対象(ライン)が無い')
      const lineId = active.id
      // 一意な枠時刻（実行ごとに hour を変えて衝突を避ける）。
      // 【旧表示撤去 2026-08-04 追従】マトリックスの30分セル（span=1）は GroupBookingDialog へ
      // ルーティングされるため、本テストが検証したい ReservationForm 経路（確認ダイアログの
      // 「予約する」ボタン）へ到達するには長尺枠（span>1）が必要。よって60分枠で作る。
      const hh = String(8 + (Math.floor(Date.now() / 1000) % 13)).padStart(2, '0') // 08..20
      const startTime = `${hh}:00`
      const endTime = `${String(Number(hh) + 1).padStart(2, '0')}:00`
      const slotRes = await rc.post(`${BE}/api/v1/teams/${TEAM_ID}/reservation-slots`, { headers: auth, data: { slotDate, startTime, endTime } })
      if (!slotRes.ok()) throw new Error(`createSlot ${slotRes.status()}: ${await slotRes.text()}`)
      const slotId = (await slotRes.json()).data.id as number
      await rc.dispose()
      await use({ admin, m1, m1Me, m2, m2Me, lineId, slotId, slotDate, startTime, endTime })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(120_000)

async function installApiBridge(page: Page, token: string): Promise<void> {
  const pageOrigin = new URL(page.url() || 'http://localhost:3000').origin
  await page.unroute('**/api/v1/**').catch(() => {})
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
    const relay = await page.request.fetch(target, { method: req.method(), headers, data: req.postData() ?? undefined, maxRedirects: 0 })
    const respHeaders: Record<string, string> = { ...relay.headers() }
    respHeaders['access-control-allow-origin'] = pageOrigin
    respHeaders['access-control-allow-credentials'] = 'true'
    await route.fulfill({ status: relay.status(), headers: respHeaders, body: await relay.body() })
  })
}

async function seedAuth(page: Page, me: Me): Promise<void> {
  const user = { id: me.id, email: me.email, fullName: `${me.lastName} ${me.firstName}`, profileImageUrl: me.avatarUrl, systemRole: me.systemRole ?? undefined, timezone: me.timezone ?? undefined }
  await page.addInitScript(({ user, expiresAt }) => {
    localStorage.setItem('currentUser', JSON.stringify(user))
    localStorage.setItem('tokenExpiresAt', String(expiresAt))
  }, { user, expiresAt: Date.now() + 24 * 60 * 60 * 1000 })
}

async function enter(page: Page, token: string, me: Me, path: string): Promise<void> {
  // real-admin.json 由来の管理者 Cookie を除去し、注入 Bearer のみで認証する
  // （残すと BE が Cookie 側セッションを優先し、非管理者ユーザーが管理者として描画されうる）
  await page.context().clearCookies()
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await installApiBridge(page, token)
  await seedAuth(page, me)
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
}

test.describe.serial('RSV-F034 メンバー予約 + 緊急休業（実機）', () => {
  test('条件1: MEMBER1 が UI で予約を作成し成立する', async ({ page, ctx }) => {
    // 事前の member1 予約 ID 集合（UI 操作で新規に増える 1 件を裏取りするため）
    const rc0 = await playwrightRequest.newContext()
    const before = new Set(((await (await rc0.get(`${BE}/api/v1/reservations/my`, { headers: { Authorization: `Bearer ${ctx.m1}` } })).json()).data as Array<{ id: number }>).map(r => r.id))
    await rc0.dispose()

    await enter(page, ctx.m1, ctx.m1Me, `/teams/${TEAM_SLUG}/reservations`)

    // 予約する タブ（既定 value=0）にマトリックス（SlotMatrixPicker）が出る（canBook=true / 所属メンバー）
    const bookTab = page.getByRole('tab', { name: '予約する' })
    if (await bookTab.count()) await bookTab.click()
    // 本日の当該セル（fixture が作成した一意時刻の60分枠・空き）をクリック。
    // マトリックスのセル名は「YYYY/MM/DD (曜) HH:MM {予約対象名} 空き」。時刻が一意なので取り違えない。
    const cellDateLabel = `${ctx.slotDate.replaceAll('-', '/')} (${['日', '月', '火', '水', '木', '金', '土'][new Date(`${ctx.slotDate}T00:00:00Z`).getUTCDay()]})`
    const slotBtn = page
      .getByRole('button', { name: new RegExp(`^${cellDateLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} ${ctx.startTime} .* 空き$`) })
      .first()
    await expect(slotBtn, `本日の空きセル(${ctx.startTime})が表示される`).toBeVisible({ timeout: 20_000 })
    await slotBtn.click()
    // 確認ダイアログ内の「予約する」ボタン押下（タブ名と衝突しないよう dialog にスコープ）
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    await dialog.getByRole('button', { name: '予約する' }).click()
    await page.waitForTimeout(3000)
    await page.screenshot({ path: 'test-results/rsv-cond1-member1-booked.png', fullPage: true })

    // API で予約成立を裏取り（UI クリックで member1 に新規予約が 1 件増え、時刻が当該枠と一致）
    const rc = await playwrightRequest.newContext()
    const res = await rc.get(`${BE}/api/v1/reservations/my`, { headers: { Authorization: `Bearer ${ctx.m1}` } })
    const my = (await res.json()).data as Array<{ id: number; identifier?: { userId?: number }; slot?: { slotDate?: string; startTime?: string } }>
    const created = my.filter(r => !before.has(r.id))
    expect(created.length, 'UI 予約で member1 の予約が新規に増える').toBeGreaterThanOrEqual(1)
    const match = created.find(r => r.slot?.slotDate === ctx.slotDate && (r.slot?.startTime ?? '').startsWith(ctx.startTime))
    expect(match, `新規予約が当該枠(${ctx.slotDate} ${ctx.startTime})と一致し member1(${ctx.m1Me.id}) が予約者`).toBeTruthy()
    expect(match?.identifier?.userId).toBe(ctx.m1Me.id)
    await rc.dispose()
  })

  test('条件2: MEMBER1 個人マイ予約に反映される', async ({ page, ctx }) => {
    await enter(page, ctx.m1, ctx.m1Me, '/my/reservations')
    // マイ予約ページに本日枠のカード（当該時刻）が出る
    await expect(page.getByText(new RegExp(ctx.startTime)).first(), 'マイ予約に本日枠の予約が表示される').toBeVisible({ timeout: 20_000 })
    await page.screenshot({ path: 'test-results/rsv-cond2-member1-myreservations.png', fullPage: true })
  })

  test('条件3: MEMBER2 の予約一覧に MEMBER1 の予約が出ない・予約者列が無い', async ({ page, ctx }) => {
    await enter(page, ctx.m2, ctx.m2Me, `/teams/${TEAM_SLUG}/reservations`)
    // 予約一覧 タブ（value=1）
    const listTab = page.getByRole('tab', { name: '予約一覧' })
    await expect(listTab).toBeVisible({ timeout: 20_000 })
    await listTab.click()
    await page.waitForTimeout(2000)
    // 非管理者=mine モード: 予約者(氏名)列ヘッダが存在しない
    await expect(page.getByRole('columnheader', { name: '予約者' }), '非管理者の一覧に予約者列は無い').toHaveCount(0)
    // MEMBER1 の氏名（E2Eユーザー 一般）がどこにも出ない
    await expect(page.getByText('E2Eユーザー 一般'), 'member2 の一覧に member1 の氏名は出ない').toHaveCount(0)
    await page.screenshot({ path: 'test-results/rsv-cond3-member2-list-mine.png', fullPage: true })

    // API 裏取り: member2 の my に member1 の本日枠予約は無い / team 一覧は 403
    const rc = await playwrightRequest.newContext()
    const myRes = await rc.get(`${BE}/api/v1/reservations/my`, { headers: { Authorization: `Bearer ${ctx.m2}` } })
    const my = (await myRes.json()).data as Array<{ identifier?: { userId?: number } }>
    expect(my.every(r => r.identifier?.userId === ctx.m2Me.id), 'member2 の my は自分の予約のみ').toBeTruthy()
    const teamRes = await rc.get(`${BE}/api/v1/teams/${TEAM_ID}/reservations`, { headers: { Authorization: `Bearer ${ctx.m2}` } })
    expect(teamRes.status(), 'member2 のチーム予約一覧(管理API)は 403').toBe(403)
    await rc.dispose()
  })

  test('条件4: ADMIN 緊急休業 → MEMBER1 通知の確認ボタン → 確認済み', async ({ page, ctx }) => {
    // ADMIN が本日枠を含む緊急休業を送信（英字本文=シェル無関係。cancel しないで予約は残す）
    const rc = await playwrightRequest.newContext()
    const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${ctx.admin}` }
    const subject = `E2E-CLOSURE-${Date.now()}` // この run の通知を一意に特定する
    const closeRes = await rc.post(`${BE}/api/v1/teams/${TEAM_ID}/emergency-closures`, {
      headers: auth,
      data: { startDate: ctx.slotDate, endDate: ctx.slotDate, reason: 'E2E closure reason', subject, messageBody: 'E2E closure body', cancelReservations: false },
    })
    expect(closeRes.status(), '緊急休業送信は 201').toBe(201)
    const closureId = (await closeRes.json()).data.id as number

    // MEMBER1 の通知一覧を開く
    await enter(page, ctx.m1, ctx.m1Me, '/notifications')
    // この run の緊急休業通知行（subject を含む）を特定し、その中の「休業を確認」ボタンを押す
    const row = page.locator('div[role="button"]').filter({ hasText: subject })
    await expect(row, 'この run の緊急休業通知が表示される').toBeVisible({ timeout: 20_000 })
    const confirmBtn = row.getByRole('button', { name: '休業を確認' })
    await expect(confirmBtn, '緊急休業通知に確認ボタンが出る').toBeVisible({ timeout: 10_000 })
    await page.screenshot({ path: 'test-results/rsv-cond4-member1-notif-confirm-button.png', fullPage: true })
    await confirmBtn.click()
    await page.waitForTimeout(3000)
    await page.screenshot({ path: 'test-results/rsv-cond4-member1-confirmed.png', fullPage: true })

    // API 裏取り: ADMIN getConfirmations で member1 が confirmed=true
    const confRes = await rc.get(`${BE}/api/v1/teams/${TEAM_ID}/emergency-closures/${closureId}/confirmations`, { headers: auth })
    const confs = (await confRes.json()).data as Array<{ userId: number; confirmed: boolean; confirmedAt: string | null }>
    const mine = confs.find(c => c.userId === ctx.m1Me.id)
    expect(mine, 'member1 の確認レコードが存在する').toBeTruthy()
    expect(mine?.confirmed, 'member1 が確認済み(confirmed=true)').toBe(true)
    await rc.dispose()
  })
})
