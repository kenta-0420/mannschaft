import { readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Locator, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * #3966: 実BE・実FEの管理レンズで、実CSSのタップ領域とview遷移を検証する。
 * UI3966_FIXTURES は専用環境の所有fixture。未設定・不正はskipせず失敗する。
 * 実行: playwright-real.config.ts --no-deps --project=chromium-real を使用する。
 * API補助はログインのみ。言語は設定画面から変更し、finallyで元に戻す。
 */
interface Fixture {
  baseUrl: string
  apiBaseUrl: string
  orgSlug: string
  credentialsFile: string
}
interface Credential {
  role: string
  email: string
  password: string
}

const fixturePath = process.env.UI3966_FIXTURES
if (!fixturePath) throw new Error('UI3966_FIXTURES_REQUIRED')
const fixture = JSON.parse(readFileSync(fixturePath, 'utf8')) as Fixture
if (!fixture.baseUrl || !fixture.apiBaseUrl || !fixture.orgSlug || !fixture.credentialsFile) {
  throw new Error('UI3966_FIXTURES_INVALID')
}
const credentials = JSON.parse(readFileSync(fixture.credentialsFile, 'utf8')) as Credential[]
const admin = credentials.find((entry) => entry.role === 'positive-admin')
if (!admin?.email || !admin.password) throw new Error('UI3966_ADMIN_CREDENTIAL_REQUIRED')

const views = ['teams', 'applications', 'invites', 'restrictions', 'groups'] as const
const viewEndpoints = {
  teams: 'teams',
  applications: 'team-applications',
  invites: 'team-invites',
  restrictions: 'team-affiliation-restrictions',
  groups: 'team-groups',
}
const rowNames = { applications: 'application', invites: 'invite', restrictions: 'restriction' }
const languages = ['日本語', 'English', 'Deutsch'] as const
const emptyTeamLabels = {
  日本語: '該当する加盟チームはありません',
  English: 'No affiliated teams match',
  Deutsch: 'Keine passenden beigetretenen Teams',
}
// 明示した切替領域の画像だけを保存し、認証情報やプロフィールをtrace/自動画像へ含めない。
test.use({ screenshot: 'off', trace: 'off' })
const languageCodes: Record<string, string> = {
  日本語: 'ja',
  English: 'en',
  '中文（简体）': 'zh',
  한국어: 'ko',
  Español: 'es',
  Deutsch: 'de',
}

async function chooseLanguage(page: Page, language: string) {
  await page.goto('/settings/language')
  await waitForHydration(page)
  const languageSelect = page.getByRole('combobox').first()
  await expect(languageSelect).toBeVisible()
  await languageSelect.click()
  await page.getByRole('option', { name: language, exact: true }).click()
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/users/me' &&
      response.request().method() === 'PUT',
  )
  await page
    .locator('button')
    .filter({ has: page.locator('.pi-check') })
    .click()
  expect((await saved).status(), '言語設定を実UIで保存する').toBe(200)
  await expect(languageSelect).toContainText(language)
  await expect
    .poll(
      async () =>
        (await page.context().cookies()).find((cookie) => cookie.name === 'i18n_locale')?.value,
    )
    .toBe(languageCodes[language])
}

async function tapTarget(control: Locator, label: string) {
  await expect(control).toBeVisible()
  const box = await control.boundingBox()
  expect(box, label).not.toBeNull()
  expect.soft(box!.width, `${label}: width`).toBeGreaterThanOrEqual(44)
  expect.soft(box!.height, `${label}: height`).toBeGreaterThanOrEqual(44)
  return { label, width: box!.width, height: box!.height, text: await control.innerText() }
}

async function selected(page: Page, view: (typeof views)[number]) {
  await expect(page.getByTestId(`view-tab-${view}`)).toHaveAttribute('aria-current', 'page')
  expect(new URL(page.url()).searchParams.get('view')).toBe(view === 'teams' ? null : view)
  expect(new URL(page.url()).searchParams.get('keep')).toBe('mobile-targets')
  await expect(page.locator('[data-testid^="view-tab-"][aria-current="page"]')).toHaveCount(1)
}

