import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * CMP-261025-2202: 実BE/実DBで表示設定を画面から保存する実機試験。
 * API補助はログイン・専用scope/所属fixture作成・scope削除のみ。
 * 表示設定のPUTは全てToggleSwitchと保存ボタンから送信する。
 * BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081
 * npx playwright test --config=playwright-real.config.ts --project=chromium-real --no-deps tests/e2e/real/cmp-261025-2202-feature-display.real.spec.ts
 */
const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const BASE = process.env.BASE_URL ?? 'http://localhost:3001'
const PASSWORD = process.env.FEATURE_DISPLAY_TEST_PASSWORD ?? 'TestPass2026!'
const EMAILS = {
  admin: 'e2e-user@test.mannschaft.local',
  member: 'e2e-dummy-6@test.mannschaft.local',
  supporter: 'e2e-supporter@test.mannschaft.local',
  outsider: 'e2e-outsider@test.mannschaft.local',
} as const

type ActorName = keyof typeof EMAILS
interface Actor {
  context: BrowserContext
  page: Page
  id: number
}
interface Scope {
  kind: 'teams' | 'organizations'
  slug: string
  path: string
  name: string
  numericId: number
}
const actors = {} as Record<ActorName, Actor>

test.use({
  storageState: { cookies: [], origins: [] },
  actionTimeout: 30_000,
  navigationTimeout: 60_000,
})

test.beforeAll(async ({ browser }) => {
  test.setTimeout(240_000)
  for (const name of Object.keys(EMAILS) as ActorName[]) {
    const context = await browser.newContext({
      baseURL: BASE,
      locale: 'ja-JP',
      timezoneId: 'Asia/Tokyo',
      storageState: { cookies: [], origins: [] },
    })
    const page = await context.newPage()
    await loginViaApi(page, { email: EMAILS[name], password: PASSWORD }, { apiBaseUrl: API })
    actors[name] = {
      context,
      page,
      id: await page.evaluate(() => JSON.parse(localStorage.getItem('currentUser')!).id as number),
    }
  }
})

test.afterAll(async () => {
  for (const actor of Object.values(actors)) await actor.context.close()
})

