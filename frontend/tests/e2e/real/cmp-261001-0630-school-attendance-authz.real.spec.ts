/* eslint-disable no-empty-pattern -- Playwright は test 関数の第 1 引数に分割代入パターンを要求する（fixture を使わないテストは空パターンになる） */
import { test, expect, type Browser, type Page, type Response } from '@playwright/test'
import JA from '../../../app/locales/ja/school.json' with { type: 'json' }
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * 実機E2E: CMP-261001-0630（学校出欠 クラス全体の「閲覧」認可）／ CMP-260930-0230（「登録」認可）第1段。
 * PR #3598（BE: SchoolAttendanceAccessPolicy と Facade）と #3648（FE: 権限出し分け）の検証。
 *
 * 規則（マスター裁可 2026-10-02）:
 *   V（閲覧・統計・CSV）= チーム ADMIN / DEPUTY_ADMIN ／ class_homerooms の現役の担任・副担任 ／ VIEW_ATTENDANCE 委任者
 *   R（日次・時限の登録）= 現役の担任・副担任 ／ ADMIN / DEPUTY_ADMIN（VIEW_ATTENDANCE 委任者は含まない）
 *   SYSTEM_ADMIN は学校出欠のクラス全体を見られない。過去の担任（担任を外れた人）・一般 MEMBER・生徒・保護者・他テナントは拒否。
 *
 * 作法（/実機）: 対象の操作は画面（クリック・入力・送信）で行う。API を使うのはログイン・前提データ作成・後始末と、
 *   「画面が入口を出さない操作を BE が拒否するか」の確認（権限なしロールの URL 直打ち相当）だけ。
 *
 * 前提データはすべてアプリの API で作る（DB 直接 DML なし）:
 *   使い捨てチーム T（FAMILY）を ADMIN が作成 → 招待トークンで各ロールが参加 → DEPUTY_ADMIN へロール変更 →
 *   権限グループ（VIEW_ATTENDANCE）を委任者へ割当 → 学級担任設定（現役 / 前年度の終了済み）→ 生徒の点呼・時限レコード。
 *   他テナント = 別の使い捨てチーム O の ADMIN。保護者 = シード済みの user_care_links（e2e-user → 生徒 user 4）。
 *   後始末: 使い捨てチーム T / O を API で論理削除する。
 *
 * 資格情報: ダミーユーザー（e2e-dummy-N）と e2e-outsider / e2e-user / e2e-admin は frontend/.env.test の
 *   TEST_USER_PASSWORD と同じ共通パスワードで作られている。値は spec に書かない。
 *
 * 実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 npx playwright test -c playwright-real.config.ts \
 *   --project=chromium-real --no-deps cmp-261001-0630
 */
test.setTimeout(180_000)

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = process.env.TEST_USER_PASSWORD ?? ''
const DOMAIN = 'test.mannschaft.local'
const FORBIDDEN_TITLE = JA.school.attendance.forbidden.title
const PERMISSION_ERROR_TITLE = JA.school.attendance.permissionError.title
const REASONS = JA.school.attendance.absenceReason
const STAMP = String(Date.now())
const MARKER = `DENIED-${STAMP}`

/** VIEW_ATTENDANCE の permissions.id（Flyway V184.20260814202646 で登録）。応答の権限名で裏取りする。 */
const PERMISSION_ID_VIEW_ATTENDANCE = 37
const ROLE_ID_MEMBER = 4
const ROLE_ID_DEPUTY_ADMIN = 3
const STUDENT_USER_ID = 4 // e2e-dummy-2。e2e-user がシード済みの ACTIVE な見守り者（保護者）

type Role =
  | 'admin' | 'deputy' | 'homeroom' | 'assistant' | 'delegate'
  | 'member' | 'past' | 'student' | 'guardian' | 'otherAdmin' | 'outsider' | 'sysAdmin'

const EMAIL: Record<Role, string> = {
  admin: `e2e-dummy-9@${DOMAIN}`, // 使い捨てチーム T の ADMIN（作成者）
  deputy: `e2e-dummy-3@${DOMAIN}`, // DEPUTY_ADMIN
  homeroom: `e2e-dummy-4@${DOMAIN}`, // 現役の学級担任
  assistant: `e2e-dummy-5@${DOMAIN}`, // 現役の副担任
  delegate: `e2e-dummy-6@${DOMAIN}`, // VIEW_ATTENDANCE の委任者（MEMBER）
  member: `e2e-dummy-7@${DOMAIN}`, // 一般 MEMBER
  past: `e2e-dummy-8@${DOMAIN}`, // 前年度の担任（終了済み。担任を外れた人）
  student: `e2e-dummy-2@${DOMAIN}`, // 生徒（T の MEMBER）
  guardian: `e2e-user@${DOMAIN}`, // 保護者（生徒への ACTIVE な見守りのみ。T には非所属）
  otherAdmin: `e2e-dummy-10@${DOMAIN}`, // 他テナント O の ADMIN
  outsider: `e2e-outsider@${DOMAIN}`, // どこにも所属しない
  sysAdmin: `e2e-admin@${DOMAIN}`, // SYSTEM_ADMIN（T には非所属）
}

