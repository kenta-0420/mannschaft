import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F01.2 組織階層表示 E2E テスト
 *
 * 対象機能:
 *   - GET /api/v1/organizations/{id}/ancestors  ← 親組織パンくず
 *   - GET /api/v1/organizations/{id}/children    ← 下位組織グリッド
 *
 * UI コンポーネント:
 *   - app/components/organization/OrgAncestorsBreadcrumb.vue
 *   - app/components/organization/OrgChildrenGrid.vue
 *   - app/pages/organizations/[id]/index.vue
 */

const PARENT_ORG_ID = 1
const CURRENT_ORG_ID = 2
const HIDDEN_PARENT_ORG_ID = 5

const MOCK_CURRENT_ORG = {
  id: CURRENT_ORG_ID,
  name: '現組織B',
  nameKana: null,
  nickname1: null,
  nickname2: null,
  template: 'BASIC',
  prefecture: '東京都',
  city: '新宿区',
  description: 'E2E テスト用 子組織',
  visibility: 'PUBLIC',
  supporterEnabled: false,
  version: 1,
  memberCount: 5,
  archivedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  iconUrl: null,
  bannerUrl: null,
}

/**
 * 認証済みユーザーをシミュレート（localStorage に accessToken / currentUser を設定）
 * F13.1 / COMMITTEE-001 と同方式。
 */
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
        id: 99,
        email: 'e2e-user@example.com',
        displayName: 'e2e_user',
        profileImageUrl: null,
      }),
    )
  })
}

/**
 * 現組織の詳細・権限・チームなど描画に必要な API をモック。
 * 階層 API（ancestors / children）は各テストで個別にモック。
 */
