import { test, expect, type Page, type TestInfo } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { AttendanceResultsFixture, REASON, type AttendanceScope } from '../fixtures/attendance-results'

/**
 * CMP-261024-1830 実機回帰。API横取りなし。
 * AC対応: 人数(PARTIAL合算)/理由/未回答区別、見出し→一覧・予定行→詳細、
 * 両scope/一般所属者/非所属/別tenant直打ち、±30日/0件/null理由/390px。
 * API補助は既存認証helperと前提fixture/cleanupだけに限定する。
 */
test.describe('出席確認状況の実機回帰', () => {
  test.describe.configure({ mode: 'default' })
  test.setTimeout(180_000)
  let fixture: AttendanceResultsFixture
  let manifestPath: string

  test.beforeAll(async ({ browser }) => {
    fixture = new AttendanceResultsFixture(browser)
    const directory = process.env.ATTENDANCE_EVIDENCE_DIR ?? test.info().outputPath('attendance-evidence')
    await mkdir(directory, { recursive: true })
    manifestPath = path.join(directory, `fixture-${fixture.stamp}.json`)
    try { await fixture.setup() }
    finally {
      await writeFile(manifestPath, JSON.stringify({ scopes: fixture.scopes, foreignScopes: fixture.foreignScopes, users: Object.fromEntries(fixture.userIds) }, null, 2), 'utf8')
    }
  })
  test.afterAll(async () => {
    if (!fixture) return
    await fixture.cleanup()
    if (manifestPath) await writeFile(manifestPath, JSON.stringify({ scopes: fixture.scopes, foreignScopes: fixture.foreignScopes, cleanedAt: new Date().toISOString() }, null, 2), 'utf8')
  })
  test.afterEach(async () => {
    const info = test.info()
    if (info.status === info.expectedStatus || !fixture) return
    for (const [role, page] of fixture.pages) {
      if (!page.isClosed()) await page.screenshot({ path: info.outputPath(`failure-${role}.png`), fullPage: true })
    }
  })

  async function evidence(page: Page, info: TestInfo, name: string, values: object): Promise<void> {
    const directory = process.env.ATTENDANCE_EVIDENCE_DIR ?? info.outputPath('attendance-evidence')
    await mkdir(directory, { recursive: true })
    await page.screenshot({ path: path.join(directory, `${name}.png`), fullPage: true })
    await writeFile(path.join(directory, `${name}.json`), JSON.stringify({
      test: info.title, url: page.url(), time: new Date().toISOString(),
      api用途: '認証・前提作成・後始末のみ', ...values,
    }, null, 2), 'utf8')
  }

  async function openWidget(page: Page, scope: AttendanceScope): Promise<void> {
    await page.goto(`/${scope.kind}/${scope.slug}`, { waitUntil: 'domcontentloaded' })
    const button = page.locator('#scope-dashboard-section-button-schedule-activity')
    await expect(button).toBeVisible({ timeout: 60_000 })
    const onboarding = page.getByRole('dialog').filter({ hasText: 'メンバーの権限を初期設定' })
    if (await onboarding.isVisible()) await onboarding.getByRole('button', { name: 'あとで決める', exact: true }).click()
    if (await button.getAttribute('aria-expanded') !== 'true') await button.click()
    await expect(page.locator('[data-widget-key="attendance-results"]')).toBeVisible({ timeout: 30_000 })
  }

  async function counts(page: Page, expected: string[]): Promise<void> {
    await expect(page.getByTestId('attendance-counts').first().locator('span')).toHaveText(expected)
  }

  for (const [kind, label] of [['teams', 'チーム'], ['organizations', '組織']] as const) {
    test(`${label}: 管理者と一般メンバーが人数・一覧・詳細・理由を実画面から確認できる`, async () => {
      const info = test.info()
      const scope = fixture.scopes.find(item => item.kind === kind)!
      const owner = fixture.page('owner')
      const list = `/${kind}/${scope.slug}/schedule-attendance`
      const detail = `${list}/${scope.scheduleIds[0]}`
      const backToList = owner.locator(`a[href="${list}"]`).filter({ hasText: '出席確認一覧' })
      const network: Array<{ url: string; status: number }> = []
      const record = (response: import('@playwright/test').Response) => {
        if (response.url().includes('/schedules')) network.push({ url: response.url(), status: response.status() })
      }
      owner.on('response', record)
      try {
        await openWidget(owner, scope)
        const widget = owner.locator('[data-widget-key="attendance-results"]')
        const row = widget.getByRole('link').filter({ hasText: scope.title })
        await expect(row.getByTestId('attendance-counts').locator('span')).toHaveText(['出席 2', '欠席 1', '未定 2'])
        await expect(widget.getByText(scope.outsideTitle, { exact: true })).toHaveCount(0)
        await expect(widget.getByText('出席確認不要の予定', { exact: true })).toHaveCount(0)
        await evidence(owner, info, `${kind}-dashboard`, { start: `/${kind}/${scope.slug}`, entry: '予定・活動→出席確認状況', counts: ['出席2', '欠席1', '未定2'], network })

        const heading = widget.getByRole('link', { name: '出席確認状況', exact: true })
        await heading.focus()
        await heading.press('Enter')
        await expect(owner).toHaveURL(new RegExp(`${list}$`))
        await expect(owner.getByRole('heading', { name: '出席確認一覧', exact: true })).toBeVisible()
        await owner.getByRole('link').filter({ hasText: scope.title }).click()
        await expect(owner).toHaveURL(new RegExp(`${detail}$`))
        await expect(owner.getByTestId('attendance-reason').filter({ hasText: '仕事のため前半のみ参加' })).toHaveText(REASON)
        await counts(owner, ['出席 2', '欠席 1', '未定 2'])
        const undecidedName = fixture.names.get('undecided')!
        const pendingName = fixture.names.get('pending')!
        await expect(owner.locator('li').filter({ hasText: undecidedName }).filter({ hasText: '勤務予定を確認中' })).toContainText('未定')
        await expect(owner.locator('li').filter({ hasText: pendingName })).toContainText('未回答')
        await expect(owner.getByTestId('attendance-reason')).toHaveCount(3)
        await expect(backToList).toHaveAccessibleName('出席確認一覧')

        await owner.setViewportSize({ width: 390, height: 844 })
        expect(await owner.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
        await evidence(owner, info, `${kind}-detail-mobile`, { start: `/${kind}/${scope.slug}`, clicks: ['出席確認状況', scope.title], final: '理由・未定・未回答', backlink: await backToList.ariaSnapshot(), network })
        await owner.setViewportSize({ width: 1280, height: 900 })

        await backToList.click()
        await owner.getByRole('link').filter({ hasText: scope.emptyTitle }).click()
        await counts(owner, ['出席 0', '欠席 0', '未定 5'])
        await expect(owner.getByTestId('attendance-reason')).toHaveCount(0)
        await backToList.click()
        await owner.getByRole('button', { name: '次の期間', exact: true }).click()
        await expect(owner.getByRole('link').filter({ hasText: scope.outsideTitle })).toBeVisible()
        await owner.getByRole('button', { name: '次の期間', exact: true }).click()
        await expect(owner.getByText('対象のイベントがありません', { exact: true })).toBeVisible()
        await evidence(owner, info, `${kind}-empty`, { clicks: ['次の期間', '次の期間'], final: '対象のイベントがありません', network })

        // 予定/人数行のクリックで詳細へ遷移し、元のtoggleExpandへ戻らないこと。
        const member = fixture.page('partial')
        await openWidget(member, scope)
        await member.locator('[data-widget-key="attendance-results"]').getByRole('link').filter({ hasText: scope.title }).getByTestId('attendance-counts').click()
        await expect(member).toHaveURL(new RegExp(`${detail}$`))
        await expect(member.getByTestId('attendance-reason').filter({ hasText: '通院のため欠席' })).toBeVisible()
        await evidence(member, info, `${kind}-member`, { start: `/${kind}/${scope.slug}`, clicks: ['予定行の人数'], final: '一般MEMBERも同じ閲覧範囲' })
        expect(network.some(item => item.status === 200 && item.url.includes('/stats'))).toBe(true)
        expect(network.some(item => item.status === 200 && item.url.includes('/attendances'))).toBe(true)
      } finally { owner.off('response', record) }
    })

    test(`${label}: 非所属と別テナントは一覧・詳細の直打ちでも理由を閲覧できない`, async () => {
      const info = test.info()
      const scope = fixture.scopes.find(item => item.kind === kind)!
      for (const role of ['outsider', 'foreign']) {
        const page = fixture.page(role)
        await page.goto(`/${kind}/${scope.slug}`, { waitUntil: 'domcontentloaded' })
        // 既存PUBLIC既定では外枠は公開される。MEMBERS_ONLYの予定・人数・理由は公開しない。
        await expect(page.locator('body')).toContainText(`出席確認実機${fixture.stamp}`, { timeout: 60_000 })
        const section = page.locator('#scope-dashboard-section-button-schedule-activity')
        if (await section.count() && await section.getAttribute('aria-expanded') !== 'true') await section.click()
        const widget = page.locator('[data-widget-key="attendance-results"]')
        if (await widget.count()) {
          await expect(widget.getByText('対象のイベントがありません', { exact: true })).toBeVisible()
          await expect(widget.getByTestId('attendance-counts')).toHaveCount(0)
        }
        await expect(page.getByText(scope.title, { exact: true })).toHaveCount(0)
        await evidence(page, info, `${kind}-${role}-dashboard`, { start: page.url(), operation: '公開dashboardを画面表示', final: 'PUBLIC既定の外枠のみ、非公開の予定・人数・理由なし' })
        for (const suffix of ['', `/${scope.scheduleIds[0]}`]) {
          await page.goto(`/${kind}/${scope.slug}/schedule-attendance${suffix}`, { waitUntil: 'domcontentloaded' })
          if (suffix) {
            await expect(page.getByTestId('load-error-state').first()).toBeVisible({ timeout: 30_000 })
            await expect(page.getByTestId('load-error-state').first()).toContainText('この画面をご利用いただけません')
          } else await expect(page.getByText('対象のイベントがありません', { exact: true })).toBeVisible({ timeout: 30_000 })
          await expect(page.getByTestId('attendance-reason')).toHaveCount(0)
          await expect(page.getByTestId('attendance-counts')).toHaveCount(0)
          await expect(page.getByText(scope.title, { exact: true })).toHaveCount(0)
          await evidence(page, info, `${kind}-${role}-${suffix ? 'detail' : 'list'}`, { start: page.url(), operation: 'URL直接遷移', final: suffix ? await page.getByTestId('load-error-state').first().innerText() : '対象のイベントがありません' })
        }
      }
      const foreign = fixture.foreignScopes.find(item => item.kind === kind)!
      const owner = fixture.page('owner')
      await owner.goto(`/${kind}/${scope.slug}/schedule-attendance/${foreign.scheduleIds[0]}`, { waitUntil: 'domcontentloaded' })
      await expect(owner.getByTestId('load-error-state').first()).toBeVisible({ timeout: 30_000 })
      await expect(owner.getByText(foreign.title, { exact: true })).toHaveCount(0)
      await expect(owner.getByTestId('attendance-reason')).toHaveCount(0)
      await evidence(owner, info, `${kind}-foreign-id`, { operation: '自所属URLに別tenantの予定IDを直接指定', final: await owner.getByTestId('load-error-state').first().innerText() })
      const anonymous = await fixture.browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost:3001', storageState: { cookies: [], origins: [] } })
      try {
        const page = await anonymous.newPage()
        for (const suffix of ['', `/${scope.scheduleIds[0]}`]) {
          await page.goto(`/${kind}/${scope.slug}/schedule-attendance${suffix}`, { waitUntil: 'domcontentloaded' })
          await expect(page).toHaveURL(/\/login(?:\?|$)/, { timeout: 30_000 })
          await expect(page.getByRole('button', { name: 'ログイン', exact: true })).toBeVisible()
          await expect(page.getByTestId('attendance-reason')).toHaveCount(0)
          await expect(page.getByText(scope.title, { exact: true })).toHaveCount(0)
          await evidence(page, info, `${kind}-anonymous-${suffix ? 'detail' : 'list'}`, { operation: '未認証の一覧/詳細URL直接遷移', final: 'ログイン画面・理由非表示' })
        }
      } finally { await anonymous.close() }
    })
  }
})