/** 閲覧 V のロールと、登録 R の可否（期待する BE 判定結果）。 */
const V_ROLES: { role: Role; canRecord: boolean; label: string }[] = [
  { role: 'admin', canRecord: true, label: 'チーム ADMIN' },
  { role: 'deputy', canRecord: true, label: 'DEPUTY_ADMIN' },
  { role: 'homeroom', canRecord: true, label: '現役の担任' },
  { role: 'assistant', canRecord: true, label: '現役の副担任' },
  { role: 'delegate', canRecord: false, label: 'VIEW_ATTENDANCE 委任者' },
]
const DENIED_ROLES: { role: Role; label: string }[] = [
  { role: 'member', label: '一般 MEMBER' },
  { role: 'past', label: '過去の担任（担任を外れた人）' },
  { role: 'student', label: '生徒' },
  { role: 'guardian', label: '保護者（見守りのみ・非所属）' },
  { role: 'otherAdmin', label: '他テナントの ADMIN' },
  { role: 'outsider', label: 'どこにも所属しない人' },
  { role: 'sysAdmin', label: 'SYSTEM_ADMIN' },
]

interface Session { page: Page; userId: number }
const sessions = new Map<Role, Session>()
let lastLoginAt = 0
let teamSlug = ''
let teamNumericId = 0
let otherSlug = ''
let homeroomRowId = 0
let browserRef: Browser

function jstDate(offsetDays = 0): string {
  const d = new Date(Date.now() + offsetDays * 86_400_000)
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Tokyo' }).format(d)
}
const TODAY = jstDate()
const ACADEMIC_YEAR = Number(TODAY.slice(0, 4)) - (Number(TODAY.slice(5, 7)) >= 4 ? 0 : 1)

/** ログインは 1 ロール 1 回（別 context の再ログインは他方のセッションを失効させるため）。ログイン上限を避けて間隔を空ける。 */
async function session(role: Role): Promise<Session> {
  const cached = sessions.get(role)
  if (cached) return cached
  const wait = 1500 - (Date.now() - lastLoginAt)
  if (wait > 0) await new Promise((r) => setTimeout(r, wait))
  const ctx = await browserRef.newContext({
    storageState: undefined,
    baseURL: process.env.BASE_URL ?? 'http://localhost:3001',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  })
  const page = await ctx.newPage()
  await loginViaApi(page, { email: EMAIL[role], password: PASSWORD }, { apiBaseUrl: API })
  lastLoginAt = Date.now()
  const me = await page.request.get(`${API}/api/v1/users/me`)
  expect(me.ok(), `${role} users/me`).toBeTruthy()
  const s = { page, userId: ((await me.json()).data as { id: number }).id }
  sessions.set(role, s)
  return s
}

/** API 呼び出し（前提作成・後始末・拒否確認用）。アクセストークン失効（15 分）時は refresh して 1 回だけ再試行する。 */
async function api(page: Page, method: string, path: string, data?: unknown) {
  const call = () =>
    page.request.fetch(`${API}/api/v1${path}`, {
      method,
      data: data === undefined ? undefined : data,
      headers: { 'Content-Type': 'application/json' },
    })
  let res = await call()
  if (res.status() === 401) {
    await page.request.post(`${API}/api/v1/auth/refresh`, { data: {} })
    res = await call()
  }
  return res
}

async function mustOk(res: Awaited<ReturnType<typeof api>>, what: string) {
  const text = await res.text()
  expect(res.status(), `${what}: ${text}`).toBeLessThan(300)
  return (text ? JSON.parse(text) : {}) as { data?: Record<string, unknown> }
}

const isPermissions = (r: Response) => r.url().includes('/attendance/permissions') && r.request().method() === 'GET'
const isRollCall = (r: Response) => r.url().includes('/attendance/daily/roll-call') && r.request().method() === 'POST'
const isPeriodPost = (r: Response) => /\/attendance\/periods\/\d+(\?.*)?$/.test(r.url()) && r.request().method() === 'POST'
const LIST_API = /\/attendance\/(daily\?|periods\/\d+\/candidates|periods\?|transition-alerts|statistics\/monthly|export)/

type PermBody = { data: { canView: boolean; canRecordDaily: boolean; canRecordPeriod: boolean } }

