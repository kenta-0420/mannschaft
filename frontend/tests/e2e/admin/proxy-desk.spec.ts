import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const mockConsents = [
  {
    id: 1,
    subjectUserId: 101,
    proxyUserId: 200,
    orgId: 10,
    consentMethod: 'PAPER_SIGNED',
    effectiveFrom: '2026-01-01',
    effectiveUntil: '2026-12-31',
    approvedAt: '2026-01-15T10:00:00',
    revokedAt: null,
    scopes: ['SURVEY', 'SCHEDULE_ATTENDANCE'],
  },
]

test.describe('PROXY-DESK-001: 代理入力デスク', () => {
  test('PROXY-DESK-001-1: ページタイトルが表示される', async ({ page }) => {
    await page.route('**/api/v1/proxy-input-consents/active', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: mockConsents }),
      })
    })

    await page.goto('/admin/proxy-desk')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '代理入力デスク' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('PROXY-DESK-001-2: 同意書一覧が表示される', async ({ page }) => {
    await page.route('**/api/v1/proxy-input-consents/active', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: mockConsents }),
      })
    })

    await page.goto('/admin/proxy-desk')
    await waitForHydration(page)

    await expect(page.getByText('代理入力同意書 #1')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('紙面署名')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('アンケート')).toBeVisible({ timeout: 10_000 })
  })

  test('PROXY-DESK-001-3: 有効同意書がない場合は「有効な同意書がありません」が表示される', async ({
    page,
  }) => {
    await page.route('**/api/v1/proxy-input-consents/active', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/admin/proxy-desk')
    await waitForHydration(page)

    await expect(page.getByText('有効な同意書がありません')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: '住民をピン留め' })).toBeDisabled({
      timeout: 10_000,
    })
  })

  test('PROXY-DESK-001-4: 同意書を選択してピン留めすると稼働中バナーが表示される', async ({
    page,
  }) => {
    await page.route('**/api/v1/proxy-input-consents/active', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: mockConsents }),
      })
    })

    await page.goto('/admin/proxy-desk')
    await waitForHydration(page)

    // 同意書アイテムをクリックして選択
    await page.getByText('代理入力同意書 #1').click()

    // ピン留めボタンをクリック
    await page.getByRole('button', { name: '住民をピン留め' }).click()

    // 稼働中バナーが表示されること
    await expect(page.getByText('代理入力モード稼働中')).toBeVisible({ timeout: 10_000 })

    // 住民をピン留めボタンが消えてピン留め解除ボタンが表示されること
    await expect(page.getByRole('button', { name: '住民をピン留め' })).not.toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'ピン留め解除' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('PROXY-DESK-001-5: ピン留め解除ボタンで解除できる', async ({ page }) => {
    await page.route('**/api/v1/proxy-input-consents/active', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: mockConsents }),
      })
    })

    await page.goto('/admin/proxy-desk')
    await waitForHydration(page)

    // テスト4と同じ手順でピン留め
    await page.getByText('代理入力同意書 #1').click()
    await page.getByRole('button', { name: '住民をピン留め' }).click()
    await expect(page.getByText('代理入力モード稼働中')).toBeVisible({ timeout: 10_000 })

    // ピン留め解除ボタンをクリック
    await page.getByRole('button', { name: 'ピン留め解除' }).click()

    // 稼働中バナーが消えること
    await expect(page.getByText('代理入力モード稼働中')).not.toBeVisible({ timeout: 10_000 })

    // 住民をピン留めボタンが再表示されること
    await expect(page.getByRole('button', { name: '住民をピン留め' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('PROXY-DESK-001-6: ページリロード後にピン留め状態が localStorage から復元される', async ({
    page,
  }) => {
    await page.route('**/api/v1/proxy-input-consents/active', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: mockConsents }),
      })
    })

    await page.goto('/admin/proxy-desk')
    await waitForHydration(page)

    // テスト4と同じ手順でピン留め
    await page.getByText('代理入力同意書 #1').click()
    await page.getByRole('button', { name: '住民をピン留め' }).click()
    await expect(page.getByText('代理入力モード稼働中')).toBeVisible({ timeout: 10_000 })

    // ページリロード
    await page.reload()
    await waitForHydration(page)

    // localStorage から復元されて稼働中バナーが表示されていること
    await expect(page.getByText('代理入力モード稼働中')).toBeVisible({ timeout: 10_000 })
  })
})