async function createScope(kind: Scope['kind']): Promise<Scope> {
  const name = '表示設定実機-' + kind + '-' + Date.now()
  const created = await actors.admin.page.request.post(API + '/api/v1/' + kind, {
    data:
      kind === 'teams'
        ? { name, template: 'SPORTS', visibility: 'PUBLIC' }
        : { name, orgType: 'OTHER', visibility: 'PUBLIC' },
  })
  expect(created.status(), '専用scopeのfixture作成').toBe(201)
  const { slug, numericId } = (await created.json()).data as { slug: string; numericId: number }
  const scope = { kind, slug, numericId, path: '/' + kind + '/' + slug, name }
  try {
    const admin = actors.admin.page.request
    const enabledSupporters = await admin.patch(API + '/api/v1' + scope.path, {
      data: { supporterEnabled: true, version: 0 },
    })
    expect(enabledSupporters.status(), 'サポータ受け入れ前提').toBe(200)
    const invite = await admin.post(API + '/api/v1' + scope.path + '/invite-tokens', {
      data: { roleId: 4, maxUses: 1 },
    })
    expect(invite.status(), 'MEMBERの招待前提作成').toBe(201)
    const token = (await invite.json()).data.token as string
    const joined = await actors.member.page.request.post(API + '/api/v1/invite/' + token + '/join')
    expect(joined.status(), 'MEMBERの所属fixture作成').toBe(200)
    const followed = await actors.supporter.page.request.post(
      API + '/api/v1' + scope.path + '/follow',
    )
    expect(followed.status(), 'SUPPORTERの通常フォロー前提作成').toBe(201)
    expect((await followed.json()).data.status).toBe('APPROVED')
    for (const [actor, roleName] of [
      ['admin', 'ADMIN'],
      ['member', 'MEMBER'],
      ['supporter', 'SUPPORTER'],
      ['outsider', null],
    ] as const) {
      const permissions = await actors[actor].page.request.get(
        API + '/api/v1' + scope.path + '/me/permissions',
      )
      expect(permissions.status(), actor + 'のロール前提').toBe(200)
      expect((await permissions.json()).data.roleName ?? null, actor + 'の実ロール').toBe(roleName)
    }

    // 対象表示設定とは独立した、業務機能を利用するための前提。
    const catalogResponse = await admin.get(API + '/api/v1' + scope.path + '/modules/catalog')
    expect(catalogResponse.status()).toBe(200)
    const modules = (await catalogResponse.json()).data.modules as Array<{
      moduleId: number
      slug: string
      isEnabled: boolean
    }>
    for (const module of modules.filter(
      (item) =>
        ['bulletin', 'circulation', 'circular', 'survey', 'schedule'].includes(item.slug) &&
        !item.isEnabled,
    )) {
      const enabled = await admin.patch(
        API + '/api/v1' + scope.path + '/modules/' + module.moduleId + '/toggle',
        { data: { moduleId: module.moduleId, enabled: true } },
      )
      expect(enabled.status(), '前提モジュール有効化:' + module.slug).toBe(200)
    }
    const bulletin = await admin.post(
      API + '/api/v1/' + kind + '/' + numericId + '/bulletin/threads',
      {
        data: {
          title: name + '-掲示板',
          body: '<p>表示復元で保持される本文</p>',
          priority: 'INFO',
        },
      },
    )
    expect(bulletin.status(), '掲示板元データの前提作成').toBe(201)
    const circulationPath = API + '/api/v1/' + kind + '/' + numericId + '/circulations'
    const circulation = await admin.post(circulationPath, {
      data: {
        title: name + '-回覧',
        body: '未確認回覧',
        circulationMode: 'SIMULTANEOUS',
        recipients: [{ userId: actors.member.id, sortOrder: 1 }],
      },
    })
    expect(circulation.status(), '回覧未確認の前提作成').toBe(201)
    const circulationId = (await circulation.json()).data.id as number
    expect((await admin.post(circulationPath + '/' + circulationId + '/activate')).status()).toBe(
      200,
    )
    const surveyPath = API + '/api/v1' + scope.path + '/surveys'
    const survey = await admin.post(surveyPath, {
      data: {
        title: name + '-アンケート',
        isAnonymous: false,
        allowMultipleSubmissions: false,
        resultsVisibility: 'ADMINS_ONLY',
        distributionMode: 'ALL',
        unrespondedVisibility: 'ALL_MEMBERS',
        questions: [
          { questionType: 'FREE_TEXT', questionText: '回答', isRequired: true, displayOrder: 1 },
        ],
      },
    })
    expect(survey.status(), 'アンケート未回答の前提作成').toBe(201)
    const surveyId = (await survey.json()).data.id as number
    expect((await admin.post(surveyPath + '/' + surveyId + '/publish')).status()).toBe(200)
    const startAt = new Date(Date.now() + 7 * 86_400_000).toISOString()
    const endAt = new Date(Date.now() + 7 * 86_400_000 + 3_600_000).toISOString()
    const schedule = await admin.post(API + '/api/v1' + scope.path + '/schedules', {
      data: {
        title: name + '-出席確認',
        startAt,
        endAt,
        allDay: false,
        eventType: 'EVENT',
        visibility: 'MEMBERS_ONLY',
        minViewRole: 'MEMBER_PLUS',
        minResponseRole: 'MEMBER_PLUS',
        attendanceRequired: true,
        targetMode: 'ALL_MEMBERS',
      },
    })
    expect(schedule.status(), '出席未回答の前提作成').toBe(201)
    return scope
  } catch (error) {
    await removeScope(scope)
    throw error
  }
}

async function removeScope(scope: Scope): Promise<void> {
  const deleted = await actors.admin.page.request.delete(API + '/api/v1' + scope.path)
  expect([200, 204], '所有scopeだけを後始末する').toContain(deleted.status())
}

function tab(page: Page, scope: Scope, key: string) {
  return page.locator('.scope-shell__tab a[href="' + scope.path + (key ? '/' + key : '') + '"]')
}

async function openHome(page: Page, scope: Scope): Promise<void> {
  await page.goto(scope.path, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await expect(tab(page, scope, ''), '固定ホームアンカー').toBeVisible({ timeout: 60_000 })
}

async function openSettings(scope: Scope, initialVisit = false): Promise<void> {
  const page = actors.admin.page
  await openHome(page, scope)
  if (initialVisit) {
    const setup = page.getByTestId('member-permission-setup')
    await expect(setup).toBeVisible({ timeout: 30_000 })
    await setup.getByRole('button', { name: 'あとで決める', exact: true }).click()
    await expect(setup).toHaveCount(0)
  }
  await expect(tab(page, scope, 'modules'), '管理者の機能設定入口').toBeVisible()
  await tab(page, scope, 'modules').click()
  await expect(page.locator('[data-feature-key="info"]')).toBeVisible({ timeout: 60_000 })
}

async function setFeature(
  _scope: Scope,
  featureKey: string,
  member: boolean,
  supporter: boolean,
): Promise<void> {
  const page = actors.admin.page
  await page.locator('#feature-display-' + featureKey + '-member').setChecked(member)
  await page.locator('#feature-display-' + featureKey + '-supporter').setChecked(supporter)
}

async function saveSettings(scope: Scope): Promise<void> {
  const page = actors.admin.page
  const [saved] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes('/api/v1' + scope.path + '/feature-display-policies') &&
        response.request().method() === 'PUT',
    ),
    page.getByTestId('feature-display-save').click(),
  ])
  expect(saved.status(), '画面操作による実BE保存').toBe(200)
  await expect(page.getByTestId('feature-display-save')).toBeEnabled()
  await openSettings(scope)
}