/** 描画待ち（.pi-spin と .p-progressspinner の両方が消えるまで）。 */
async function settled(page: Page) {
  await page.locator('.pi-spin, .p-progressspinner').first().waitFor({ state: 'detached', timeout: 30_000 })
}

/** 画面を開き、権限照会 API の応答を返す。一覧系 API の呼び出しを記録する。 */
async function visit(page: Page, sub: string): Promise<{ perm: PermBody; status: number; listCalls: string[] }> {
  const listCalls: string[] = []
  const onReq = (r: { url(): string; method(): string }) => {
    if (r.method() === 'GET' && LIST_API.test(r.url()) && !r.url().includes('/me/')) listCalls.push(r.url())
  }
  page.on('request', onReq)
  let res: Response | undefined
  const onRes = (r: Response) => {
    if (isPermissions(r)) res = r
  }
  page.on('response', onRes)
  // dev サーバの依存再最適化による全画面リロードで goto が ERR_ABORTED になることがある（アプリ欠陥ではない）。その場合に限り 1 回やり直す。
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      await page.goto(`/teams/${teamSlug}/school-attendance/${sub}`, { waitUntil: 'domcontentloaded' })
    } catch (e) {
      if (attempt === 1 || !String(e).includes('ERR_ABORTED')) throw e
      continue
    }
    break
  }
  await waitForHydration(page)
  await expect.poll(() => res, { timeout: 60_000, message: '権限照会 API の応答' }).toBeTruthy()
  page.off('response', onRes)
  if (!res) throw new Error('権限照会の応答を取得できなかった')
  const perm = (await res.json()) as PermBody
  await settled(page)
  await page.waitForTimeout(800)
  page.off('request', onReq)
  return { perm, status: res.status(), listCalls }
}

async function bodyText(page: Page, n = 160): Promise<string> {
  return (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, n)
}

