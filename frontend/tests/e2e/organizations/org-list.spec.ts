import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const ORG_ID = 1

const MOCK_ORG_LIST = [
  {
    id: ORG_ID,
    name: 'テスト組織A',
    nickname1: null,
    iconUrl: null,
    prefecture: '東京都',
    city: '新宿区',
    memberCount: 10,
    supporterEnabled: false,
  },
]

const MOCK_ORG_DETAIL = {
  id: ORG_ID,
  name: 'テスト組織A',
  nameKana: null,
  nickname1: null,
  nickname2: null,
  prefecture: '東京都',
  city: '新宿区',
  description: 'E2Eテスト用組織',
  visibility: 'PUBLIC',
  supporterEnabled: false,
  version: 1,
  memberCount: 10,
  archivedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
}

async function mockOrgDetailApis(page: import('@playwright/test').Page) {
  await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_ORG_DETAIL }),
    })
  })
  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { roleName: 'MEMBER', permissions: [] } }),
    })
  })
  await page.route(`**/api/v1/organizations/${ORG_ID}/**`, async (route) => {
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

test.describe('ORG-001〜002: 組織一覧', () => {
  test('ORG-001: 組織検索ページが表示され基本要素が存在する', async ({ page }) => {
    await page.route('**/api/v1/organizations/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_ORG_LIST,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })

    await page.goto('/organizations')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '組織検索' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: '組織を作成' })).toBeVisible()
    await expect(page.getByText('テスト組織A')).toBeVisible({ timeout: 5_000 })
  })

  test('ORG-002: 検索結果が空の場合は空状態メッセージが表示される', async ({ page }) => {
    await page.route('**/api/v1/organizations/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto('/organizations')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '組織検索' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('該当する組織が見つかりませんでした')).toBeVisible({
      timeout: 5_000,
    })
  })
})

test.describe('ORG-003〜004: 組織詳細', () => {
  test('ORG-003: 組織詳細ページが表示される', async ({ page }) => {
    await mockOrgDetailApis(page)

    await page.goto(`/organizations/${ORG_ID}`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'テスト組織A' })).toBeVisible({
      timeout: 10_000,
    })
    // タブリスト確認
    await expect(page.getByRole('tab', { name: 'ダッシュボード' })).toBeVisible()
    await expect(page.getByRole('tab', { name: '基本情報' })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'メンバー' })).toBeVisible()
    await expect(page.getByRole('tab', { name: '所属チーム' })).toBeVisible()
  })

  test('ORG-004: 組織詳細の基本情報タブに組織情報が表示される', async ({ page }) => {
    await mockOrgDetailApis(page)

    await page.goto(`/organizations/${ORG_ID}`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'テスト組織A' })).toBeVisible({
      timeout: 10_000,
    })

    // 基本情報タブをクリック（初期表示はダッシュボードタブ）
    await page.getByRole('tab', { name: '基本情報' }).click()
    await expect(page.getByText('組織名')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('公開設定')).toBeVisible()
    await expect(page.getByText('メンバー数')).toBeVisible()
  })
})