async function expectInfo(
  scope: Scope,
  actor: 'member' | 'supporter',
  visible: boolean,
): Promise<void> {
  const page = actors[actor].page
  await openHome(page, scope)
  if (visible) await expect(tab(page, scope, 'info')).toBeVisible({ timeout: 30_000 })
  else await expect(tab(page, scope, 'info')).toHaveCount(0, { timeout: 30_000 })
  await expect(tab(page, scope, 'modules'), actor + 'には管理設定入口を出さない').toHaveCount(0)
}

async function openSwipe(page: Page, scope: Scope) {
  const type = scope.kind === 'teams' ? 'TEAM' : 'ORGANIZATION'
  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.getByTestId('scope-segment-' + type).click()
  const chip = page.getByTestId('scope-tab-chip-' + type + '-' + scope.slug)
  const previous = page.getByTestId('scope-tab-prevpage-' + type)
  const next = page.getByTestId('scope-tab-nextpage-' + type)
  async function turn(button: typeof next) {
    const response = page.waitForResponse((r) => {
      const url = new URL(r.url())
      return (
        url.pathname.endsWith('/dashboard/scope-tabs') && url.searchParams.get('scopeType') === type
      )
    })
    const [, loaded] = await Promise.all([button.click(), response])
    expect(loaded.status()).toBe(200)
    const items = (await loaded.json()).data.items as Array<{ public_id: string }>
    await expect
      .poll(async () =>
        (
          await page
            .getByTestId('scope-tab-bar-' + type)
            .locator('[data-testid^="scope-tab-chip-"]')
            .evaluateAll((elements) => elements.map((el) => el.getAttribute('data-testid')))
        ).sort(),
      )
      .toEqual(items.map((item) => 'scope-tab-chip-' + type + '-' + item.public_id).sort())
  }
  for (
    let index = 0;
    index < 100 && (await previous.isVisible()) && (await previous.isEnabled());
    index++
  )
    await turn(previous)
  for (let index = 0; index < 100 && !(await chip.count()); index++) {
    await expect(next, '対象の所属チップへUIページ送りで到達する').toBeEnabled()
    await turn(next)
  }
  await expect(chip).toBeVisible()
  await chip.click()
  await expect(chip).toHaveAttribute('aria-pressed', 'true')
  const grid = page.getByTestId('swipe-widget-grid-' + type)
  await expect(grid).toBeVisible({ timeout: 60_000 })
  return grid
}

async function expectBulletin(
  scope: Scope,
  actor: 'member' | 'supporter',
  visible: boolean,
): Promise<void> {
  const page = actors[actor].page
  await openHome(page, scope)
  if (actor === 'member') {
    await page.getByTestId('scope-sidebar-toggle').click()
    const link = page.getByRole('dialog').locator('a[href="' + scope.path + '/bulletin"]')
    if (visible) await expect(link).toBeVisible()
    else await expect(link).toHaveCount(0)
    await page.keyboard.press('Escape')
  }
  const grid = await openSwipe(page, scope)
  const heading = grid.getByRole('heading', { name: '掲示板', exact: true })
  if (visible) {
    await expect(heading).toBeVisible()
    if (actor === 'member')
      await expect(grid.getByText(scope.name + '-掲示板', { exact: true })).toBeVisible()
  } else await expect(heading).toHaveCount(0)
}

