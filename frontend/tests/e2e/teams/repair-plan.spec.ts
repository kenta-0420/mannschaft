import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/** E2Eテスト用の擬似認証情報をlocalStorageに設定する */
async function mockAuth(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 1,
        email: 'e2e-user@example.com',
        displayName: 'e2eユーザー',
        profileImageUrl: null,
      }),
    )
  })
}

/** 修繕計画ページに必要なAPIをまとめてモックする */
async function mockRepairPlanApis(page: import('@playwright/test').Page) {
  // タイムラインAPI
  await page.route(`**/api/v1/teams/${TEAM_ID}/repair-plan/timeline**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          labels: [],
          layers: [],
        },
      }),
    })
  })

  // カンバン一覧API
  await page.route(`**/api/v1/teams/${TEAM_ID}/repair-plan/quote-kanbans**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // 申し送りパックAPI
  await page.route(`**/api/v1/teams/${TEAM_ID}/repair-plan/handover-packs**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // 理事任期API
  await page.route(`**/api/v1/teams/${TEAM_ID}/member-terms**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // 組織一覧API（カンバン作成時に必要）
  await page.route(`**/api/v1/teams/${TEAM_ID}/organizations**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [{ id: 1, name: 'テスト管理組合' }],
      }),
    })
  })
}

test.describe('RP-001〜006: 修繕計画ダッシュボード', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    await mockRepairPlanApis(page)
  })

  test('RP-001: ダッシュボードが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/repair-plan`)
    await waitForHydration(page)

    // ページタイトル「修繕長期計画」が表示される
    await expect(page.getByText('修繕長期計画')).toBeVisible({ timeout: 10_000 })

    // 3タブボタン（タイムライン、カンバン、申し送り）が表示される
    await expect(page.getByRole('button', { name: '修繕履歴・計画タイムライン' })).toBeVisible({
      timeout: 5_000,
    })
    await expect(page.getByRole('button', { name: '相見積もりカンバン' })).toBeVisible({
      timeout: 5_000,
    })
    await expect(page.getByRole('button', { name: '申し送り' })).toBeVisible({ timeout: 5_000 })
  })

  test('RP-002: タイムラインタブが初期表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/repair-plan`)
    await waitForHydration(page)

    // ページ読み込み完了を待つ
    await expect(page.getByText('修繕長期計画')).toBeVisible({ timeout: 10_000 })

    // タイムラインタブが表示されている（初期アクティブ）
    const timelineTab = page.getByRole('button', { name: '修繕履歴・計画タイムライン' })
    await expect(timelineTab).toBeVisible({ timeout: 5_000 })

    // タイムラインコンテンツが表示される（年度範囲フォームまたは空状態メッセージ）
    const timelineContent = page.locator('div').filter({ hasText: '開始年度' }).first()
    await expect(timelineContent).toBeVisible({ timeout: 5_000 })
  })

  test('RP-003: カンバンタブに切り替えられる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/repair-plan`)
    await waitForHydration(page)

    // ページ読み込み完了を待つ
    await expect(page.getByText('修繕長期計画')).toBeVisible({ timeout: 10_000 })

    // カンバンタブをクリック
    await page.getByRole('button', { name: '相見積もりカンバン' }).click()

    // カンバンコンテンツが表示される（カンバン見出しまたは空状態）
    await expect(
      page.getByText('カンバンがありません'),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('RP-004: 申し送りタブに切り替えられる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/repair-plan`)
    await waitForHydration(page)

    // ページ読み込み完了を待つ
    await expect(page.getByText('修繕長期計画')).toBeVisible({ timeout: 10_000 })

    // 申し送りタブをクリック
    await page.getByRole('button', { name: '申し送り' }).click()

    // 申し送りコンテンツが表示される（見出しで判定）
    await expect(
      page.getByRole('heading', { name: '申し送りパック生成' }),
    ).toBeVisible({ timeout: 10_000 })
  })

  test.skip('RP-005: モジュールが無効な場合はエラーまたは未対応表示', async ({ page }) => {
    // repair-plan.vue にはモジュールガード（isEnabled チェック）が実装されていないため、
    // このテストはスキップする。モジュールガードが実装された際に有効化すること。
    await page.goto(`/teams/${TEAM_ID}/repair-plan`)
    await waitForHydration(page)
    await expect(page.getByText('このモジュールは無効です')).toBeVisible({ timeout: 5_000 })
  })

  test('RP-006: カンバン作成ダイアログが開く', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/repair-plan`)
    await waitForHydration(page)

    // ページ読み込み完了を待つ
    await expect(page.getByText('修繕長期計画')).toBeVisible({ timeout: 10_000 })

    // カンバンタブに切り替え
    await page.getByRole('button', { name: '相見積もりカンバン' }).click()

    // カンバンコンテンツが表示されるまで待つ
    await page.waitForTimeout(500)

    // 「カンバンを作成」ボタンをクリック（PrimeVueのiconつきButtonはfilterで取得）
    await page.locator('button').filter({ hasText: 'カンバンを作成' }).click({ timeout: 15_000 })

    // ダイアログが開く
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

    // ダイアログヘッダーにタイトルが表示される
    await expect(page.getByRole('dialog').getByText('カンバンを作成')).toBeVisible({
      timeout: 5_000,
    })
  })
})
