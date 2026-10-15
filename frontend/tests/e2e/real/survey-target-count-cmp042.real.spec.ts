import { expect as baseExpect, request, test, type APIRequestContext, type Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { loginViaApi } from '../fixtures/auth'

// 通常のchromium-realでも専用configと同じ実機の待機境界を使う。
test.setTimeout(360_000)
test.use({ storageState: { cookies: [], origins: [] }, actionTimeout: 20_000, navigationTimeout: 120_000 })
const expect = baseExpect.configure({ timeout: 20_000 })

// APIは認証・自所有組織の前提変更・後始末・永続化確認だけに使用する。
// 公開、回答、未回答者更新、督促の対象操作は実UIを通す。API横取りは行わない。
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const FRONTEND_BASE = process.env.BASE_URL ?? 'http://localhost:3001'
const password = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const accounts = {
  admin: { email: process.env.CMP042_ADMIN_EMAIL ?? 'e2e-user@test.mannschaft.local', password },
  member: { email: process.env.CMP042_MEMBER_EMAIL ?? 'e2e-dummy-6@test.mannschaft.local', password },
  late: { email: process.env.CMP042_LATE_EMAIL ?? 'e2e-outsider@test.mannschaft.local', password },
  outsider: { email: process.env.CMP042_OUTSIDER_EMAIL ?? 'e2e-dummy-5@test.mannschaft.local', password },
}
type Actor = { api: APIRequestContext; id: number }
type Notification = { sourceId: number; notificationType: string }

test('CMP042: 公開分母固定・現在所属の未回答者/督促・ロール境界を実UIで確認', async ({ page, browser }, info) => {
  const actors: Partial<Record<keyof typeof accounts, Actor>> = {}
  let orgSlug = ''
  let surveyId = 0
  let inviteToken = ''
  let verificationFailed = false
  let verificationError: unknown
  const cleanupFailures: Array<{ target: string; error: string }> = []
  const cleanupResults: Array<{ target: string; completed: boolean; status?: number }> = []
  const ownedContexts: Awaited<ReturnType<typeof browser.newContext>>[] = []
  const authPages: Partial<Record<keyof typeof accounts, Page>> = { admin: page }
  async function screenshot(target: Page, name: string) {
    const file = info.outputPath(`${name}.png`)
    await target.screenshot({ path: file, fullPage: true })
    await info.attach(name, { path: file, contentType: 'image/png' })
  }
  async function apiOk(response: Awaited<ReturnType<APIRequestContext['get']>>, label: string) {
    expect(response.ok(), `${label}: HTTP ${response.status()}`).toBeTruthy()
  }
  async function join(actor: Actor) {
    const response = await actor.api.post(`/api/v1/invite/${inviteToken}/join`)
    await apiOk(response, '自所有組織のMEMBER招待参加')
  }
  async function detail() {
    const response = await actors.admin!.api.get(`/api/v1/organizations/${orgSlug}/surveys/${surveyId}`)
    await apiOk(response, '対象人数永続化確認')
    return (await response.json()).data as { stats: { targetCount: number }; status: string }
  }
  async function openRespondents() {
    const toggle = page.getByTestId('survey-respondents-toggle')
    if (!(await page.getByTestId('survey-respondents-list').isVisible())) await toggle.click()
    await expect(page.getByTestId('respondents-summary')).toBeVisible()
    await page.getByTestId('respondents-filter').getByRole('button', { name: /未回答/ }).click()
  }
  try {
    for (const [key, credentials] of Object.entries(accounts) as Array<[keyof typeof accounts, typeof accounts.admin]>) {
      let actorPage = page
      if (key !== 'admin') {
        const context = await browser.newContext({ baseURL: FRONTEND_BASE, storageState: { cookies: [], origins: [] }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
        ownedContexts.push(context)
        actorPage = await context.newPage()
        actorPage.setDefaultTimeout(20_000)
        actorPage.setDefaultNavigationTimeout(120_000)
        authPages[key] = actorPage
      }
      await loginViaApi(actorPage, credentials, { apiBaseUrl: API_BASE, deferNavigation: true })
      const token = (await actorPage.context().cookies()).find(cookie => cookie.name === 'access_token')?.value
      expect(token, `${key} fixture認証Cookie`).toBeTruthy()
      const api = await request.newContext({ baseURL: API_BASE, extraHTTPHeaders: { Authorization: `Bearer ${token}` } })
      actors[key] = { api, id: 0 }
      const me = await api.get('/api/v1/users/me')
      await apiOk(me, `${key} fixture本人確認`)
      const identity = (await me.json()).data as { id: number; email: string; systemRole: string | null }
      expect(identity.email).toBe(credentials.email)
      expect(identity.email, '送信先は既存の架空test.mannschaft.local fixtureに限定').toMatch(/@test\.mannschaft\.local$/)
      expect(identity.systemRole, `${key}はシステム管理者ではない`).not.toBe('SYSTEM_ADMIN')
      actors[key] = { api, id: identity.id }
    }
    const suffix = Date.now().toString(36)
    const createOrg = await actors.admin!.api.post('/api/v1/organizations', {
      data: { name: `CMP042 実機 ${suffix}`, slug: `cmp042-${suffix}`, orgType: 'OTHER', visibility: 'PRIVATE' },
    })
    await apiOk(createOrg, '自所有検証組織の作成')
    orgSlug = ((await createOrg.json()).data as { slug: string }).slug
    expect(orgSlug).toMatch(/^cmp042-/)
    const adminPermissions = await actors.admin!.api.get(`/api/v1/organizations/${orgSlug}/me/permissions`)
    await apiOk(adminPermissions, '自所有組織のADMINロール確認')
    expect(((await adminPermissions.json()).data as { roleName: string }).roleName).toBe('ADMIN')
    const invite = await actors.admin!.api.post(`/api/v1/organizations/${orgSlug}/invite-tokens`, {
      data: { roleId: 4, expiresIn: '1d', maxUses: 2 },
    })
    await apiOk(invite, '自所有組織の限定招待作成')
    const inviteBody = (await invite.json()).data as { token: string; roleName: string }
    expect(inviteBody.roleName).toBe('MEMBER')
    inviteToken = inviteBody.token
    await join(actors.member!)
    const memberPermissions = await actors.member!.api.get(`/api/v1/organizations/${orgSlug}/me/permissions`)
    await apiOk(memberPermissions, '自所有組織内の一般メンバーロール確認')
    const memberScope = (await memberPermissions.json()).data as { roleName: string; permissions: string[] }
    expect(memberScope.roleName).toBe('MEMBER')
    expect(memberScope.permissions).not.toContain('MANAGE_SURVEYS')
    const outsiderPermissions = await actors.outsider!.api.get(`/api/v1/organizations/${orgSlug}/me/permissions`)
    if (outsiderPermissions.ok()) {
      const outsiderScope = (await outsiderPermissions.json()).data as { roleName: string | null; permissions: string[] }
      expect(outsiderScope.roleName).toBeNull()
      expect(outsiderScope.permissions).not.toContain('MANAGE_SURVEYS')
    } else {
      expect([403, 404], '非所属者の自所有組織権限照会は403/404').toContain(outsiderPermissions.status())
    }
    const createSurvey = await actors.admin!.api.post(`/api/v1/organizations/${orgSlug}/surveys`, {
      data: {
        title: `CMP042 固定分母 ${suffix}`, description: '自所有実機検証。完了後削除。',
        isAnonymous: false, allowMultipleSubmissions: false, distributionMode: 'ALL',
        resultsVisibility: 'ADMINS_ONLY',
        questions: [{ questionType: 'FREE_TEXT', questionText: 'ひとこと', isRequired: true, displayOrder: 1 }],
      },
    })
    await apiOk(createSurvey, '公開前アンケート前提作成')
    surveyId = ((await createSurvey.json()).data as { id: number }).id
    const url = `/surveys/${surveyId}?scope=organization&scopeId=${orgSlug}`
    await page.goto(url, { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('survey-mode-draft')).toBeVisible({ timeout: 90_000 })
    const publish = page.waitForResponse(r => r.url().includes(`/surveys/${surveyId}/publish`) && r.request().method() === 'POST')
    await page.getByTestId('survey-publish-button').click()
    expect((await publish).status()).toBe(200)
    await expect(page.getByText('0 / 2', { exact: false })).toBeVisible()
    expect((await detail()).stats.targetCount).toBe(2)
    await screenshot(page, '01-published-denominator-two')

    const memberPage = authPages.member!
    await memberPage.goto(url, { waitUntil: 'domcontentloaded' })
    await expect(memberPage.getByTestId('survey-response-form')).toBeVisible()
    await expect(memberPage.getByTestId('survey-close-button')).toHaveCount(0)
    await expect(memberPage.getByTestId('survey-respondents-section')).toHaveCount(0)
    await expect(memberPage.getByTestId('respondents-remind-button')).toHaveCount(0)
    await screenshot(memberPage, '02-member-no-management')
    const outsiderPage = authPages.outsider!
    await outsiderPage.goto(url, { waitUntil: 'domcontentloaded' })
    await expect(outsiderPage.getByText('アンケート情報を取得できませんでした', { exact: false })).toBeVisible()
    await expect(outsiderPage.getByTestId('survey-publish-button')).toHaveCount(0)
    await screenshot(outsiderPage, '03-outsider-direct-url-denied')

    await join(actors.late!)
    await openRespondents()
    await page.getByTestId('respondents-refresh').click()
    await expect(page.getByTestId(`respondent-item-${actors.late!.id}`)).toBeVisible()
    await expect(page.getByTestId('respondents-summary')).toContainText('3')
    await expect(page.getByText('0 / 2', { exact: false })).toBeVisible()
    expect((await detail()).stats.targetCount).toBe(2)
    await screenshot(page, '04-current-members-three-denominator-two')

    const remove = await actors.admin!.api.delete(`/api/v1/organizations/${orgSlug}/members/${actors.member!.id}`)
    await apiOk(remove, '自所有検証組織から元メンバーを除外')
    await page.getByTestId('respondents-refresh').click()
    await expect(page.getByTestId(`respondent-item-${actors.member!.id}`)).toHaveCount(0)
    await expect(page.getByTestId(`respondent-item-${actors.late!.id}`)).toBeVisible()
    expect((await detail()).stats.targetCount).toBe(2)
    await screenshot(page, '05-leaver-removed-denominator-fixed')
    const reminder = page.waitForResponse(r => r.url().includes(`/surveys/${surveyId}/remind`) && r.request().method() === 'POST')
    await page.getByTestId('respondents-remind-button').click()
    const reminderResponse = await reminder
    expect(reminderResponse.status()).toBe(200)
    expect(((await reminderResponse.json()).data as { remindedCount: number }).remindedCount).toBe(2)
    await expect.poll(async () => {
      const response = await actors.late!.api.get('/api/v1/notifications?size=100')
      await apiOk(response, '新規所属者の督促通知永続化')
      const notifications = (await response.json()).data as Notification[]
      return notifications.filter(n => n.sourceId === surveyId && n.notificationType === 'SURVEY_RESPONSE_REMINDER').length
    }).toBe(1)
    const leftNotifications = await actors.member!.api.get('/api/v1/notifications?size=100')
    await apiOk(leftNotifications, '退会者の督促なし確認')
    expect(((await leftNotifications.json()).data as Notification[]).filter(n => n.sourceId === surveyId && n.notificationType === 'SURVEY_RESPONSE_REMINDER')).toHaveLength(0)
    await screenshot(page, '06-reminder-current-members')
    const latePage = authPages.late!
    await latePage.goto(url, { waitUntil: 'domcontentloaded' })
    await expect(latePage.getByTestId('survey-response-form')).toBeVisible({ timeout: 90_000 })
    await latePage.locator('[data-testid^="response-text-"]').fill('公開後加入者の実UI回答')
    const answer = latePage.waitForResponse(r => r.url().includes(`/surveys/${surveyId}/responses`) && r.request().method() === 'POST')
    await latePage.getByTestId('survey-response-submit').click()
    expect((await answer).status()).toBe(201)
    await expect(latePage.getByTestId('survey-already-responded')).toBeVisible()
    await screenshot(latePage, '07-late-member-ui-answer')
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.getByText('1 / 2', { exact: false })).toBeVisible()
    expect((await detail()).stats.targetCount).toBe(2)
    await screenshot(page, '08-answer-denominator-fixed')
    const summaryPath = info.outputPath('cmp042-summary.json')
    await writeFile(summaryPath, JSON.stringify({ orgSlug, surveyId, snapshot: 2, afterJoin: 3, afterLeave: 2, reminderCount: 2, uiOperations: ['publish', 'respondents-refresh', 'remind', 'response-submit'] }, null, 2), 'utf8')
    await info.attach('cmp042-summary', { path: summaryPath, contentType: 'application/json' })
  } catch (error) {
    verificationFailed = true
    verificationError = error
  } finally {
    async function cleanup(target: string, action: () => Promise<unknown>) {
      try {
        const status = await action()
        cleanupResults.push({ target, completed: true, ...(typeof status === 'number' ? { status } : {}) })
      } catch (error) {
        cleanupFailures.push({ target, error: error instanceof Error ? error.message : String(error) })
        cleanupResults.push({ target, completed: false })
      }
    }
    if (surveyId && orgSlug && actors.admin) {
      await cleanup(`survey:${surveyId}`, async () => {
        const deletion = await actors.admin!.api.delete(`/api/v1/organizations/${orgSlug}/surveys/${surveyId}`)
        await apiOk(deletion, '自所有アンケート後片付け')
        return deletion.status()
      })
    }
    if (orgSlug && actors.admin) {
      await cleanup(`organization:${orgSlug}`, async () => {
        const deletion = await actors.admin!.api.delete(`/api/v1/organizations/${orgSlug}`)
        await apiOk(deletion, '自所有組織後片付け')
        return deletion.status()
      })
    }
    for (const [index, context] of ownedContexts.entries()) await cleanup(`browser-context:${index}`, () => context.close())
    for (const [key, actor] of Object.entries(actors)) await cleanup(`api-context:${key}`, () => actor.api.dispose())
    const cleanupPath = info.outputPath('cmp042-cleanup.json')
    await cleanup('cleanup-proof', async () => {
      await writeFile(cleanupPath, JSON.stringify({ orgSlug, surveyId, results: cleanupResults, failures: cleanupFailures.map(({ target }) => ({ target })) }, null, 2), 'utf8')
      await info.attach('cmp042-cleanup', { path: cleanupPath, contentType: 'application/json' })
    })
  }
  // 元の検証失敗は上書きせず保持する。検証成功でも後始末失敗なら合格にしない。
  if (verificationFailed) throw verificationError
  if (cleanupFailures.length) throw new Error(`CMP042後片付け失敗: ${JSON.stringify(cleanupFailures)}`)
})