for (const kind of ['teams', 'organizations'] as const) {
  test(kind + ': 2列独立保存・再読込・4組合せ・全OFF・管理者復帰・直URL既存認可', async () => {
    test.setTimeout(1_200_000)
    const scope = await createScope(kind)
    try {
      await openSettings(scope, true)
      await expect(actors.admin.page.locator('#feature-display-info-member')).toBeChecked()
      await expect(actors.admin.page.locator('#feature-display-info-supporter')).toBeChecked()

      // 相反する2列と両OFF/両ONの全組合せを、同じ保存データの更新として踏む。
      for (const [member, supporter] of [
        [false, true],
        [true, false],
        [false, false],
        [true, true],
      ] as const) {
        await test.step('表示組合せ member=' + member + '/supporter=' + supporter, async () => {
          await setFeature(scope, 'info', member, supporter)
          await setFeature(scope, 'bulletin', member, supporter)
          await saveSettings(scope)
          await expect(actors.admin.page.locator('#feature-display-info-member')).toBeChecked({
            checked: member,
          })
          await expect(actors.admin.page.locator('#feature-display-info-supporter')).toBeChecked({
            checked: supporter,
          })
          await expectInfo(scope, 'member', member)
          await expectInfo(scope, 'supporter', supporter)
          await expectBulletin(scope, 'member', member)
          await expectBulletin(scope, 'supporter', supporter)
        })
        console.info(kind + ': 表示組合せ完了 member=' + member + '/supporter=' + supporter)
      }

      // 要対応3区分の実データがある状態から1区分ずつ隠す。
      for (const [key, label] of [
        ['circulation', '回覧板'],
        ['surveys', 'アンケート'],
        ['schedule', '出席確認'],
      ] as const) {
        await openSettings(scope)
        await setFeature(scope, key, false, true)
        await saveSettings(scope)
        const grid = await openSwipe(actors.member.page, scope)
        if (key !== 'schedule')
          await expect(grid.getByRole('heading', { name: '要対応', exact: true })).toBeVisible()
        await expect(grid.getByRole('button', { name: new RegExp('^' + label) })).toHaveCount(0)
        console.info(kind + ': 要対応非表示完了 ' + key)
      }
      const hiddenActionGrid = await openSwipe(actors.member.page, scope)
      await expect(
        hiddenActionGrid.getByRole('heading', { name: '要対応', exact: true }),
      ).toHaveCount(0)
      await openSettings(scope)
      for (const key of ['circulation', 'surveys', 'schedule'])
        await setFeature(scope, key, true, true)
      await saveSettings(scope)
      const actionGrid = await openSwipe(actors.member.page, scope)
      await actionGrid
        .getByRole('heading', { name: '要対応', exact: true })
        .scrollIntoViewIfNeeded()
      for (const label of ['回覧板', 'アンケート', '出席確認'])
        await expect(
          actionGrid.getByRole('button', { name: new RegExp('^' + label) }).first(),
        ).toBeVisible({ timeout: 30_000 })
      console.info(kind + ': 要対応復元完了')

      // 全OFFでも管理者の管理表示・復帰とホームは残す。
      const switches = actors.admin.page.locator('[data-feature-key] input[role="switch"]')
      const count = await switches.count()
      expect(count).toBeGreaterThan(10)
      for (let index = 0; index < count; index++) await switches.nth(index).setChecked(false)
      await saveSettings(scope)
      await openHome(actors.admin.page, scope)
      await expect(tab(actors.admin.page, scope, 'info')).toBeVisible()
      await actors.admin.page.getByTestId('scope-lens-member').click()
      await expect(tab(actors.admin.page, scope, 'info')).toHaveCount(0)
      await expect(tab(actors.admin.page, scope, '')).toBeVisible()
      await expect(actors.admin.page.getByTestId('scope-lens-admin')).toBeVisible()
      await actors.admin.page.getByTestId('scope-lens-admin').click()
      await expect(tab(actors.admin.page, scope, 'modules')).toBeVisible()
      await expectInfo(scope, 'member', false)
      await expectInfo(scope, 'supporter', false)
      console.info(kind + ': 全OFF/管理者レンズ復帰完了')

      // 表示OFFは直URLの認可ではない。infoは元々許可されている画面を保つ。
      for (const actor of ['member', 'supporter'] as const) {
        const page = actors[actor].page
        await page.goto(scope.path + '/info', { waitUntil: 'domcontentloaded' })
        await waitForHydration(page)
        await expect(page).toHaveURL(new RegExp(scope.path + '/info$'))
        await expect(tab(page, scope, '')).toBeVisible()
        await expect(tab(page, scope, 'info')).toHaveCount(0)
        await expect(page.getByText(/アクセス権限がありません|ページが見つかりません/)).toHaveCount(
          0,
        )
      }

      // 管理設定URLは非所属・MEMBER/SUPPORTERへ公開しない。
      for (const actor of ['member', 'supporter', 'outsider'] as const) {
        const page = actors[actor].page
        await page.goto(scope.path + '/modules', { waitUntil: 'domcontentloaded' })
        await waitForHydration(page)
        await expect(page.getByTestId('feature-display-save')).toHaveCount(0)
        await expect(page.locator('[data-feature-key]')).toHaveCount(0)
        await expect(page).not.toHaveURL(new RegExp(scope.path + '/modules$'), { timeout: 30_000 })
      }
      console.info(kind + ': 直接URL/管理設定認可完了')

      // 復元も同じ画面から行い、保存済みscopeで元の入口が戻る。
      await openSettings(scope)
      const restored = actors.admin.page.locator('[data-feature-key] input[role="switch"]')
      for (let index = 0; index < (await restored.count()); index++)
        await restored.nth(index).setChecked(true)
      await saveSettings(scope)
      await expectInfo(scope, 'member', true)
      await expectInfo(scope, 'supporter', true)
      await expectBulletin(scope, 'member', true)
      await expectBulletin(scope, 'supporter', true)
    } finally {
      await removeScope(scope)
    }
  })
}
