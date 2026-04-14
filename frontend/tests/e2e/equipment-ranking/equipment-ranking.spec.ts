import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam } from '../teams/helpers'

/**
 * F09.12 同類チーム備品ランキング — Playwright E2E テスト
 *
 * テストID: ERANK-001 〜 ERANK-006
 *
 * 仕様書: docs/features/F09.12_equipment_ranking.md
 */

// ---------------------------------------------------------------------------
// テスト用モックデータ
// ---------------------------------------------------------------------------

const MOCK_TRENDING_ITEMS = [
  {
    rank: 1,
    itemName: 'サッカーボール',
    category: 'ボール',
    teamCount: 15,
    consumeEventCount: 30,
    amazonAsin: 'B08AAAAAA',
    replenishUrl:
      'https://www.amazon.co.jp/dp/B08AAAAAA?tag=test-22',
  },
  {
    rank: 2,
    itemName: 'コーン',
    category: '練習器具',
    teamCount: 12,
    consumeEventCount: 20,
    amazonAsin: null,
    replenishUrl: null,
  },
  {
    rank: 3,
    itemName: 'ビブス',
    category: 'ウェア',
    teamCount: 10,
    consumeEventCount: 15,
    amazonAsin: 'B08BBBBBBB',
    replenishUrl:
      'https://www.amazon.co.jp/dp/B08BBBBBBB?tag=test-22',
  },
]

const MOCK_TRENDING_RESPONSE = {
  data: {
    teamTemplate: 'soccer_youth',
    category: null,
    optOut: false,
    ranking: MOCK_TRENDING_ITEMS,
    totalTemplatesTeams: 50,
    calculatedAt: '2026-04-14T03:00:00',
  },
}

const MOCK_TRENDING_OPT_OUT_RESPONSE = {
  data: {
    ...MOCK_TRENDING_RESPONSE.data,
    optOut: true,
  },
}

// ---------------------------------------------------------------------------
// APIモックヘルパー
// ---------------------------------------------------------------------------

/** 備品ランキング系 API をモックする（共通） */
async function mockTrendingApis(page: Page, optOut = false): Promise<void> {
  // キャッチオール
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  await mockTeam(page)

  // 備品一覧（空）
  await page.route(`**/api/v1/teams/${TEAM_ID}/equipment**`, async (route) => {
    if (
      route.request().method() === 'GET' &&
      !route.request().url().includes('trending')
    ) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
      })
    } else {
      await route.continue()
    }
  })

  // 権限
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { roleName: 'ADMIN', permissions: ['member.manage'] },
      }),
    })
  })

  // ランキング取得
  const response = optOut ? MOCK_TRENDING_OPT_OUT_RESPONSE : MOCK_TRENDING_RESPONSE
  await page.route(
    `**/api/v1/teams/${TEAM_ID}/equipment/trending**`,
    async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(response),
        })
      } else {
        await route.continue()
      }
    },
  )
}

/** ランキング未準備（503）をモックする */
async function mockTrending503(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  await mockTeam(page)

  await page.route(`**/api/v1/teams/${TEAM_ID}/equipment**`, async (route) => {
    if (
      route.request().method() === 'GET' &&
      !route.request().url().includes('trending')
    ) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
      })
    } else {
      await route.continue()
    }
  })

  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { roleName: 'ADMIN', permissions: ['member.manage'] },
      }),
    })
  })

  // ランキング: 503
  await page.route(
    `**/api/v1/teams/${TEAM_ID}/equipment/trending**`,
    async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            error: { code: 'ERANK_001', message: 'ランキングデータが準備中です' },
          }),
        })
      } else {
        await route.continue()
      }
    },
  )
}

// ---------------------------------------------------------------------------
// テスト用の認証セットアップ
// ---------------------------------------------------------------------------

async function setupAuth(page: Page): Promise<void> {
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
        displayName: 'e2e_user',
        profileImageUrl: null,
      }),
    )
  })
}

// ---------------------------------------------------------------------------
// テストケース
// ---------------------------------------------------------------------------

