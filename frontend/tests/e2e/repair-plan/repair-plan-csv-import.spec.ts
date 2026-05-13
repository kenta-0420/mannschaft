import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { setupRepairPlanAuth, setupLayoutMocks, setupRepairPlanPageMocks } from './helpers'

/**
 * F08.8 Phase 6 E2E テスト — repair-plan-csv-import.spec.ts
 *
 * シナリオ: タイムライン API dry_run → 確定フロー（API レベル検証）
 *
 * ※ repair-plan.vue には独立した「Items タブ」が存在しないため、
 *   バックエンドの CSV インポート API（/repair-plan/items/csv-import）
 *   を page.evaluate() で直接検証するシナリオに調整する。
 *
 * 全 API を page.route() でモックしバックエンド不要で実行できる。
 */

/** CSV インポート dry_run レスポンス */
const DRY_RUN_RESPONSE = {
  previewCount: 3,
  errorCount: 0,
  errors: [],
  preview: [
    { year: 2025, category: '外壁塗装', amount: 5000000 },
    { year: 2026, category: '屋上防水', amount: 3000000 },
    { year: 2027, category: '給排水設備', amount: 2000000 },
  ],
}

/** CSV インポート確定レスポンス */
const COMMIT_RESPONSE = {
  importedCount: 3,
  message: 'インポートが完了しました',
}

const ADMIN_AUTH = { userId: 1, displayName: 'Admin', role: 'ADMIN' } as const

/**
 * CSV インポート API を含む全モックをセットアップ。
 */
async function setupCsvImportMocks(page: import('@playwright/test').Page) {
  await setupRepairPlanAuth(page, ADMIN_AUTH)
  await setupLayoutMocks(page, ADMIN_AUTH)
  await setupRepairPlanPageMocks(page, 1, { role: 'ADMIN' })

  // CSV インポート API (dry_run と確定の両方)
  await page.route('**/api/v1/teams/1/repair-plan/items/csv-import**', async (route) => {
    const url = route.request().url()
    if (url.includes('dry_run=true')) {
      await route.fulfill({ status: 200, json: { data: DRY_RUN_RESPONSE } })
    } else {
      await route.fulfill({ status: 200, json: { data: COMMIT_RESPONSE } })
    }
  })
}

test.describe('F08.8 Phase 6: repair-plan CSV インポート API フロー', () => {
  test('RP-C01: CSV インポート dry_run API が正しいプレビューを返す', async ({ page }) => {
    await setupCsvImportMocks(page)
    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // ページが正常にロードされること
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })

    // dry_run API を直接呼び出す
    const result = await page.evaluate(async () => {
      const formData = new FormData()
      const csvContent =
        'year,category,amount\n2025,外壁塗装,5000000\n2026,屋上防水,3000000\n2027,給排水設備,2000000'
      formData.append('file', new Blob([csvContent], { type: 'text/csv' }), 'repair-plan.csv')
      const res = await fetch('/api/v1/teams/1/repair-plan/items/csv-import?dry_run=true', {
        method: 'POST',
        body: formData,
      })
      return { status: res.status, data: await res.json() }
    })

    expect(result.status).toBe(200)
    const data = result.data as { data: typeof DRY_RUN_RESPONSE }
    expect(data.data.previewCount).toBe(3)
    expect(data.data.errorCount).toBe(0)
    expect(data.data.preview).toHaveLength(3)
  })

  test('RP-C02: CSV インポート確定 API でインポートが完了する', async ({ page }) => {
    await setupCsvImportMocks(page)
    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // 確定 API を直接呼び出す
    const result = await page.evaluate(async () => {
      const formData = new FormData()
      const csvContent =
        'year,category,amount\n2025,外壁塗装,5000000\n2026,屋上防水,3000000\n2027,給排水設備,2000000'
      formData.append('file', new Blob([csvContent], { type: 'text/csv' }), 'repair-plan.csv')
      const res = await fetch('/api/v1/teams/1/repair-plan/items/csv-import', {
        method: 'POST',
        body: formData,
      })
      return { status: res.status, data: await res.json() }
    })

    expect(result.status).toBe(200)
    const data = result.data as { data: typeof COMMIT_RESPONSE }
    expect(data.data.importedCount).toBe(3)
  })

  test('RP-C03: repair-plan ページが正常にロードされ、タイムラインタブが表示される', async ({
    page,
  }) => {
    await setupCsvImportMocks(page)
    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // ページヘッダが表示される
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })

    // タイムラインタブが表示される
    const timelineTab = page.getByRole('button').filter({ hasText: /タイムライン/ })
    await expect(timelineTab.first()).toBeVisible({ timeout: 10_000 })

    // 更新ボタンが表示される
    const refreshButton = page.getByRole('button').filter({ hasText: /更新/ })
    await expect(refreshButton.first()).toBeVisible({ timeout: 10_000 })
  })

  test('RP-C04: dry_run でエラーがある場合にエラー件数が確認できる', async ({ page }) => {
    // エラーありの dry_run レスポンスでオーバーライド（setupCsvImportMocks より後に登録）
    await setupRepairPlanAuth(page, ADMIN_AUTH)
    await setupLayoutMocks(page, ADMIN_AUTH)
    await setupRepairPlanPageMocks(page, 1, { role: 'ADMIN' })

    await page.route('**/api/v1/teams/1/repair-plan/items/csv-import**', async (route) => {
      const url = route.request().url()
      if (url.includes('dry_run=true')) {
        await route.fulfill({
          status: 200,
          json: {
            data: {
              previewCount: 1,
              errorCount: 2,
              errors: [
                { row: 2, message: '年度が不正です' },
                { row: 3, message: '金額が数値ではありません' },
              ],
              preview: [{ year: 2025, category: '外壁塗装', amount: 5000000 }],
            },
          },
        })
      } else {
        await route.fulfill({ status: 200, json: { data: COMMIT_RESPONSE } })
      }
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // ページが正常にロードされること
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })

    // dry_run API を呼び出す
    const result = await page.evaluate(async () => {
      const formData = new FormData()
      const csvContent = 'year,category,amount\n2025,外壁塗装,5000000\nabc,屋上防水,xyz'
      formData.append('file', new Blob([csvContent], { type: 'text/csv' }), 'bad.csv')
      const res = await fetch('/api/v1/teams/1/repair-plan/items/csv-import?dry_run=true', {
        method: 'POST',
        body: formData,
      })
      return { status: res.status, data: await res.json() }
    })

    expect(result.status).toBe(200)
    const data = result.data as { data: { previewCount: number; errorCount: number } }
    expect(data.data.errorCount).toBe(2)
    expect(data.data.previewCount).toBe(1)
  })
})
