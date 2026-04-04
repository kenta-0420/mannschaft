import { test, expect } from '@playwright/test'

// chromium プロジェクトの storageState（認証済みユーザー）を使用
// MY ページはすべて表示確認のみ（APIエラー時も見出しは表示される）

test.describe('MY-001〜006: マイページ系', () => {
  test('MY-001: マイシフトページが表示される', async ({ page }) => {
    await page.goto('/my/shifts')
    await expect(page.getByRole('heading', { name: 'マイシフト' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('MY-002: マイ予約ページが表示される', async ({ page }) => {
    await page.goto('/my/reservations')
    await expect(page.getByRole('heading', { name: 'マイ予約' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('MY-003: マイパフォーマンスページが表示される', async ({ page }) => {
    await page.goto('/my/performance')
    await expect(page.getByRole('heading', { name: 'マイパフォーマンス' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('MY-004: マイサービス履歴ページが表示される', async ({ page }) => {
    await page.goto('/my/service-records')
    await expect(page.getByRole('heading', { name: 'マイサービス履歴' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('MY-005: マイカルテページが表示される', async ({ page }) => {
    await page.goto('/my/charts')
    await expect(page.getByRole('heading', { name: 'マイカルテ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('MY-006: オンボーディングページが表示される', async ({ page }) => {
    await page.goto('/my/onboarding')
    await expect(page.getByRole('heading', { name: 'オンボーディング' })).toBeVisible({
      timeout: 10_000,
    })
  })
})
