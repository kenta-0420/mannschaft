import { test, expect, type Browser, type Page, type Response } from '@playwright/test'
import dayjs from 'dayjs'
import JA from '../../../app/locales/ja/school.json' with { type: 'json' }
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * 実機E2E: CMP-260930-1532 欠席理由 enum を BE AbsenceReason（8値）へ一致させた修正。
 * 修正前は SICK / FAMILY_REASON を選ぶと提出が 400 になっていた。
 *
 * 前提（開発DB・実 BE/FE。CI 対象外）:
 *   - ADMIN = e2e-admin(24): team-000092 の ADMIN（= 教員相当・担任）
 *   - USER  = e2e-user(23): team-000092 の MEMBER、user 4 の ACTIVE な見守り者（親）、team 898 に非所属
 *   - 生徒 = user 4。日次レコードは API で前提作成する（画面に作成の入口が無い。欠陥として別途報告）
 *   - 資格情報は frontend/.env.test（TEST_*）から読む。値は spec に書かない。
 *
 * 実行: BASE_URL=http://localhost:3003 API_BASE_URL=http://localhost:8080 npx playwright test -c playwright-real.config.ts cmp-260930-1532 --project=chromium-real --no-deps
 */
test.use({ launchOptions: { args: [] } })
test.setTimeout(180_000)

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const TEAM_ID = 92
const TEAM_SLUG = 'team-000092'
const OTHER_TEAM_SLUG = 'alicization-shift-20260916'
const STUDENT_ID = 4
const TODAY = dayjs().format('YYYY-MM-DD')
const REASONS = JA.school.attendance.absenceReason
const NOTICE_REASONS = JA.school.familyNotice.reason
const REASON_KEYS = Object.keys(REASONS) as (keyof typeof REASONS)[]

const userCred = { email: process.env.TEST_USER_EMAIL ?? '', password: process.env.TEST_USER_PASSWORD ?? '' }
const studentCred = { email: 'e2e-dummy-2@test.mannschaft.local' } // 生徒 user 4（パスワードは userCred と共通のシード値）
const adminCred = { email: process.env.TEST_ADMIN_EMAIL ?? '', password: process.env.TEST_ADMIN_PASSWORD ?? '' }

async function newPage(browser: Browser, cred: { email: string; password: string }): Promise<Page> {
  const ctx = await browser.newContext({ storageState: undefined, baseURL: process.env.BASE_URL ?? 'http://localhost:3003', locale: 'ja-JP' })
  const page = await ctx.newPage()
  await loginViaApi(page, cred, { apiBaseUrl: API })
  return page
}

/** PrimeVue Select を開いて選択肢のラベル一覧を返す（開いたままにする）。 */
async function openSelect(page: Page, select: ReturnType<Page['locator']>): Promise<string[]> {
  await select.click()
  const options = page.getByRole('option')
  await expect(options.first()).toBeVisible()
  return (await options.allTextContents()).map((s) => s.trim())
}

async function expectNoRawKeys(page: Page) {
  const body = await page.locator('body').innerText()
  expect(body, 'i18n 生キーが画面に出ている').not.toMatch(/school\.(attendance|familyNotice)\./)
}

async function bodyText(page: Page, n = 200): Promise<string> {
  return (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, n)
}


/**
 * 提出 API を 1.5 秒遅延させてから実 BE へそのまま通す（応答は改変しない。モックではなく遅延の注入のみ）。
 * 実 BE は数十 ms で返すため、「提出中」の UI 状態を確実に観測するために使う。
 */
async function delaySubmit(page: Page, urlPart: string) {
  await page.route(`**${urlPart}*`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    await new Promise((r) => setTimeout(r, 1500))
    await route.continue()
  })
}

const isRollCall = (r: Response) => r.url().includes('/attendance/daily/roll-call') && r.request().method() === 'POST'
const isPeriodPost = (r: Response) => /\/attendance\/periods\/\d+(\?.*)?$/.test(r.url()) && r.request().method() === 'POST'
const isNoticePost = (r: Response) => r.url().includes('/me/attendance/notices') && r.request().method() === 'POST'

