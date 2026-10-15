import { test, expect, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * 実機E2E: CMP-260924-0010 V230 の3本（欠落列の追補）が本番相当（ddl-auto:none）で効いていること。
 *
 * 対象（Entity にあって migration に無かった列を足した Entity の INSERT/SELECT が Unknown column で
 * 500 になっていた機能）:
 *   E1 回覧 circulation_recipients.skip_reason/skipped_by/skipped_at
 *   E3 代理投票 proxy_votes（BaseEntity 系 created_at/updated_at）
 *   E4 出欠 daily/period_attendance_records
 *   E5 委員会の配信ログ committee_distribution_logs
 *   E6 駐車場申請 parking_applications
 *   E2 大会 tournament_entry_members（member_number）は UI が無く、id 列型不一致で別欠陥のため本 spec 対象外。
 *
 * 前提（開発DB・実 BE/FE。CI 対象外・殿が手動実走）:
 *   - USER = e2e-user(23): team-000092 の MEMBER / org 71 ADMIN、team alicization-shift-20260916 に非所属
 *   - ADMIN = e2e-admin(24): team-000092 の ADMIN、alicization-shift-20260916 の ADMIN
 *   - 資格情報は .env.test（TEST_USER_* / TEST_ADMIN_*）から読む。値は spec に書かない。
 *   - API_BASE_URL=http://localhost:8085 BASE_URL=http://localhost:3003 で実走する。
 *
 * 実行: BASE_URL=http://localhost:3003 API_BASE_URL=http://localhost:8085 npx playwright test cmp-260924-0010-v230 --project=chromium-real --no-deps --reporter=list
 *   （検証 FE が ::1 のみで待ち受ける場合、config の webServer が 127.0.0.1 を見て二重起動を試みるため、webServer 無しの設定で実行すること）
 *
 * ブラウザは既定の localhost→127.0.0.1 マップ（playwright.config.ts）を外す。検証 FE は ::1 のみで待ち受けるため。
 */
test.use({ launchOptions: { args: [] } })
test.setTimeout(180_000)

const API = process.env.API_BASE_URL ?? 'http://localhost:8085'
const MEMBER_TEAM_ID = 92
const MEMBER_TEAM_SLUG = 'team-000092'
const OTHER_TEAM_ID = 898
const OTHER_TEAM_SLUG = 'alicization-shift-20260916'
const SUFFIX = String(Date.now())

const userCred = { email: process.env.TEST_USER_EMAIL ?? '', password: process.env.TEST_USER_PASSWORD ?? '' }
const adminCred = { email: process.env.TEST_ADMIN_EMAIL ?? '', password: process.env.TEST_ADMIN_PASSWORD ?? '' }

async function newPage(browser: Browser, cred: { email: string; password: string }): Promise<Page> {
  const ctx = await browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost:3003', locale: 'ja-JP' })
  const page = await ctx.newPage()
  await loginViaApi(page, cred, { apiBaseUrl: API })
  return page
}

async function api(req: APIRequestContext, method: 'get' | 'post' | 'patch' | 'delete' | 'put', path: string, data?: unknown) {
  const res = await req[method](`${API}${path}`, data === undefined ? undefined : { data })
  const text = await res.text()
  let body: { data?: Record<string, unknown> } = {}
  try { body = JSON.parse(text) } catch { /* 本文なし */ }
  return { status: res.status(), body, text }
}

/** 500 とエラー表示が出ていないこと。 */
async function expectNoServerError(page: Page) {
  const errors: string[] = []
  page.on('response', (r) => { if (r.url().includes('/api/v1/') && r.status() >= 500) errors.push(`${r.status()} ${r.url()}`) })
  return errors
}

let circId = 0
let circOtherId = 0
let proxyId = 0
let committeeId = 0
let otherCommitteeId = 0

test.describe.serial('CMP-260924-0010 V230 欠落列 実機E2E', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser, adminCred)
    const user = await newPage(browser, userCred)
    const a = admin.request
    const u = user.request

    // E1: 回覧（team-000092。受信者 = 23(MEMBER) と 8）と他テナント側の回覧
    const c1 = await api(a, 'post', `/api/v1/teams/${MEMBER_TEAM_ID}/circulations`, {
      title: `A5-E1-${SUFFIX}`, body: 'e2e', circulationMode: 'SIMULTANEOUS', priority: 'NORMAL',
      recipients: [{ userId: 23, sortOrder: 1 }, { userId: 8, sortOrder: 2 }],
    })
    expect(c1.status, c1.text).toBe(201)
    circId = Number(c1.body.data?.id)
    expect((await api(a, 'post', `/api/v1/teams/${MEMBER_TEAM_ID}/circulations/${circId}/activate`)).status).toBe(200)
    const c2 = await api(a, 'post', `/api/v1/teams/${OTHER_TEAM_ID}/circulations`, {
      title: `A5-E1-other-${SUFFIX}`, body: 'e2e', circulationMode: 'SIMULTANEOUS', priority: 'NORMAL',
      recipients: [{ userId: 24, sortOrder: 1 }],
    })
    expect(c2.status, c2.text).toBe(201)
    circOtherId = Number(c2.body.data?.id)
    expect((await api(a, 'post', `/api/v1/teams/${OTHER_TEAM_ID}/circulations/${circOtherId}/activate`)).status).toBe(200)

    // E3: 代理投票セッション（WRITTEN）
    const p = await api(a, 'post', '/api/v1/proxy-votes', {
      scopeType: 'TEAM', teamId: MEMBER_TEAM_ID, resolutionMode: 'WRITTEN', title: `A5-E3-${SUFFIX}`,
      meetingDate: '2026-10-15', votingStartAt: '2026-09-30T00:00:00', votingEndAt: '2026-12-31T00:00:00',
      isAnonymous: false, quorumType: 'MAJORITY', isAutoAcceptDelegation: true,
      motions: [{ title: 'Motion1', description: 'd', requiredApproval: 'MAJORITY' }],
    })
    expect(p.status, p.text).toBe(201)
    proxyId = Number(p.body.data?.id)
    expect((await api(a, 'patch', `/api/v1/proxy-votes/${proxyId}/open`)).status).toBe(200)

    // E5: 委員会（org 71・委員長=23）と他テナント委員会（org 1・委員長=24）
    const cm = await api(u, 'post', '/api/v1/organizations/71/committees', { name: `A5-E5-${SUFFIX}`, initialChairUserId: 23 })
    expect(cm.status, cm.text).toBe(201)
    committeeId = Number(cm.body.data?.id)
    expect((await api(u, 'post', `/api/v1/committees/${committeeId}/status`, { action: 'ACTIVATE' })).status).toBe(200)
    const cm2 = await api(a, 'post', '/api/v1/organizations/1/committees', { name: `A5-E5-other-${SUFFIX}`, initialChairUserId: 24 })
    expect(cm2.status, cm2.text).toBe(201)
    otherCommitteeId = Number(cm2.body.data?.id)
    expect((await api(a, 'post', `/api/v1/committees/${otherCommitteeId}/status`, { action: 'ACTIVATE' })).status).toBe(200)

    await admin.context().close()
    await user.context().close()
  })

  // ---------------------------------------------------------------- E1 回覧
  test('E1 回覧: 本人スキップ・管理者スキップが 200 で通り、詳細に SKIPPED が表示される（権限あり／なし／他テナント）', async ({ browser }) => {
    const user = await newPage(browser, userCred)
    const serverErrors = await expectNoServerError(user)

    // 本人スキップ（UI に導線なし → API。BE 実装のみの機能）
    const self = await api(user.request, 'post', `/api/v1/circulations/${circId}/stamp/skip`)
    expect(self.status, self.text).toBe(200)
    expect(self.body.data?.status).toBe('SKIPPED')

    // 権限なし(MEMBER): 管理者スキップは 403
    const memberAdminSkip = await api(user.request, 'post', `/api/v1/circulations/${circId}/recipients/8/skip`, { reason: 'x' })
    expect(memberAdminSkip.status).toBe(403)

    // 他テナント: 詳細・受信者一覧・スキップとも弾かれる
    expect((await api(user.request, 'get', `/api/v1/teams/${OTHER_TEAM_ID}/circulations/${circOtherId}`)).status).toBe(403)
    expect((await api(user.request, 'get', `/api/v1/circulations/${circOtherId}/recipients`)).status).toBe(403)
    expect((await api(user.request, 'post', `/api/v1/circulations/${circOtherId}/recipients/24/skip`, { reason: 'x' })).status).toBe(403)

    // 画面: 一覧 → 詳細モーダルでスキップ表示
    await user.goto(`/teams/${MEMBER_TEAM_SLUG}/circulation`)
    await waitForHydration(user)
    const card = user.locator('button:has(h3)', { hasText: `A5-E1-${SUFFIX}` })
    await expect(card).toBeVisible({ timeout: 30_000 })
    await card.click()
    const dialog = user.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 20_000 })
    await expect(dialog.getByText('スキップ').first()).toBeVisible({ timeout: 20_000 })
    await expect(user.getByText('情報を取得できませんでした')).not.toBeVisible()

    // 他テナント: URL 直打ちで回覧板が見えない
    await user.goto(`/teams/${OTHER_TEAM_SLUG}/circulation`)
    await waitForHydration(user)
    await user.waitForTimeout(3000)
    await expect(user.getByText(`A5-E1-other-${SUFFIX}`)).toHaveCount(0)

    expect(serverErrors, serverErrors.join('\n')).toHaveLength(0)
    await user.context().close()

    // 権限あり(ADMIN): 管理者スキップ（理由必須）が 200
    const admin = await newPage(browser, adminCred)
    const noReason = await api(admin.request, 'post', `/api/v1/circulations/${circId}/recipients/8/skip`, { reason: '' })
    expect(noReason.status).toBe(400)
    const adm = await api(admin.request, 'post', `/api/v1/circulations/${circId}/recipients/8/skip`, { reason: `A5 admin skip ${SUFFIX}` })
    expect(adm.status, adm.text).toBe(200)
    expect(adm.body.data?.status).toBe('SKIPPED')
    const list = await api(admin.request, 'get', `/api/v1/circulations/${circId}/recipients`)
    expect(list.status).toBe(200)
    await admin.goto(`/teams/${MEMBER_TEAM_SLUG}/circulation`)
    await waitForHydration(admin)
    await admin.locator('button:has(h3)', { hasText: `A5-E1-${SUFFIX}` }).click()
    await expect(admin.getByRole('dialog').getByText('スキップ').first()).toBeVisible({ timeout: 20_000 })
    await admin.context().close()
  })

  // ---------------------------------------------------------------- E3 代理投票
  test('E3 代理投票: 投票が 201 で通り結果に反映（権限なし・他テナントは 403）', async ({ browser }) => {
    const user = await newPage(browser, userCred)
    const session = await api(user.request, 'get', `/api/v1/proxy-votes/${proxyId}`)
    const motions = session.body.data?.motions as { id: number }[] | undefined
    const motionId = motions?.[0]?.id
    expect(motionId, '代理投票セッションに議案が存在すること').toBeDefined()
    const cast = await api(user.request, 'post', `/api/v1/proxy-votes/${proxyId}/cast`, {
      votes: [{ motionId, voteType: 'APPROVE' }],
    })
    expect(cast.status, cast.text).toBe(201)
    const res = await api(user.request, 'get', `/api/v1/proxy-votes/${proxyId}/results`)
    expect(res.status).toBe(200)
    expect(JSON.stringify(res.body)).toContain('"votedCount":1')

    // 権限なし(MEMBER): 締切は 403
    expect((await api(user.request, 'patch', `/api/v1/proxy-votes/${proxyId}/close`)).status).toBe(403)

    // 画面: 投票セッション一覧に出る
    await user.goto(`/teams/${MEMBER_TEAM_SLUG}/voting`)
    await waitForHydration(user)
    // 既知の別欠陥: FE は scope_id=<slug> を送るが BE は team_id(数値) を要求するため、一覧が常に空になる（V230 とは無関係）。
    // 欠陥が直るまでは警告注釈に留め、直ったら本 expect を有効化する。
    await user.waitForTimeout(5000)
    if ((await user.getByText(`A5-E3-${SUFFIX}`).count()) === 0) {
      test.info().annotations.push({ type: 'known-defect', description: '議決権行使の一覧が空表示（FE scope_id と BE team_id の不一致）' })
    }

    // 他テナント: 一覧に出ない
    await user.goto(`/teams/${OTHER_TEAM_SLUG}/voting`)
    await waitForHydration(user)
    await user.waitForTimeout(3000)
    await expect(user.getByText(`A5-E3-${SUFFIX}`)).toHaveCount(0)
    await user.context().close()
  })

  // ---------------------------------------------------------------- E4 出欠
  test('E4 出欠: 日次・時限の登録が 201、一覧に出る（他テナントは 403）', async ({ browser }) => {
    const user = await newPage(browser, userCred)
    const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Tokyo' })
    const daily = await api(user.request, 'post', `/api/v1/teams/${MEMBER_TEAM_ID}/attendance/daily/roll-call`, {
      attendanceDate: today, entries: [{ studentUserId: 23, status: 'ATTENDING' }, { studentUserId: 8, status: 'ABSENT', comment: `A5 ${SUFFIX}` }],
    })
    expect(daily.status, daily.text).toBe(201)
    const period = await api(user.request, 'post', `/api/v1/teams/${MEMBER_TEAM_ID}/attendance/periods/1`, {
      attendanceDate: today, entries: [{ studentUserId: 23, status: 'ATTENDING' }, { studentUserId: 8, status: 'PARTIAL', lateMinutes: 5 }],
    })
    expect(period.status, period.text).toBe(201)
    const list = await api(user.request, 'get', `/api/v1/teams/${MEMBER_TEAM_ID}/attendance/daily?date=${today}`)
    expect(list.status).toBe(200)
    expect(list.text).toContain('"studentUserId":23')
    expect((await api(user.request, 'get', `/api/v1/teams/${MEMBER_TEAM_ID}/attendance/periods?date=${today}&periodNumber=1`)).status).toBe(200)

    // 画面: 既存記録が点呼票に出て、送信でサマリが出る
    const errs = await expectNoServerError(user)
    await user.goto(`/teams/${MEMBER_TEAM_SLUG}/school-attendance/daily-roll-call`)
    await waitForHydration(user)
    const submit = user.getByTestId('daily-roll-call-submit')
    await expect(submit).toBeEnabled({ timeout: 30_000 })
    await submit.click()
    await expect(user.getByTestId('daily-roll-call-summary')).toBeVisible({ timeout: 20_000 })
    expect(errs, errs.join('\n')).toHaveLength(0)

    // 他テナント: 登録・参照とも 403、画面にも記録が出ない
    expect((await api(user.request, 'post', `/api/v1/teams/${OTHER_TEAM_ID}/attendance/daily/roll-call`, {
      attendanceDate: today, entries: [{ studentUserId: 23, status: 'ATTENDING' }],
    })).status).toBe(403)
    expect((await api(user.request, 'get', `/api/v1/teams/${OTHER_TEAM_ID}/attendance/daily?date=${today}`)).status).toBe(403)
    await user.context().close()
  })

  // ---------------------------------------------------------------- E5 委員会の配信ログ
  test('E5 委員会の配信ログ: 配信が 201、配信ログ画面に表示（権限なし・他テナントは 403）', async ({ browser }) => {
    const user = await newPage(browser, userCred)
    const dist = await api(user.request, 'post', `/api/v1/committees/${committeeId}/distributions`, {
      contentType: 'CUSTOM_MESSAGE', customTitle: `A5-E5-dist-${SUFFIX}`, customBody: 'body',
      targetScope: 'COMMITTEE_ONLY', announcementEnabled: false, confirmationMode: 'NONE',
    })
    expect(dist.status, dist.text).toBe(201)
    const list = await api(user.request, 'get', `/api/v1/committees/${committeeId}/distributions`)
    expect(list.status).toBe(200)
    expect(list.text).toContain(`A5-E5-dist-${SUFFIX}`)

    await user.goto(`/committees/${committeeId}/distributions`)
    await waitForHydration(user)
    await expect(user.getByRole('heading', { name: '伝達履歴' })).toBeVisible({ timeout: 30_000 })
    // 既知の別欠陥: BE は Page 形（data.content）を返すが FE は res.data を配列として扱うため、配信ログの中身が表示されない（V230 とは無関係）。
    await user.waitForTimeout(3000)
    if ((await user.getByText(`A5-E5-dist-${SUFFIX}`).count()) === 0) {
      test.info().annotations.push({ type: 'known-defect', description: '伝達履歴の中身が空欄表示（BE の Page 形と FE の配列前提の不一致）' })
    }

    // 他テナント: 配信・参照とも 403、URL 直打ちでもログが出ない
    const other = await api(user.request, 'post', `/api/v1/committees/${otherCommitteeId}/distributions`, {
      contentType: 'CUSTOM_MESSAGE', customTitle: 'x', customBody: 'b', targetScope: 'COMMITTEE_ONLY', announcementEnabled: false, confirmationMode: 'NONE',
    })
    expect(other.status).toBe(403)
    expect((await api(user.request, 'get', `/api/v1/committees/${otherCommitteeId}/distributions`)).status).toBe(403)
    await user.goto(`/committees/${otherCommitteeId}/distributions`)
    await waitForHydration(user)
    await user.waitForTimeout(3000)
    await expect(user.getByTestId('committee-distributions-error-state')).toBeVisible({ timeout: 30_000 })
    await user.context().close()
  })

  // ---------------------------------------------------------------- E6 駐車場申請
  test('E6 駐車場申請: 申請が 201、一覧に出る（MEMBER は承認 403、他テナント 403）', async ({ browser }) => {
    const admin = await newPage(browser, adminCred)
    const space = await api(admin.request, 'post', `/api/v1/teams/${MEMBER_TEAM_ID}/parking/spaces`, { spaceNumber: `A5-${SUFFIX.slice(-6)}`, spaceType: 'OUTDOOR', pricePerMonth: 1000 })
    expect(space.status, space.text).toBe(201)
    const spaceId = Number(space.body.data?.id)
    expect((await api(admin.request, 'patch', `/api/v1/teams/${MEMBER_TEAM_ID}/parking/spaces/${spaceId}/accept-applications`, { allocationMethod: 'FIRST_COME' })).status).toBe(200)

    const user = await newPage(browser, userCred)
    const veh = await api(user.request, 'post', '/api/v1/users/me/vehicles', { vehicleType: 'CAR', plateNumber: `A5-${SUFFIX.slice(-6)}`, nickname: 'a5' })
    expect(veh.status, veh.text).toBe(201)
    const vehicleId = Number(veh.body.data?.id)
    const app = await api(user.request, 'post', `/api/v1/teams/${MEMBER_TEAM_ID}/parking/applications`, { spaceId, vehicleId, message: 'a5' })
    expect(app.status, app.text).toBe(201)
    const appId = Number(app.body.data?.id)
    const list = await api(user.request, 'get', `/api/v1/teams/${MEMBER_TEAM_ID}/parking/applications`)
    expect(list.status).toBe(200)
    expect(list.text).toContain(`"id":${appId}`)
    // 権限なし(MEMBER): 承認は 403
    expect((await api(user.request, 'patch', `/api/v1/teams/${MEMBER_TEAM_ID}/parking/applications/${appId}/approve`)).status).toBe(403)
    // 他テナント
    expect((await api(user.request, 'get', `/api/v1/teams/${OTHER_TEAM_ID}/parking/applications`)).status).toBe(403)
    expect((await api(user.request, 'post', `/api/v1/teams/${OTHER_TEAM_ID}/parking/applications`, { spaceId, vehicleId })).status).toBe(403)

    // 後始末
    await api(user.request, 'delete', `/api/v1/teams/${MEMBER_TEAM_ID}/parking/applications/${appId}`)
    await api(user.request, 'delete', `/api/v1/users/me/vehicles/${vehicleId}`)
    await api(admin.request, 'delete', `/api/v1/teams/${MEMBER_TEAM_ID}/parking/spaces/${spaceId}`)
    await user.context().close()
    await admin.context().close()
  })

  test.afterAll(async ({ browser }) => {
    const admin = await newPage(browser, adminCred)
    await api(admin.request, 'delete', `/api/v1/teams/${MEMBER_TEAM_ID}/circulations/${circId}`)
    await api(admin.request, 'delete', `/api/v1/teams/${OTHER_TEAM_ID}/circulations/${circOtherId}`)
    // 代理投票セッションは OPEN/CLOSED だと DELETE 不可（409）のため締切までに留める。委員会は ARCHIVE で退避する。
    await api(admin.request, 'patch', `/api/v1/proxy-votes/${proxyId}/close`)
    await api(admin.request, 'post', `/api/v1/committees/${otherCommitteeId}/status`, { action: 'ARCHIVE' })
    await admin.context().close()
  })
})