async function shot(page: Page, testInfo: import('@playwright/test').TestInfo, name: string) {
  await testInfo.attach(name, { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
}

test.describe.serial('CMP-261001-0630 / CMP-260930-0230 学校出欠の認可 実機E2E（ロール横断）', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(300_000)
    browserRef = browser
    expect(PASSWORD, 'frontend/.env.test の TEST_USER_PASSWORD が必要').not.toBe('')

    // --- 使い捨てチーム T（ADMIN = admin ロール） ---
    const admin = await session('admin')
    const created = await mustOk(
      await api(admin.page, 'POST', '/teams', {
        name: `E2E School Authz ${STAMP}`,
        template: 'FAMILY',
        visibility: 'MEMBERS_AND_ABOVE',
        slug: `e2e-sa-${STAMP.slice(-9)}`,
      }),
      'T 作成',
    )
    teamSlug = String(created.data?.slug)
    teamNumericId = Number(created.data?.numericId)
    const tok = await mustOk(
      await api(admin.page, 'POST', `/teams/${teamSlug}/invite-tokens`, { roleId: ROLE_ID_MEMBER, expiresIn: '7d', maxUses: 30 }),
      '招待トークン',
    )
    const token = String(tok.data?.token)

    // --- 各ロールが招待で参加（MEMBER） ---
    for (const role of ['deputy', 'homeroom', 'assistant', 'delegate', 'member', 'past', 'student'] as Role[]) {
      const s = await session(role)
      await mustOk(await api(s.page, 'POST', `/invite/${token}/join`, {}), `${role} 参加`)
    }
    // --- DEPUTY_ADMIN へロール変更 ---
    await mustOk(
      await api(admin.page, 'PATCH', `/teams/${teamSlug}/members/${(await session('deputy')).userId}/role`, { roleId: ROLE_ID_DEPUTY_ADMIN }),
      'deputy ロール変更',
    )
    // --- VIEW_ATTENDANCE の権限グループを委任者へ割当 ---
    const group = await mustOk(
      await api(admin.page, 'POST', `/teams/${teamSlug}/permission-groups`, {
        name: `view-attendance-${STAMP}`,
        targetRole: 'MEMBER',
        permissionIds: [PERMISSION_ID_VIEW_ATTENDANCE],
      }),
      '権限グループ作成',
    )
    expect(group.data?.permissions, 'VIEW_ATTENDANCE を含む権限グループ').toEqual(['VIEW_ATTENDANCE'])
    await mustOk(
      await api(admin.page, 'PUT', `/teams/${teamSlug}/members/${(await session('delegate')).userId}/permission-groups`, {
        groupIds: [Number(group.data?.id)],
      }),
      '委任割当',
    )
    // --- 学級担任設定: 現役（担任 + 副担任）と、前年度の終了済み（過去の担任） ---
    const hr = await mustOk(
      await api(admin.page, 'POST', `/teams/${teamSlug}/homerooms`, {
        homeroomTeacherUserId: (await session('homeroom')).userId,
        assistantTeacherUserIds: [(await session('assistant')).userId],
        academicYear: ACADEMIC_YEAR,
        effectiveFrom: `${ACADEMIC_YEAR}-04-01`,
      }),
      '現役の担任設定',
    )
    homeroomRowId = Number(hr.data?.id)
    await mustOk(
      await api(admin.page, 'POST', `/teams/${teamSlug}/homerooms`, {
        homeroomTeacherUserId: (await session('past')).userId,
        academicYear: ACADEMIC_YEAR - 1,
        effectiveFrom: `${ACADEMIC_YEAR - 1}-04-01`,
        effectiveUntil: `${ACADEMIC_YEAR}-03-31`,
      }),
      '過去の担任設定',
    )
    // --- 他テナント O（ADMIN = otherAdmin） ---
    const other = await session('otherAdmin')
    const o = await mustOk(
      await api(other.page, 'POST', '/teams', {
        name: `E2E School Authz Other ${STAMP}`,
        template: 'FAMILY',
        visibility: 'MEMBERS_AND_ABOVE',
        slug: `e2e-sao-${STAMP.slice(-9)}`,
      }),
      'O 作成',
    )
    otherSlug = String(o.data?.slug)

    // --- 画面に作成の入口が無い日次・時限レコードを、担任（R）の API で前提作成する ---
    const hrPage = (await session('homeroom')).page
    await mustOk(
      await api(hrPage, 'POST', `/teams/${teamSlug}/attendance/daily/roll-call`, {
        attendanceDate: TODAY,
        entries: [{ studentUserId: STUDENT_USER_ID, status: 'UNDECIDED' }],
      }),
      '日次レコード前提作成',
    )
    await mustOk(
      await api(hrPage, 'POST', `/teams/${teamSlug}/attendance/periods/1`, {
        attendanceDate: TODAY,
        entries: [{ studentUserId: STUDENT_USER_ID, status: 'ATTENDING' }],
      }),
      '時限レコード前提作成',
    )
  })

  test.afterAll(async () => {
    // 後始末: 使い捨てチームを API で論理削除（ADMIN のみ可）。失敗しても残置として報告する。
    for (const [role, slug] of [['admin', teamSlug], ['otherAdmin', otherSlug]] as [Role, string][]) {
      const s = sessions.get(role)
      if (!s || !slug) continue
      const res = await api(s.page, 'DELETE', `/teams/${slug}`)
      console.log(`CLEANUP DELETE /teams/${slug} -> ${res.status()}`)
    }
    for (const s of sessions.values()) await s.page.context().close()
  })

  // ---------------------------------------------------------------------------------------------
  // V（閲覧できる）ロール: 4 画面がすべて開き、BE 判定（AC-23）と画面の出し分けが一致する。
  // ---------------------------------------------------------------------------------------------
  for (const { role, canRecord, label } of V_ROLES) {
    test(`AC-1/2/3/18/23 V: ${label} は 4 画面を開け、登録ボタンは ${canRecord ? '有効' : '非活性'}（統計・CSV も可）`, async ({}, testInfo) => {
      const { page } = await session(role)

      // 日次点呼
      const daily = await visit(page, 'daily-roll-call')
      expect(daily.status).toBe(200)
      expect(daily.perm.data).toMatchObject({ canView: true, canRecordDaily: canRecord, canRecordPeriod: canRecord })
      await expect(page.getByTestId('school-attendance-forbidden')).toHaveCount(0)
      await expect(page.getByTestId(`roll-call-row-${STUDENT_USER_ID}`)).toBeVisible()
      const submit = page.getByTestId('daily-roll-call-submit')
      if (canRecord) await expect(submit).toBeEnabled()
      else {
        await expect(submit).toBeDisabled()
        // 閲覧のみ: 状態ボタンを押しても提出はできない（FE の仕様は提出ボタン非活性）。ボタン自体は表示される点を記録する。
        await page.getByTestId(`roll-call-row-${STUDENT_USER_ID}-absent`).click()
        await expect(submit).toBeDisabled()
        testInfo.annotations.push({ type: 'view-only-status-buttons', description: 'delegate: 状態ボタンは表示・クリック可能だが提出は非活性' })
      }
      await shot(page, testInfo, `${role}-daily`)

      // 時限点呼
      const period = await visit(page, 'period-attendance')
      expect(period.perm.data.canView).toBe(true)
      await expect(page.getByTestId('school-attendance-forbidden')).toHaveCount(0)
      await expect(page.getByTestId(`period-row-${STUDENT_USER_ID}`)).toBeVisible()
      const psubmit = page.getByTestId('period-attendance-submit')
      if (canRecord) await expect(psubmit).toBeEnabled()
      else await expect(psubmit).toBeDisabled()

      // 移動検知アラート
      await visit(page, 'transition-alerts')
      await expect(page.getByTestId('school-attendance-forbidden')).toHaveCount(0)
      await expect(page.getByTestId('transition-alert-date')).toBeEnabled()
      await expect(page.locator('[data-testid="transition-alert-list"], [data-testid="transition-alert-empty"]').first()).toBeVisible()

      // 統計と CSV（V のみ）
      await visit(page, 'statistics')
      await expect(page.getByTestId('school-attendance-forbidden')).toHaveCount(0)
      await expect(page.getByTestId('statistics-page')).toBeVisible()
      await expect(page.getByTestId('statistics-year')).toBeVisible()
      await page.getByTestId('statistics-tab-term').click()
      const csvBtn = page.getByTestId('statistics-export-csv')
      await expect(csvBtn).toBeEnabled()
      // CSV: 画面のボタンが export URL へのダウンロードを開始する。
      // 注: FE は href を相対パス（/api/v1/...）に固定しているため、API が別オリジンの開発構成（FE:3001 / BE:8081、
      //     NUXT_API_PROXY なし）ではダウンロード自体は FE 側で 404 になる（本番は FE/BE 同一オリジンで該当しない）。
      //     そのためここでは「ダウンロード要求が正しい URL・ファイル名で出ること」を画面操作で確かめ、CSV の中身は同じ権限の BE 直接取得で確かめる。
      const dlP = page.waitForEvent('download', { timeout: 20_000 })
      await csvBtn.click()
      const dl = await dlP
      expect(dl.url()).toContain(`/api/v1/teams/${teamSlug}/attendance/export?from=`)
      expect(dl.suggestedFilename()).toMatch(/^attendance_.*\.csv$/)
      const csvRes = await api(page, 'GET', `/teams/${teamSlug}/attendance/export?from=${jstDate(-30)}&to=${TODAY}`)
      expect(csvRes.status(), await csvRes.text()).toBe(200)
      expect(csvRes.headers()['content-type']).toContain('csv')
      const csv = await csvRes.text()
      expect(csv.length, 'CSV が空').toBeGreaterThan(0)
      expect(csv, 'CSV に HTML が返っている').not.toMatch(/<!doctype html|<html/i)
      await shot(page, testInfo, `${role}-statistics`)
    })
  }

  // ---------------------------------------------------------------------------------------------
  // R（登録できる）ロール: 画面から日次点呼を提出する。
  // ---------------------------------------------------------------------------------------------
  for (const { role, label } of V_ROLES.filter((v) => v.canRecord)) {
    test(`AC-8/10 R: ${label} は画面から日次点呼（欠席+理由）を提出でき 201 になる`, async ({}, testInfo) => {
      const { page } = await session(role)
      await visit(page, 'daily-roll-call')
      const row = page.getByTestId(`roll-call-row-${STUDENT_USER_ID}`)
      await expect(row).toBeVisible()
      await page.getByTestId(`roll-call-row-${STUDENT_USER_ID}-absent`).click()
      await row.locator('.p-select').click()
      await page.getByRole('option', { name: REASONS.SICK, exact: true }).click()
      const resP = page.waitForResponse(isRollCall, { timeout: 30_000 })
      await page.getByTestId('daily-roll-call-submit').click()
      const res = await resP
      expect(res.status(), await res.text()).toBe(201)
      expect(res.request().postDataJSON().entries[0]).toMatchObject({ studentUserId: STUDENT_USER_ID, status: 'ABSENT', absenceReason: 'SICK' })
      await expect(page.getByText(JA.school.attendance.dailyRollCall.submitSuccess).first()).toBeVisible()
      await expect(page.getByTestId('daily-roll-call-summary')).toBeVisible()
      await shot(page, testInfo, `${role}-roll-call-submitted`)
    })
  }

  for (const role of ['homeroom', 'assistant'] as Role[]) {
    test(`AC-10 R: ${role === 'homeroom' ? '現役の担任' : '現役の副担任'} は画面から時限点呼を提出でき 2xx になる`, async ({}, testInfo) => {
      const { page } = await session(role)
      await visit(page, 'period-attendance')
      const row = page.getByTestId(`period-row-${STUDENT_USER_ID}`)
      await expect(row).toBeVisible()
      await page.getByTestId(`period-row-${STUDENT_USER_ID}-absent`).click()
      const resP = page.waitForResponse(isPeriodPost, { timeout: 30_000 })
      await page.getByTestId('period-attendance-submit').click()
      const res = await resP
      expect(res.status(), await res.text()).toBeLessThan(300)
      expect(res.request().postDataJSON().entries[0].status).toBe('ABSENT')
      await expect(page.getByText(JA.school.attendance.period.submitSuccess).first()).toBeVisible()
      await shot(page, testInfo, `${role}-period-submitted`)
    })
  }

  // 登録の結果が実際に保存されている（担任が画面から提出した ABSENT が、ADMIN の一覧で読める）
  test('AC-8 R: 画面から提出した日次点呼が保存されている（ADMIN の一覧 API で ABSENT・SICK）', async () => {
    const { page } = await session('admin')
    const res = await api(page, 'GET', `/teams/${teamSlug}/attendance/daily?date=${TODAY}`)
    const body = (await res.json()) as { data: { records: { studentUserId: number; status: string; absenceReason?: string }[] } }
    const rec = body.data.records.find((r) => r.studentUserId === STUDENT_USER_ID)
    expect(rec, JSON.stringify(body)).toBeTruthy()
    expect(rec).toMatchObject({ status: 'ABSENT', absenceReason: 'SICK' })
  })

  // ---------------------------------------------------------------------------------------------
  // 権限なしロール: 4 画面は「権限がありません」。操作ボタンは出ない。一覧 API も呼ばない。BE も 403。
  // ---------------------------------------------------------------------------------------------
  for (const { role, label } of DENIED_ROLES) {
    test(`AC-1/6/7/8/18 拒否: ${label} は 4 画面すべてで「権限がありません」・操作ボタン無し・一覧 API 未呼出・BE は 403`, async ({}, testInfo) => {
      const { page } = await session(role)

      for (const sub of ['daily-roll-call', 'period-attendance', 'transition-alerts', 'statistics']) {
        const v = await visit(page, sub)
        expect(v.status, `${sub} の権限照会（権限なしは 200 の全 false）`).toBe(200)
        expect(v.perm.data).toMatchObject({ canView: false, canRecordDaily: false, canRecordPeriod: false })
        const panel = page.getByTestId('school-attendance-forbidden')
        await expect(panel, `${sub}: ${await bodyText(page)}`).toBeVisible()
        await expect(panel).toContainText(FORBIDDEN_TITLE)
        // 操作ボタン・一覧が出ない
        await expect(page.getByTestId('daily-roll-call-submit')).toHaveCount(0)
        await expect(page.getByTestId('period-attendance-submit')).toHaveCount(0)
        await expect(page.getByTestId('statistics-export-csv')).toHaveCount(0)
        await expect(page.locator('[data-testid^="roll-call-row-"], [data-testid^="period-row-"], [data-testid="transition-alert-list"]')).toHaveCount(0)
        // 照会の結論が出るまで一覧 API を呼ばない（fail-closed）
        expect(v.listCalls, `${sub}: 一覧 API が呼ばれた`).toEqual([])
        if (sub === 'daily-roll-call') await shot(page, testInfo, `${role}-forbidden`)
      }

      // BE への直打ち: 閲覧 6 種と登録 3 種はすべて 403（COMMON_002）。
      const probes: [string, string, unknown?][] = [
        ['GET', `/teams/${teamSlug}/attendance/daily?date=${TODAY}`],
        ['GET', `/teams/${teamSlug}/attendance/periods?date=${TODAY}&periodNumber=1`],
        ['GET', `/teams/${teamSlug}/attendance/periods/1/candidates?date=${TODAY}`],
        ['GET', `/teams/${teamSlug}/attendance/transition-alerts?date=${TODAY}`],
        ['GET', `/teams/${teamSlug}/attendance/statistics/monthly?year=${TODAY.slice(0, 4)}&month=${Number(TODAY.slice(5, 7))}`],
        ['GET', `/teams/${teamSlug}/attendance/export?from=${jstDate(-30)}&to=${TODAY}`],
        ['POST', `/teams/${teamSlug}/attendance/daily/roll-call`, { attendanceDate: TODAY, entries: [{ studentUserId: STUDENT_USER_ID, status: 'ATTENDING', comment: MARKER }] }],
        ['POST', `/teams/${teamSlug}/attendance/periods/1`, { attendanceDate: TODAY, entries: [{ studentUserId: STUDENT_USER_ID, status: 'ATTENDING', comment: MARKER }] }],
      ]
      for (const [method, path, data] of probes) {
        const res = await api(page, method, path, data)
        const text = await res.text()
        expect(res.status(), `${role} ${method} ${path}: ${text}`).toBe(403)
        expect(JSON.parse(text).error.code).toBe('COMMON_002')
        expect(text, '403 本文に生徒情報が含まれてはならない').not.toContain(String(STUDENT_USER_ID) + ',')
      }
    })
  }

  test('AC-6/7 他テナント: 他テナント O の ADMIN は自チーム O では閲覧できるが、T は不可（陽性対照）。存在しないチーム ID も同じ 403', async ({}, testInfo) => {
    const { page } = await session('otherAdmin')
    const own = await api(page, 'GET', `/teams/${otherSlug}/attendance/permissions`)
    expect((await own.json()).data).toMatchObject({ canView: true, canRecordDaily: true })
    const cross = await api(page, 'GET', `/teams/${teamSlug}/attendance/permissions`)
    expect((await cross.json()).data).toMatchObject({ canView: false, canRecordDaily: false, canRecordPeriod: false })
    const ownList = await api(page, 'GET', `/teams/${otherSlug}/attendance/daily?date=${TODAY}`)
    expect(ownList.status()).toBe(200)
    // 存在しないチーム ID・他テナントの T・自分が非教員の既存 T は、ステータスとエラーコードが同一（存在オラクル無し。AC-7）。
    // 注: 存在しない「スラッグ」は FE/BE 共通のスラッグ解決が先に 404（COMMON_005）を返す（プラットフォーム共通・認可の外）。
    //     スラッグは公開識別子のため本 AC の対象外とし、観測値を annotation に残す。
    const ghost = await api(page, 'GET', `/teams/2000000000/attendance/daily?date=${TODAY}`)
    const crossList = await api(page, 'GET', `/teams/${teamNumericId}/attendance/daily?date=${TODAY}`)
    const member = (await session('member')).page
    const memberList = await api(member, 'GET', `/teams/${teamNumericId}/attendance/daily?date=${TODAY}`)
    const ghostSlug = await api(page, 'GET', `/teams/no-such-team-${STAMP.slice(-9)}/attendance/daily?date=${TODAY}`)
    testInfo.annotations.push({ type: 'unknown-slug-observation', description: `${ghostSlug.status()}:${(await ghostSlug.json()).error?.code}` })
    const codes = await Promise.all([ghost, crossList, memberList].map(async (r) => `${r.status()}:${(await r.json()).error?.code}`))
    expect(new Set(codes).size, `存在オラクル: ${codes.join(' / ')}`).toBe(1)
    expect(codes[0]).toBe('403:COMMON_002')
  })

  test('AC-8/9 委任者・拒否ロールの登録試行は 1 行も保存されていない（ADMIN の一覧に MARKER が無い）', async () => {
    const { page } = await session('admin')
    const delegate = (await session('delegate')).page
    // 委任者（V だが R でない）の登録は BE も 403
    const res = await api(delegate, 'POST', `/teams/${teamSlug}/attendance/daily/roll-call`, {
      attendanceDate: TODAY,
      entries: [{ studentUserId: STUDENT_USER_ID, status: 'ATTENDING', comment: MARKER }],
    })
    expect(res.status(), await res.text()).toBe(403)
    const pres = await api(delegate, 'POST', `/teams/${teamSlug}/attendance/periods/1`, {
      attendanceDate: TODAY,
      entries: [{ studentUserId: STUDENT_USER_ID, status: 'ATTENDING', comment: MARKER }],
    })
    expect(pres.status(), await pres.text()).toBe(403)

    const daily = await (await api(page, 'GET', `/teams/${teamSlug}/attendance/daily?date=${TODAY}`)).text()
    expect(daily).not.toContain(MARKER)
    const period = await (await api(page, 'GET', `/teams/${teamSlug}/attendance/periods?date=${TODAY}&periodNumber=1`)).text()
    expect(period).not.toContain(MARKER)
  })

  test('AC-9 PATCH: 一般 MEMBER・委任者は日次レコードの個別修正が 403、ADMIN は 200', async () => {
    const admin = (await session('admin')).page
    const list = (await (await api(admin, 'GET', `/teams/${teamSlug}/attendance/daily?date=${TODAY}`)).json()) as { data: { records: { id: number }[] } }
    const recordId = list.data.records[0]?.id
    expect(recordId, "ADMIN の日次一覧に前提レコードが無い").toBeDefined()
    for (const role of ['member', 'delegate'] as Role[]) {
      const res = await api((await session(role)).page, 'PATCH', `/teams/${teamSlug}/attendance/daily/${recordId}`, { comment: MARKER })
      expect(res.status(), `${role}: ${await res.text()}`).toBe(403)
    }
    const ok = await api(admin, 'PATCH', `/teams/${teamSlug}/attendance/daily/${recordId}`, { comment: 'admin-fix' })
    expect(ok.status(), await ok.text()).toBeLessThan(300)
  })

  test('統計・CSV は V のみ: 委任者は 200 の CSV を取得でき、SYSTEM_ADMIN・一般 MEMBER は 403', async () => {
    const q = `from=${jstDate(-30)}&to=${TODAY}`
    const ok = await api((await session('delegate')).page, 'GET', `/teams/${teamSlug}/attendance/export?${q}`)
    expect(ok.status()).toBe(200)
    expect(ok.headers()['content-type']).toContain('csv')
    for (const role of ['sysAdmin', 'member'] as Role[]) {
      const res = await api((await session(role)).page, 'GET', `/teams/${teamSlug}/attendance/export?${q}`)
      expect(res.status(), role).toBe(403)
    }
  })

  // ---------------------------------------------------------------------------------------------
  // 権限照会が失敗したとき: 「権限がない」と区別した再試行 UI を出し、復旧できる。
  // ---------------------------------------------------------------------------------------------
  test('権限照会の失敗: 再試行 UI を出し（権限なしパネルではない）、一覧 API は呼ばず、再試行で復旧する', async ({}, testInfo) => {
    const { page } = await session('homeroom')
    const listCalls: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'GET' && LIST_API.test(r.url()) && !r.url().includes('/me/')) listCalls.push(r.url())
    })
    let failing = true
    await page.route('**/attendance/permissions', async (route) => {
      if (failing) await route.abort('failed')
      else await route.continue()
    })
    await page.goto(`/teams/${teamSlug}/school-attendance/daily-roll-call`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    const err = page.getByTestId('school-attendance-permission-error')
    await expect(err).toBeVisible({ timeout: 30_000 })
    await expect(err).toContainText(PERMISSION_ERROR_TITLE)
    await expect(page.getByTestId('school-attendance-forbidden')).toHaveCount(0)
    await expect(page.getByTestId('daily-roll-call-submit')).toHaveCount(0)
    expect(listCalls, '照会失敗中に一覧 API が呼ばれた').toEqual([])
    await shot(page, testInfo, 'homeroom-permission-error')

    failing = false
    const permP = page.waitForResponse(isPermissions, { timeout: 30_000 })
    await page.getByTestId('school-attendance-permission-error-retry').click()
    expect((await permP).status()).toBe(200)
    await expect(page.getByTestId(`roll-call-row-${STUDENT_USER_ID}`)).toBeVisible({ timeout: 30_000 })
    await expect(err).toHaveCount(0)
    await page.unroute('**/attendance/permissions')
  })

  // ---------------------------------------------------------------------------------------------
  // 非回帰（AC-15）: 生徒本人の自己スコープ画面は権限変更の影響を受けない。
  // ---------------------------------------------------------------------------------------------
  test('AC-15 非回帰: 生徒本人は /me/attendance/timeline を開け、権限なしパネルは出ない', async ({}, testInfo) => {
    const { page } = await session('student')
    const resP = page.waitForResponse((r) => r.url().includes('/me/attendance/timeline'), { timeout: 60_000 })
    await page.goto('/me/attendance/timeline', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    expect((await resP).status()).toBe(200)
    await settled(page)
    await expect(page.getByTestId('school-attendance-forbidden')).toHaveCount(0)
    await expect(page.getByRole('heading', { name: JA.school.timeline.title })).toBeVisible()
    await shot(page, testInfo, 'student-timeline')
  })

  // ---------------------------------------------------------------------------------------------
  // 担任を外れる（AC-5 現役判定）: 担任設定を終了させると、同じ人が画面を開けなくなる。
  // ---------------------------------------------------------------------------------------------
  test('AC-5 現役判定: 担任設定の終了日を昨日にすると、担任・副担任は即座に「権限がありません」になる', async ({}, testInfo) => {
    const admin = (await session('admin')).page
    await mustOk(
      await api(admin, 'PATCH', `/teams/${teamSlug}/homerooms/${homeroomRowId}`, { effectiveUntil: jstDate(-1) }),
      '担任設定の終了',
    )
    for (const role of ['homeroom', 'assistant'] as Role[]) {
      const { page } = await session(role)
      const v = await visit(page, 'daily-roll-call')
      expect(v.perm.data).toMatchObject({ canView: false, canRecordDaily: false, canRecordPeriod: false })
      await expect(page.getByTestId('school-attendance-forbidden')).toBeVisible()
      await expect(page.getByTestId('daily-roll-call-submit')).toHaveCount(0)
      const res = await api(page, 'POST', `/teams/${teamSlug}/attendance/daily/roll-call`, {
        attendanceDate: TODAY,
        entries: [{ studentUserId: STUDENT_USER_ID, status: 'ATTENDING', comment: MARKER }],
      })
      expect(res.status(), `${role} 外れた後の登録`).toBe(403)
      if (role === 'homeroom') await shot(page, testInfo, 'former-homeroom-forbidden')
    }
    // 管理者・委任者は影響を受けない
    const d = await visit((await session('delegate')).page, 'daily-roll-call')
    expect(d.perm.data.canView).toBe(true)
  })
})
