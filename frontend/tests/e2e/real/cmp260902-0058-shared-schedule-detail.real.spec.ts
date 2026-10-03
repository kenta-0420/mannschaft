/** CMP-260902-0058: Actions の専用 DB・同じ head の本物の API/UI による実機検証。 */
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { test, expect, type BrowserContext, type Page, type TestInfo } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const DESCRIPTION = '集合は正門\n持ち物：水筒'
const COLOR = '#a855f7'
const ADMIN = 'e2e-admin@test.mannschaft.local'
const OUTSIDER = 'e2e-outsider@test.mannschaft.local'

interface SeedConnection {
  execute<T = Array<Record<string, unknown>>>(sql: string, params?: unknown[]): Promise<[T, unknown]>
  end(): Promise<void>
}
interface Fixture {
  type: 'teams' | 'organizations'
  slug: string
  member: string
  memberId: number
  memberName: string
  title: string
  id: number
}
interface Detail {
  detail: { description: string; color: string }
  targetCount: number
  targets: Array<{ userId: number; displayName: string }>
}
const fixtures: Fixture[] = []
const observationLogs = new Map<string, string[]>()
const endpoint = (fixture: Fixture) => `/api/v1/${fixture.type}/${fixture.slug}/schedules/${fixture.id}`
const screenPath = (fixture: Fixture) => `/${fixture.type}/${fixture.slug}/schedule`

async function detail(page: Page, fixture: Fixture): Promise<Detail> {
  const response = await page.request.get(`${API}${endpoint(fixture)}`)
  expect(response.status(), '本物の詳細 GET').toBe(200)
  return (await response.json()).data as Detail
}

async function screenshot(page: Page, info: TestInfo, name: string) {
  const path = info.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: true })
  await info.attach(name, { path, contentType: 'image/png' })
}

async function openEvent(page: Page, fixture: Fixture) {
  const response = page.waitForResponse(response =>
    response.url().endsWith(endpoint(fixture)) && response.request().method() === 'GET')
  const visibleEvent = page.getByText(fixture.title, { exact: false }).filter({ visible: true }).first()
  await expect(visibleEvent, '表示中の予定から詳細を開く').toBeVisible()
  await visibleEvent.click()
  expect((await response).status(), '予定クリックで実際の詳細 GET を実行').toBe(200)
  // TEAM画面はmobile/desktop両パネルをDOMに保持するため、現在表示中の予定詳細へ限定する。
  const detailPanel = page.locator('div.space-y-4').filter({
    has: page.getByRole('heading', { name: fixture.title, exact: true, level: 2 }),
  }).filter({ visible: true })
  await expect(detailPanel, '題名が一致する可視の詳細パネルは1件').toHaveCount(1)
  const description = detailPanel.getByText(DESCRIPTION, { exact: true }).filter({ visible: true })
  await expect(description, '可視パネル内の保存済み説明文は1件').toHaveCount(1)
  await expect(description).toBeVisible()
}