test('加盟管理5タブは360/390px・日本語/英語/ドイツ語でも44px以上で遷移できる', async ({
  browser,
}, testInfo) => {
  test.setTimeout(180_000)
  const context = await browser.newContext({
    baseURL: fixture.baseUrl,
    storageState: { cookies: [], origins: [] },
    isMobile: true,
    hasTouch: true,
    locale: 'ja-JP',
    viewport: { width: 390, height: 844 },
  })
  const page = await context.newPage()
  let originalLanguage: string | undefined
  const dimensions: Record<string, unknown>[] = []
  try {
    try {
      await loginViaApi(page, admin, { apiBaseUrl: fixture.apiBaseUrl })
    } catch (error) {
      // 既存helperの認証失敗に含まれるアカウント・レスポンスを成果物へ残さない。
      // 原因の原本は共有artifactに添付せず、既存private領域へ0600で保存する。
      writeFileSync(
        path.join(path.dirname(fixture.credentialsFile), `ui3966-login-failure-${Date.now()}.txt`),
        error instanceof Error ? (error.stack ?? error.message) : String(error),
        { encoding: 'utf8', mode: 0o600, flag: 'wx' },
      )
      throw new Error('UI3966_ADMIN_LOGIN_FAILED')
    }
    await page.goto('/settings/language')
    await waitForHydration(page)
    const languageSelect = page.getByRole('combobox').first()
    await expect(languageSelect).toBeVisible()
    originalLanguage = (await languageSelect.innerText()).trim()

    for (const language of languages) {
      await chooseLanguage(page, language)
      const initialSetup =
        language === '日本語'
          ? page.waitForResponse(
              (response) =>
                new URL(response.url()).pathname === '/api/v1/admin/member-permissions' &&
                new URL(response.url()).searchParams.get('scopeType') === 'ORGANIZATION' &&
                response.request().method() === 'GET',
            )
          : undefined
      const documentResponse = await page.goto(
        `/organizations/${fixture.orgSlug}/member-teams?keep=mobile-targets`,
      )
      expect(documentResponse?.status(), '対象組織画面のHTTP応答').toBe(200)
      dimensions.push({ label: `${language}-document`, status: documentResponse?.status() })
      await waitForHydration(page)
      if (initialSetup) {
        const response = await initialSetup
        expect(response.status(), '初期権限案内の判定').toBe(200)
        const result = (await response.json()) as {
          data: { permissions: { inherited: boolean }[] }
        }
        const dialog = page.getByTestId('member-permission-setup')
        if (
          result.data.permissions.length === 3 &&
          result.data.permissions.every((entry) => entry.inherited)
        ) {
          await expect(dialog).toBeVisible()
          await dialog.getByRole('button', { name: 'あとで決める', exact: true }).click()
        }
        await expect(dialog).not.toBeVisible()
      }
      await page.getByTestId('scope-lens-admin').click()
      await expect(page.getByTestId('member-teams-view-switcher')).toBeVisible()
      await expect(page.getByTestId('member-teams-view-switcher').getByRole('button')).toHaveCount(
        5,
      )

      for (const viewport of [
        { width: 390, height: 844 },
        { width: 360, height: 800 },
      ]) {
        await page.setViewportSize(viewport)
        const scope = `${languageCodes[language]}-${viewport.width}`
        for (const view of views) {
          const tab = page.getByTestId(`view-tab-${view}`)
          const loaded =
            view === 'teams'
              ? undefined
              : page.waitForResponse((response) => {
                  const url = new URL(response.url())
                  return (
                    url.pathname ===
                      `/api/v1/organizations/${fixture.orgSlug}/${viewEndpoints[view]}` &&
                    response.request().method() === 'GET' &&
                    url.searchParams.get('size') !== '1'
                  )
                })
          await tab.click()
          await selected(page, view)
          if (loaded) {
            const response = await loaded
            const url = new URL(response.url())
            dimensions.push({
              label: `${scope}-${view}-api`,
              path: url.pathname,
              query: url.search,
              status: response.status(),
            })
            if (view === 'groups' && response.status() === 409) {
              await expect(page.getByTestId('groups-disabled-card')).toBeVisible()
            } else {
              expect(response.status(), `${view} 一覧取得`).toBe(200)
              if (view === 'groups')
                await expect(page.getByTestId('groups-unassigned-count')).toBeVisible()
            }
          }
          if (view === 'teams') {
            // 専用fixtureは未加盟組織。機能offのOrgTeamGridも同じ正規空表示を使う。
            await expect(page.getByText(emptyTeamLabels[language], { exact: true })).toBeVisible()
            await expect(page.getByTestId('teamlist-load-error')).not.toBeVisible()
            await expect(page.getByTestId('teamlist-groups-error')).not.toBeVisible()
          } else if (view !== 'groups') {
            await expect(
              page
                .locator(`[data-testid="${view}-empty"],[data-testid="${rowNames[view]}-row"]`)
                .first(),
            ).toBeVisible()
          }
          if (view !== 'teams')
            await expect(page.getByTestId(`${view}-load-error`)).not.toBeVisible()
          const dimension = await tapTarget(tab, `${scope}-${view}`)
          expect(dimension.text).not.toMatch(/^(teamAffiliation|orgMemberTeams)\./)
          dimensions.push(dimension)
          if (view === 'applications') {
            for (const control of ['team-affiliation-settings-link', 'page-header-help']) {
              const target = page.getByTestId(control)
              dimensions.push(await tapTarget(target, `${scope}-${control}`))
              await target.screenshot({ path: testInfo.outputPath(`${scope}-${control}.png`) })
            }
          }
          await page.getByTestId('member-teams-view-switcher').screenshot({
            path: testInfo.outputPath(`${scope}-${view}.png`),
          })
          const layout = await page.evaluate(() => ({
            clientWidth: document.documentElement.clientWidth,
            innerWidth: window.innerWidth,
            scrollWidth: document.documentElement.scrollWidth,
          }))
          dimensions.push({
            label: `${scope}-${view}-page`,
            configuredViewport: viewport,
            ...layout,
          })
          expect
            .soft(layout.scrollWidth, `${scope}-${view}: 横溢れ`)
            .toBeLessThanOrEqual(layout.clientWidth)
        }
        await page.goBack()
        await selected(page, 'restrictions')
        await page.goForward()
        await selected(page, 'groups')
      }
    }
  } finally {
    try {
      if (originalLanguage) {
        await chooseLanguage(page, originalLanguage)
        dimensions.push({
          label: 'restore-language',
          language: languageCodes[originalLanguage],
          completed: true,
        })
      }
    } finally {
      try {
        const evidencePath = testInfo.outputPath('mobile-target-dimensions.json')
        writeFileSync(evidencePath, JSON.stringify(dimensions, null, 2), {
          encoding: 'utf8',
          mode: 0o600,
        })
        await testInfo.attach('mobile-target-dimensions', {
          path: evidencePath,
          contentType: 'application/json',
        })
      } finally {
        await context.close()
      }
    }
  }
})
