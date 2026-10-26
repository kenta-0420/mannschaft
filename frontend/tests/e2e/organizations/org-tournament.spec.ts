import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const ORG_ID = 1

const MOCK_TOURNAMENTS = [
  {
    id: 1,
    title: '2026年度春季リーグ',
    status: 'OPEN',
    format: 'LEAGUE',
    sportCategory: 'サッカー',
    seasonYear: 2026,
    winPoints: 3,
    drawPoints: 1,
    lossPoints: 0,
    divisions: [{ id: 1, name: 'Aリーグ' }],
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    title: '2026年度カップ戦',
    status: 'DRAFT',
    format: 'KNOCKOUT',
    sportCategory: 'サッカー',
    seasonYear: 2026,
    winPoints: 1,
    drawPoints: 0,
    lossPoints: 0,
    divisions: [],
    createdAt: '2026-02-01T00:00:00Z',
  },
]

test.describe('ORG-007: 大会・リーグ一覧', () => {
  test('ORG-007: 大会一覧ページが表示され大会カードが表示される', async ({ page }) => {
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TOURNAMENTS }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: '大会を作成' })).toBeVisible()
    await expect(page.getByText('2026年度春季リーグ')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('2026年度カップ戦')).toBeVisible()
  })

  test('ORG-008: 大会が空の場合は空状態メッセージが表示される', async ({ page }) => {
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('大会がありません')).toBeVisible({ timeout: 5_000 })
  })

  test('ORG-009: 大会カードにステータスとフォーマットが表示される', async ({ page }) => {
    await page.route(`**/api/v1/organizations/${ORG_ID}/tournaments**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TOURNAMENTS }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/tournaments`)
    await waitForHydration(page)

    await expect(page.getByText('2026年度春季リーグ')).toBeVisible({ timeout: 10_000 })
    // ステータスとフォーマット表示確認
    await expect(page.getByText('OPEN').first()).toBeVisible()
    await expect(page.getByText('リーグ戦')).toBeVisible()
    await expect(page.getByText('トーナメント')).toBeVisible()
  })
})