test.describe.serial('CMP-260930-1532 欠席理由 実機E2E', () => {
  test.beforeAll(async ({ browser }) => {
    // 前提データ: 画面に日次レコードを作る入口が無いため API で作る（対象操作ではない）。
    const admin = await newPage(browser, adminCred)
    // 認可是正（CMP-260930-0230）以降、roll-call は「そのクラスの在籍メンバー」だけを受け付ける（SCHOOL_STUDENT_NOT_ENROLLED）。
    // 生徒 user 4 が team 92 の MEMBER でなければ、招待トークン経由（実プロダクト経路）で参加させる。
    const membersRes = await admin.request.get(`${API}/api/v1/teams/${TEAM_SLUG}/members/all`)
    expect(membersRes.status(), await membersRes.text()).toBe(200)
    const enrolled = ((await membersRes.json()).data as { userId: number }[]).some((m) => m.userId === STUDENT_ID)
    if (!enrolled) {
      const tokRes = await admin.request.post(`${API}/api/v1/teams/${TEAM_SLUG}/invite-tokens`, { data: { roleId: 4, expiresIn: '1d', maxUses: 1 } })
      expect(tokRes.status(), await tokRes.text()).toBeLessThan(300)
      const inviteToken = (await tokRes.json()).data.token as string
      const student = await newPage(browser, { email: studentCred.email, password: userCred.password })
      const joinRes = await student.request.post(`${API}/api/v1/invite/${inviteToken}/join`, { data: {} })
      expect(joinRes.status(), await joinRes.text()).toBeLessThan(300)
      await student.context().close()
    }
    const res = await admin.request.post(`${API}/api/v1/teams/${TEAM_ID}/attendance/daily/roll-call`, {
      data: { attendanceDate: TODAY, entries: [{ studentUserId: STUDENT_ID, status: 'UNDECIDED' }] },
    })
    expect(res.status(), await res.text()).toBe(201)
    // 時限点呼の候補生徒は「当該時限の既存レコード」から作られる（BE 簡易実装）ため、1 時限目のレコードも API で前提作成する。
    const pres = await admin.request.post(`${API}/api/v1/teams/${TEAM_ID}/attendance/periods/1`, {
      data: { attendanceDate: TODAY, entries: [{ studentUserId: STUDENT_ID, status: 'ATTENDING' }] },
    })
    expect(pres.status(), await pres.text()).toBeLessThan(300)
    await admin.context().close()
  })

  test('AC1/4 日次点呼: 8選択肢・SICK と FAMILY_REASON が 201・提出中は無効化・再読込で保持', async ({ browser }) => {
    const page = await newPage(browser, adminCred)
    await page.goto(`/teams/${TEAM_SLUG}/school-attendance/daily-roll-call`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await delaySubmit(page, '/attendance/daily/roll-call')
    const row = page.getByTestId(`roll-call-row-${STUDENT_ID}`)
    await expect(row).toBeVisible({ timeout: 30_000 })
    await page.getByTestId(`roll-call-row-${STUDENT_ID}-absent`).click()

    const select = row.locator('.p-select')
    const labels = await openSelect(page, select)
    expect(labels).toEqual(REASON_KEYS.map((k) => REASONS[k]))
    await page.keyboard.press('Escape')

    for (const key of ['SICK', 'FAMILY_REASON'] as const) {
      await page.getByTestId(`roll-call-row-${STUDENT_ID}`).locator('.p-select').click()
      await page.getByRole('option', { name: REASONS[key], exact: true }).click()
      const submit = page.getByTestId('daily-roll-call-submit')
      const resP = page.waitForResponse(isRollCall, { timeout: 20_000 })
      await submit.click()
      await expect(submit).toBeDisabled() // AC4: クリック直後
      const res = await resP
      expect(res.status(), await res.text()).toBe(201)
      expect(res.request().postDataJSON().entries[0].absenceReason).toBe(key)
      await expect(page.getByText(JA.school.attendance.dailyRollCall.submitSuccess).first()).toBeVisible()
      await expect(page.getByTestId('daily-roll-call-summary')).toBeVisible()

      await page.reload({ waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      const rowAfter = page.getByTestId(`roll-call-row-${STUDENT_ID}`)
      await expect(rowAfter).toHaveAttribute('data-status', 'ABSENT', { timeout: 30_000 })
      await expect(rowAfter.locator('.p-select')).toContainText(REASONS[key])
      await expectNoRawKeys(page)
    }
    await page.context().close()
  })

  test('AC2 時限点呼: 欠席理由の選択 UI が無く、送信に absenceReason が含まれない（period_attendance_records に理由列は無い）', async ({ browser }) => {
    const page = await newPage(browser, adminCred)
    await page.goto(`/teams/${TEAM_SLUG}/school-attendance/period-attendance`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await delaySubmit(page, '/attendance/periods/1')
    const row = page.getByTestId(`period-row-${STUDENT_ID}`)
    await expect(row).toBeVisible({ timeout: 30_000 })
    await page.getByTestId(`period-row-${STUDENT_ID}-absent`).click()
    // 欠席にしても理由の Select は出ない
    await expect(row.locator('.p-select')).toHaveCount(0)

    const submit = page.getByTestId('period-attendance-submit')
    const resP = page.waitForResponse(isPeriodPost, { timeout: 20_000 })
    await submit.click()
    await expect(submit).toBeDisabled()
    const res = await resP
    expect(res.status(), await res.text()).toBeLessThan(300)
    const sent = res.request().postDataJSON().entries[0]
    expect(sent.status).toBe('ABSENT')
    expect('absenceReason' in sent).toBe(false)
    await expect(page.getByText(JA.school.attendance.period.submitSuccess).first()).toBeVisible()
    await expectNoRawKeys(page)
    await page.context().close()
  })

  test('AC3/4 保護者の欠席連絡: 8選択肢・201・先生の受信一覧と me 履歴で日本語ラベル', async ({ browser }) => {
    const parent = await newPage(browser, userCred)
    await delaySubmit(parent, '/me/attendance/notices')
    await parent.goto(`/me/attendance/notices?teamId=${TEAM_ID}&studentUserId=${STUDENT_ID}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(parent)
    await expect(parent.getByTestId('family-notice-form')).toBeVisible({ timeout: 30_000 })
    const select = parent.getByTestId('family-notice-reason')
    const labels = await openSelect(parent, select)
    expect(labels).toEqual(REASON_KEYS.map((k) => NOTICE_REASONS[k]))
    await parent.getByRole('option', { name: NOTICE_REASONS.FAMILY_REASON, exact: true }).click()

    const submit = parent.getByTestId('family-notice-submit')
    const resP = parent.waitForResponse(isNoticePost, { timeout: 20_000 })
    await submit.click()
    // 提出中は disabled 属性が付き（二重送信防止）、PrimeVue の提出中表示も出る。
    await expect(submit).toBeDisabled()
    await expect(submit).toHaveAttribute('data-p-disabled', 'true')
    await expect(submit).toHaveClass(/p-button-loading/)
    const res = await resP
    expect(res.status(), await res.text()).toBe(201)
    expect(res.request().postDataJSON().reason).toBe('FAMILY_REASON')
    await expect(parent.getByTestId('family-notice-success')).toBeVisible()
    // 履歴: 生キーでなく日本語ラベル
    await expect(parent.getByText(NOTICE_REASONS.FAMILY_REASON).first()).toBeVisible({ timeout: 20_000 })
    await expectNoRawKeys(parent)
    await expect(parent.locator('body')).not.toContainText('FAMILY_REASON')
    await parent.context().close()

    const teacher = await newPage(browser, adminCred)
    await teacher.goto(`/teams/${TEAM_SLUG}/school-attendance/notices`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(teacher)
    const list = teacher.getByTestId('teacher-notice-list')
    await expect(list).toBeVisible({ timeout: 30_000 })
    await expect(list.getByText(NOTICE_REASONS.FAMILY_REASON).first()).toBeVisible()
    await expect(list).not.toContainText('FAMILY_REASON')
    await expectNoRawKeys(teacher)
    await teacher.context().close()
  })

  test('AC5 失敗時: 見守り関係の無い生徒への連絡は 403 で、画面にエラーが出る（実 BE の拒否）', async ({ browser }, testInfo) => {
    const parent = await newPage(browser, userCred)
    await parent.goto(`/me/attendance/notices?teamId=${TEAM_ID}&studentUserId=${STUDENT_ID + 1000}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(parent)
    await expect(parent.getByTestId('family-notice-form')).toBeVisible({ timeout: 30_000 })
    await parent.getByTestId('family-notice-reason').click()
    await parent.getByRole('option', { name: NOTICE_REASONS.SICK, exact: true }).click()
    const resP = parent.waitForResponse(isNoticePost, { timeout: 20_000 })
    await parent.getByTestId('family-notice-submit').click()
    const res = await resP
    expect(res.status()).toBe(403)
    await expect(parent.getByTestId('family-notice-success')).not.toBeVisible()
    // 失敗トースト（PrimeVue Toast）に BE のエラー内容（拒否理由）が出ること。「保護者連絡」の一語だけでは不可。
    const toast = parent.locator('.p-toast-message')
    await expect(toast.first()).toBeVisible({ timeout: 10_000 })
    const toastText = (await toast.first().innerText()).replace(/\s+/g, ' ')
    testInfo.annotations.push({ type: 'ac5-error-toast', description: toastText })
    const resBody = await res.json()
    expect(resBody?.error?.message, 'BE がエラー内容を返している').toBeTruthy()
    expect(toastText).toContain(resBody.error.message)
    await parent.context().close()
  })

  test('AC6 ロール横断: 一般メンバー／他チーム（画面は権限なし表示・BE の登録は 403）', async ({ browser }, testInfo) => {
    // 一般メンバー(MEMBER) — 日次点呼画面を URL 直打ち。CMP-261001-0630/0230 以降は「権限がありません」で行も提出ボタンも出ない。
    const member = await newPage(browser, userCred)
    const rollCallListCalls: string[] = []
    member.on('request', (r) => {
      if (r.method() === 'GET' && r.url().includes(`/teams/${TEAM_SLUG}/attendance/daily?`)) rollCallListCalls.push(r.url())
    })
    await member.goto(`/teams/${TEAM_SLUG}/school-attendance/daily-roll-call`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(member)
    await expect(member.getByTestId('school-attendance-forbidden')).toBeVisible({ timeout: 30_000 })
    await expect(member.getByTestId('school-attendance-forbidden')).toContainText(JA.school.attendance.forbidden.title)
    testInfo.annotations.push({ type: 'member-daily-page', description: await bodyText(member) })
    await expect(member.getByTestId(`roll-call-row-${STUDENT_ID}`)).toHaveCount(0)
    await expect(member.getByTestId('daily-roll-call-submit')).toHaveCount(0)
    expect(rollCallListCalls, '権限なしの間は一覧 API を呼ばない').toEqual([])
    // 画面が入口を出さない操作を BE が拒否すること: MEMBER の roll-call POST は 403（かつ何も保存されない）
    const memberPost = await member.request.post(`${API}/api/v1/teams/${TEAM_ID}/attendance/daily/roll-call`, {
      data: { attendanceDate: TODAY, entries: [{ studentUserId: STUDENT_ID, status: 'ATTENDING', comment: 'member-denied' }] },
    })
    testInfo.annotations.push({ type: 'member-roll-call-post-status', description: String(memberPost.status()) })
    expect(memberPost.status(), await memberPost.text()).toBe(403)
    // 一般メンバーは先生用の受信一覧を開けない（BE 403）
    await member.goto(`/teams/${TEAM_SLUG}/school-attendance/notices`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(member)
    await member.waitForTimeout(3000)
    await expect(member.locator('[data-testid^="teacher-notice-item-"]')).toHaveCount(0)
    testInfo.annotations.push({ type: 'member-notices-page', description: await bodyText(member) })
    await expectNoRawKeys(member)

    // 他チーム: e2e-user は team 898 の非所属 — 権限なし表示で、一覧も登録もできない（BE も 403）
    const permP = member.waitForResponse((r) => r.url().includes(`/teams/${OTHER_TEAM_SLUG}/attendance/permissions`), { timeout: 60_000 })
    await member.goto(`/teams/${OTHER_TEAM_SLUG}/school-attendance/daily-roll-call`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(member)
    const perm = await permP
    expect(perm.status()).toBe(200)
    expect((await perm.json()).data).toMatchObject({ canView: false, canRecordDaily: false, canRecordPeriod: false })
    await expect(member.getByTestId('school-attendance-forbidden')).toBeVisible()
    await expect(member.locator('[data-testid^="roll-call-row-"]')).toHaveCount(0)
    await expect(member.getByTestId('daily-roll-call-submit')).toHaveCount(0)
    const otherList = await member.request.get(`${API}/api/v1/teams/${OTHER_TEAM_SLUG}/attendance/daily?date=${TODAY}`)
    expect(otherList.status()).toBe(403)
    testInfo.annotations.push({ type: 'other-team-page', description: await bodyText(member) })
    await member.context().close()
  })
})