test.describe('CMP-260902-0058 実ブラウザ（API smoke と別判定）', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ storageState: { cookies: [], origins: [] } })

  test.beforeAll(async ({ browser }) => {
    // 既存の共有 DB に fixture を投入しない。workflow の専用 MySQL だけで実行する。
    expect(process.env.GITHUB_ACTIONS, 'Actions 専用の実機フェーズ').toBe('true')
    expect(process.env.E2E_ISOLATED_DB, '専用 DB の明示確認').toBe('true')
    expect(process.env.E2E_DB_HOST).toBe('127.0.0.1')
    expect(process.env.E2E_DB_PORT).toBe('3306')
    expect(process.env.E2E_DB_NAME).toBe('mannschaft')
    expect(process.env.E2E_DB_USER).toBe('mannschaft')
    const containerId = process.env.E2E_MYSQL_CONTAINER_ID
    expect(containerId, '同じ job の MySQL service ID').toMatch(/^[a-f0-9]{64}$/)
    const mysqlHostname = execFileSync('docker', ['inspect', '--format', '{{.Config.Hostname}}', containerId!], { encoding: 'utf8' }).trim()
    const require = createRequire(new URL('../../../../backend/scripts/package.json', import.meta.url))
    const mysql = require('mysql2/promise') as {
      createConnection(options: Record<string, unknown>): Promise<SeedConnection>
    }
    const db = await mysql.createConnection({
      host: process.env.E2E_DB_HOST, port: Number(process.env.E2E_DB_PORT),
      user: process.env.E2E_DB_USER, password: process.env.E2E_DB_PASSWORD, database: process.env.E2E_DB_NAME,
    })
    let context: BrowserContext | undefined
    try {
      const [databaseHosts] = await db.execute('SELECT DATABASE() AS databaseName, @@hostname AS hostname')
      expect(databaseHosts[0]?.databaseName).toBe(process.env.E2E_DB_NAME)
      expect(databaseHosts[0]?.hostname, '接続した DB は当該 Actions job の service container').toBe(mysqlHostname)
      context = await browser.newContext({
        baseURL: process.env.BASE_URL ?? 'http://localhost:8081', locale: 'ja-JP', timezoneId: 'Asia/Tokyo',
      })
      const page = await context.newPage()
      const scopes = [
        { type: 'teams' as const, name: 'FC東京U-15（テスト）', member: 'e2e-dummy-6@test.mannschaft.local' },
        { type: 'organizations' as const, name: '東京都サッカー協会（テスト）', member: 'e2e-dummy-1@test.mannschaft.local' },
      ]
      for (const scope of scopes) {
        const [scopeRows] = await db.execute(`SELECT id, slug FROM ${scope.type} WHERE name = ?`, [scope.name])
        const [memberRows] = await db.execute('SELECT id, display_name FROM users WHERE email = ?', [scope.member])
        expect(scopeRows).toHaveLength(1)
        expect(memberRows).toHaveLength(1)
        const scopeId = Number(scopeRows[0]!.id)
        const [adminMemberships] = await db.execute(
          'SELECT sm.id FROM memberships sm JOIN users u ON u.id = sm.user_id '
          + 'WHERE u.email = ? AND sm.scope_type = ? AND sm.scope_id = ? AND sm.left_at IS NULL',
          [ADMIN, scope.type === 'teams' ? 'TEAM' : 'ORGANIZATION', scopeId],
        )
        expect(adminMemberships, 'SYSTEM_ADMIN はこのスコープの直接所属者ではない').toHaveLength(0)
        await page.context().clearCookies()
        await loginViaApi(page, { email: scope.member, password: PASSWORD }, { apiBaseUrl: API })
        const fixture: Fixture = {
          type: scope.type, slug: String(scopeRows[0]!.slug), member: scope.member,
          memberId: Number(memberRows[0]!.id), memberName: String(memberRows[0]!.display_name),
          title: `CMP0058-${scope.type}-${Date.now()}`, id: 0,
        }
        const date = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Tokyo' })
        const created = await page.request.post(`${API}/api/v1/${fixture.type}/${fixture.slug}/schedules`, {
          data: {
            title: fixture.title, description: DESCRIPTION,
            startAt: `${date}T10:00:00+09:00`, endAt: `${date}T11:00:00+09:00`,
            allDay: false, eventType: 'OTHER', visibility: 'MEMBERS_ONLY', minViewRole: 'ANYONE',
            attendanceRequired: false, targetMode: 'SELECTED_MEMBERS', targetUserIds: [fixture.memberId],
          },
        })
        expect(created.status(), `担当 fixture 作成: ${await created.text()}`).toBe(201)
        fixture.id = Number((await created.json()).data.id)
        expect(fixture.id, '今回 API 作成の予定 ID').toBeGreaterThan(0)
        fixtures.push(fixture)
        // 共有色は編集 API/UI に項目がないため、専用 DB の今回作成した行だけに保存済み色を用意する。
        const [updated] = await db.execute<{ affectedRows: number }>('UPDATE schedules SET color = ? WHERE id = ? AND title = ? AND created_by = ?',
          [COLOR, fixture.id, fixture.title, fixture.memberId])
        expect(updated.affectedRows, '今回 API 作成の予定一件だけに色 fixture を保存').toBe(1)
        const initial = await detail(page, fixture)
        expect(initial.detail).toMatchObject({ description: DESCRIPTION, color: COLOR })
        expect(initial.targets).toContainEqual(expect.objectContaining({ userId: fixture.memberId }))
      }
    } finally {
      await context?.close()
      await db.end()
    }
  })

  test.beforeEach(async ({ page }, info) => {
    const observations: string[] = []
    observationLogs.set(info.testId, observations)
    page.on('console', message => {
      const text = /access[_-]?token|refresh[_-]?token|authorization|set-cookie|bearer\s/i.test(message.text())
        ? '[認証関連出力を除外]' : message.text()
      observations.push(`console ${message.type()}: ${text}`)
    })
    page.on('pageerror', error => observations.push(`pageerror: ${error.message}`))
    page.on('requestfailed', request => observations.push(`requestfailed ${request.method()} ${new URL(request.url()).pathname}: ${request.failure()?.errorText}`))
    page.on('response', response => {
      if (response.status() >= 400) observations.push(`HTTP ${response.status()} ${response.request().method()} ${new URL(response.url()).pathname}`)
    })
    // 応答本文・Cookie・Authorization は証跡へ記録しない。
  })

  test.afterEach(async ({ page }, info) => {
    const observations = observationLogs.get(info.testId) ?? []
    observations.push(`最終画面: ${new URL(page.url()).pathname}`)
    await info.attach('console・失敗したリクエスト', { body: observations.join('\n'), contentType: 'text/plain' })
    observationLogs.delete(info.testId)
  })

  for (const type of ['teams', 'organizations'] as const) {
    test(`直接所属者: ${type} の通常ナビ・カレンダー・編集無変更保存`, async ({ page }, info) => {
      const fixture = fixtures.find(fixture => fixture.type === type)!
      await loginViaApi(page, { email: fixture.member, password: PASSWORD }, { apiBaseUrl: API })
      await page.goto('/')
      await waitForHydration(page)
      await page.locator('a[href="/calendar"]:visible').first().click()
      await expect(page).toHaveURL(/\/calendar$/)
      const today = page.getByTestId('calendar-today-button')
      await today.focus()
      await today.press('Enter')
      await openEvent(page, fixture)
      await expect(page.getByLabel(fixture.memberName, { exact: true }).first()).toBeVisible()
      await screenshot(page, info, `${type}-member-calendar`)
      await page.locator('button:has(.pi-pencil)').first().click()
      const dialog = page.getByRole('dialog')
      await expect(dialog.locator('textarea').first()).toHaveValue(DESCRIPTION)
      const saved = page.waitForResponse(response =>
        response.url().endsWith(endpoint(fixture)) && response.request().method() === 'PATCH')
      await page.getByTestId('schedule-submit').click()
      const savedResponse = await saved
      expect(savedResponse.status()).toBe(200)
      expect(savedResponse.request().postDataJSON()).toMatchObject({ description: DESCRIPTION })
      expect(savedResponse.request().postDataJSON()).not.toHaveProperty('color')
      expect((await detail(page, fixture)).detail).toMatchObject({ description: DESCRIPTION, color: COLOR })
      await screenshot(page, info, `${type}-member-saved`)
      await page.goBack()
      await expect(page).toHaveURL(/\/dashboard$/)
      const setupResponse = page.waitForResponse(response => {
        const url = new URL(response.url())
        return response.request().method() === 'GET' && url.pathname === '/api/v1/admin/member-permissions'
          && url.searchParams.get('scopeType') === (type === 'teams' ? 'TEAM' : 'ORGANIZATION')
      })
      await page.goto(screenPath(fixture))
      const setup = await setupResponse
      expect(setup.status(), '初回案内の判定を行う実GET').toBe(200)
      const settings = (await setup.json()).data.permissions as Array<{ inherited?: boolean }>
      const promptRequired = settings.length === 3 && settings.every(setting => setting.inherited)
      const setupDialog = page.getByRole('dialog', { name: 'メンバーの権限を初期設定', exact: true })
      if (promptRequired) {
        await expect(setupDialog).toBeVisible()
        await screenshot(page, info, `${type}-member-initial-permissions`)
        // 権限を保存せず、既存の初回案内を通常操作で後回しにする。
        await setupDialog.getByRole('button', { name: 'あとで決める', exact: true }).click()
      }
      await expect(setupDialog).toBeHidden()
      await info.attach(`${type}-初回案内の閉鎖`, { body: JSON.stringify({ path: '/api/v1/admin/member-permissions', status: setup.status(), promptRequired, dismissedWithoutSaving: promptRequired, dialogHidden: true }), contentType: 'application/json' })
      await openEvent(page, fixture)
      await screenshot(page, info, `${type}-member-scope-page`)
    })
  }

  test('非直接所属 SYSTEM_ADMIN: 直接 URL・戻る・説明可視と対象者名秘匿', async ({ page }, info) => {
    await loginViaApi(page, { email: ADMIN, password: PASSWORD }, { apiBaseUrl: API })
    for (const fixture of fixtures) {
      const response = await detail(page, fixture)
      expect(response.detail.description).toBe(DESCRIPTION)
      expect(response.targetCount).toBe(1)
      expect(response.targets).toEqual([])
      await page.goto(screenPath(fixture))
      await openEvent(page, fixture)
      await expect(page.getByLabel(fixture.memberName, { exact: true })).toHaveCount(0)
      await screenshot(page, info, `${fixture.type}-system-admin-hidden-targets`)
    }
    await page.goBack()
    await expect(page).toHaveURL(new RegExp(`/teams/${fixtures[0]!.slug}/schedule$`))
  })

  test('完全非所属者: 同じ直接 URL と実 GET は説明・名簿を開示しない', async ({ page }, info) => {
    await loginViaApi(page, { email: OUTSIDER, password: PASSWORD }, { apiBaseUrl: API })
    for (const fixture of fixtures) {
      const scopeEndpoint = `/api/v1/${fixture.type}/${fixture.slug}`
      const initialScopeResponse = page.waitForResponse(response => {
        const path = new URL(response.url()).pathname
        return response.request().method() === 'GET'
          && [scopeEndpoint, `${scopeEndpoint}/me/permissions`, `${scopeEndpoint}/schedules`].includes(path)
      })
      await page.goto(screenPath(fixture))
      expect([200, 403], '直接 URL の実初期 GET は正常取得または認可拒否').toContain((await initialScopeResponse).status())
      const response = await page.request.get(`${API}${endpoint(fixture)}`)
      expect(response.status(), 'ANYONE は MEMBERS_ONLY を緩めない').toBe(403)
      // 親の取得失敗表示、または予定ページの空詳細は、既存のロード終了後にだけ描画される。
      const terminal = page.getByText('イベントを選択してください', { exact: true })
        .or(page.getByText('情報を取得できませんでした', { exact: true })).filter({ visible: true })
      await expect(terminal, '403 後の既存画面がロード途中でなく終端へ到達').toBeVisible()
      await expect(page.locator('body > .pointer-events-none.fixed.inset-0'), 'グローバル読み込み終了').toBeHidden()
      expect(new URL(page.url()).pathname, '拒否時も同じ団体の直接 URL を検証').toBe(screenPath(fixture))
      await expect(page.getByText(DESCRIPTION, { exact: true })).toHaveCount(0)
      await expect(page.getByLabel(fixture.memberName, { exact: true })).toHaveCount(0)
      await screenshot(page, info, `${fixture.type}-outsider-denied`)
    }
  })
})