async function mockOrgDetailApis(
  page: Page,
  _options: { withAncestors?: boolean; withChildren?: boolean } = {},
): Promise<void> {
  // 現組織詳細
  await page.route(`**/api/v1/organizations/${CURRENT_ORG_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_CURRENT_ORG }),
    })
  })

  // 権限
  await page.route(
    `**/api/v1/organizations/${CURRENT_ORG_ID}/me/permissions`,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { roleName: 'MEMBER', permissions: [] } }),
      })
    },
  )

  // 残りの組織配下 API は空配列を返してフォールバック（ancestors / children を除く）
  await page.route(`**/api/v1/organizations/${CURRENT_ORG_ID}/**`, async (route) => {
    const url = route.request().url()
    // ancestors / children は呼び出し側で別途モックするためここではスキップ
    if (url.includes('/ancestors') || url.includes('/children')) {
      await route.fallback()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
      }),
    })
  })
}

test.describe('ORG-HIER-001〜003: F01.2 組織階層表示', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page)
  })

  test('ORG-HIER-001: 親組織のパンくずがヘッダー直下に表示され、クリックで遷移する', async ({
    page,
  }) => {
    // ancestors API: 親組織A 1件（root → 親順）
    await page.route(
      `**/api/v1/organizations/${CURRENT_ORG_ID}/ancestors`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                id: PARENT_ORG_ID,
                name: '親組織A',
                nickname1: null,
                description: null,
                iconUrl: null,
                visibility: 'PUBLIC',
                hidden: false,
              },
            ],
            meta: { depth: 1, truncated: false },
          }),
        })
      },
    )

    // children API: 空配列（タブ表示には影響しないが描画用）
    await page.route(
      `**/api/v1/organizations/${CURRENT_ORG_ID}/children**`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { nextCursor: null, size: 20, hasNext: false },
          }),
        })
      },
    )

    // 親組織詳細 API（パンくずクリック後の遷移先で必要）
    await page.route(`**/api/v1/organizations/${PARENT_ORG_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { ...MOCK_CURRENT_ORG, id: PARENT_ORG_ID, name: '親組織A' },
        }),
      })
    })
    await page.route(
      `**/api/v1/organizations/${PARENT_ORG_ID}/me/permissions`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { roleName: 'MEMBER', permissions: [] } }),
        })
      },
    )
    await page.route(`**/api/v1/organizations/${PARENT_ORG_ID}/**`, async (route) => {
      const url = route.request().url()
      if (url.includes('/ancestors')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], meta: { depth: 0, truncated: false } }),
        })
        return
      }
      if (url.includes('/children')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { nextCursor: null, size: 20, hasNext: false },
          }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await mockOrgDetailApis(page)

    await page.goto(`/organizations/${CURRENT_ORG_ID}`)
    await waitForHydration(page)

    // パンくずナビが表示される
    const breadcrumb = page.getByTestId('org-ancestors-breadcrumb')
    await expect(breadcrumb).toBeVisible({ timeout: 10_000 })

    // 親組織A のリンクが表示される
    const ancestorLink = breadcrumb.getByTestId('org-ancestor-link')
    await expect(ancestorLink).toContainText('親組織A')

    // 現組織名（リンクなし）が太字で表示される
    await expect(breadcrumb.getByTestId('org-ancestor-current')).toContainText('現組織B')

    // クリックで親組織ページに遷移する
    await ancestorLink.click()
    await page.waitForURL(`**/organizations/${PARENT_ORG_ID}`, { timeout: 10_000 })
    expect(page.url()).toContain(`/organizations/${PARENT_ORG_ID}`)
  })

  test('ORG-HIER-002: hidden な祖先がプレースホルダで表示される（リンクではない）', async ({
    page,
  }) => {
    // hidden な祖先 1件
    await page.route(
      `**/api/v1/organizations/${CURRENT_ORG_ID}/ancestors`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                id: HIDDEN_PARENT_ORG_ID,
                name: null,
                nickname1: null,
                description: null,
                iconUrl: null,
                visibility: null,
                hidden: true,
              },
            ],
            meta: { depth: 1, truncated: false },
          }),
        })
      },
    )

    // children は空
    await page.route(
      `**/api/v1/organizations/${CURRENT_ORG_ID}/children**`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { nextCursor: null, size: 20, hasNext: false },
          }),
        })
      },
    )

    await mockOrgDetailApis(page)

    await page.goto(`/organizations/${CURRENT_ORG_ID}`)
    await waitForHydration(page)

    const breadcrumb = page.getByTestId('org-ancestors-breadcrumb')
    await expect(breadcrumb).toBeVisible({ timeout: 10_000 })

    // 非公開組織プレースホルダ（バッジ）が表示される
    const hiddenBadge = breadcrumb.getByTestId('org-ancestor-hidden')
    await expect(hiddenBadge).toBeVisible()
    await expect(hiddenBadge).toContainText('非公開組織')

    // リンク要素ではない（<a> ではなく <span>）— プレースホルダ専用 testid であり、
    // 通常の祖先リンク testid (`org-ancestor-link`) は存在しないことを保証
    await expect(breadcrumb.getByTestId('org-ancestor-link')).toHaveCount(0)
  })

  test('ORG-HIER-003: 「下位組織」タブが表示され、空時メッセージが出る', async ({ page }) => {
    // ancestors は空
    await page.route(
      `**/api/v1/organizations/${CURRENT_ORG_ID}/ancestors`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { depth: 0, truncated: false },
          }),
        })
      },
    )

    // 下位組織タブが必ず表示されるよう ADMIN ロールに上書きする
    // （非 ADMIN かつ children 0件だと showChildrenTab が false になりタブが消える）
    await page.route(
      `**/api/v1/organizations/${CURRENT_ORG_ID}/me/permissions`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { roleName: 'ADMIN', permissions: [] },
          }),
        })
      },
    )

    // 現組織詳細
    await page.route(`**/api/v1/organizations/${CURRENT_ORG_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_CURRENT_ORG }),
      })
    })

    // 子組織は空
    await page.route(
      `**/api/v1/organizations/${CURRENT_ORG_ID}/children**`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { nextCursor: null, size: 20, hasNext: false },
          }),
        })
      },
    )

    // 残りの API はキャッチオール
    await page.route(`**/api/v1/organizations/${CURRENT_ORG_ID}/**`, async (route) => {
      const url = route.request().url()
      if (
        url.includes('/ancestors') ||
        url.includes('/children') ||
        url.includes('/me/permissions')
      ) {
        await route.fallback()
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto(`/organizations/${CURRENT_ORG_ID}`)
    await waitForHydration(page)

    // 「下位組織」タブが表示される
    const childrenTab = page.getByRole('tab', { name: '下位組織' })
    await expect(childrenTab).toBeVisible({ timeout: 10_000 })

    // タブをクリック
    await childrenTab.click()

    // 空メッセージが表示される
    const empty = page.getByTestId('org-children-empty')
    await expect(empty).toBeVisible({ timeout: 5_000 })
    await expect(empty).toContainText('下位組織はありません')
  })
})