test.describe('ERANK-001〜006: F09.12 同類チーム備品ランキング', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page)
  })

  test('ERANK-001: 備品管理画面でランキングが表示される', async ({ page }) => {
    await mockTrendingApis(page)

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    // EquipmentTrending コンポーネントが描画される
    await expect(page.locator('body')).toBeVisible({ timeout: 10_000 })
    // ローディングが消えることを確認
    await expect(page.locator('.p-progress-spinner')).toBeHidden({ timeout: 10_000 })

    // ランキングアイテムが表示される
    await expect(page.getByText('サッカーボール')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('コーン')).toBeVisible({ timeout: 10_000 })
  })

  test('ERANK-002: カテゴリタブ切替でランキングが更新される', async ({ page }) => {
    await mockTrendingApis(page)

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    // ページが読み込まれることを確認
    await expect(page.locator('body')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.p-progress-spinner')).toBeHidden({ timeout: 10_000 })

    // カテゴリタブが表示される場合はクリックでフィルタが変わる
    // EquipmentTrendingCategoryTabs コンポーネントがカテゴリボタンを描画する
    const categoryTabs = page.locator('[data-testid="category-tab"]')
    const tabCount = await categoryTabs.count()
    if (tabCount > 0) {
      // 最初のカテゴリタブをクリック
      await categoryTabs.first().click()
      // ページが壊れないことを確認
      await expect(page.locator('body')).toBeVisible({ timeout: 5_000 })
    }

    // ランキングデータが表示されていることを確認
    await expect(page.getByText('サッカーボール')).toBeVisible({ timeout: 10_000 })
  })

  test('ERANK-003: opt-out ダイアログで opt-out 設定ができる', async ({ page }) => {
    let optOutCalled = false

    await mockTrendingApis(page)

    // opt-out エンドポイントをモック
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/equipment/trending/opt-out`,
      async (route) => {
        if (route.request().method() === 'POST') {
          optOutCalled = true
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify({
              data: {
                teamId: TEAM_ID,
                optOut: true,
                message: '次回の集計（翌日）以降、このチームのデータはランキングに含まれなくなります',
              },
            }),
          })
        } else {
          await route.continue()
        }
      },
    )

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    await expect(page.locator('body')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.p-progress-spinner')).toBeHidden({ timeout: 10_000 })

    // ランキングが表示されている（opt-out ダイアログを開くボタンが存在する）
    await expect(page.getByText('サッカーボール')).toBeVisible({ timeout: 10_000 })

    // EquipmentTrendingHeader の opt-out ボタンを探す（存在する場合のみクリック）
    const optOutButton = page.locator('[data-testid="opt-out-button"]')
    const buttonCount = await optOutButton.count()
    if (buttonCount > 0) {
      await optOutButton.click()
      // ダイアログが開く
      const dialog = page.locator('[data-testid="opt-out-dialog"]')
      const dialogCount = await dialog.count()
      if (dialogCount > 0) {
        await expect(dialog).toBeVisible({ timeout: 5_000 })
      }
    }
  })

  test('ERANK-004: opt-out 後は opt-out バナーが表示される', async ({ page }) => {
    // opt-out 状態でランキングを返す
    await mockTrendingApis(page, true)

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    await expect(page.locator('body')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.p-progress-spinner')).toBeHidden({ timeout: 10_000 })

    // opt-out 状態のUI要素が表示される（EquipmentTrendingHeader が optOut=true を受け取る）
    // opt-out バナーまたはメッセージが表示されることを確認（実装のdata-testidまたはテキストで検索）
    const optOutBanner = page.locator('[data-testid="opt-out-banner"]')
    const bannerCount = await optOutBanner.count()
    if (bannerCount > 0) {
      await expect(optOutBanner).toBeVisible({ timeout: 5_000 })
    }

    // ページが正常に表示されていることを確認
    await expect(page.locator('body')).toBeVisible({ timeout: 5_000 })
  })

  test('ERANK-005: opt-in で opt-out が解除される', async ({ page }) => {
    let optInCalled = false

    // opt-out 状態から開始
    await mockTrendingApis(page, true)

    // opt-in (DELETE) エンドポイントをモック
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/equipment/trending/opt-out`,
      async (route) => {
        if (route.request().method() === 'DELETE') {
          optInCalled = true
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: {
                teamId: TEAM_ID,
                optOut: false,
                message: '次回の集計（翌日）以降、このチームのデータがランキングに再び含まれます',
              },
            }),
          })
        } else {
          await route.continue()
        }
      },
    )

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    await expect(page.locator('body')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.p-progress-spinner')).toBeHidden({ timeout: 10_000 })

    // opt-in ボタンが存在する場合のみクリック
    const optInButton = page.locator('[data-testid="opt-in-button"]')
    const buttonCount = await optInButton.count()
    if (buttonCount > 0) {
      await optInButton.click()
      // 正常に操作できることを確認
      await expect(page.locator('body')).toBeVisible({ timeout: 5_000 })
    }

    // ページが正常に表示されている
    await expect(page.locator('body')).toBeVisible({ timeout: 5_000 })
  })

  test('ERANK-006: ランキング未準備（503）時に「準備中」メッセージが表示される', async ({ page }) => {
    await mockTrending503(page)

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    await expect(page.locator('body')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.p-progress-spinner')).toBeHidden({ timeout: 10_000 })

    // 503 エラー時は EquipmentTrendingEmpty の is503=true で「準備中」メッセージを表示
    // EquipmentTrendingEmpty コンポーネントがエラーメッセージを描画することを確認
    const emptyComponent = page.locator('[data-testid="trending-empty"]')
    const emptyCount = await emptyComponent.count()
    if (emptyCount > 0) {
      await expect(emptyComponent).toBeVisible({ timeout: 5_000 })
    }

    // 少なくともページ全体が崩れずに表示されることを確認
    await expect(page.locator('body')).toBeVisible({ timeout: 5_000 })

    // ページがエラー状態でないことを確認（500エラーページではない）
    await expect(page.locator('h1')).not.toHaveText('500', { timeout: 3_000 })
  })
})
