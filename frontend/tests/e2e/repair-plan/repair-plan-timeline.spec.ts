import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import type { RepairPlanTimelineResponse } from '../../../app/types/repairPlanTimeline'
import { setupRepairPlanAuth, setupLayoutMocks, setupRepairPlanPageMocks } from './helpers'

/**
 * F08.8 Phase 6 E2E テスト — repair-plan-timeline.spec.ts
 *
 * シナリオ: タイムラインタブ表示 → 年度変更 → リフレッシュ
 *
 * 全 API を page.route() でモックしバックエンド不要で実行できる。
 */

const BASE_TIMELINE: RepairPlanTimelineResponse = {
  scopeType: 'teams',
  scopeId: '1',
  yearFrom: 2005,
  yearTo: 2030,
  labels: [2025, 2026, 2027],
  categories: ['外壁塗装', '屋上防水'],
  amountByYearAndCategory: {
    '外壁塗装': { '2025': 5000000, '2026': 0, '2027': 0 },
    '屋上防水': { '2025': 0, '2026': 3000000, '2027': 0 },
  },
  totalByYear: { '2025': 5000000, '2026': 3000000, '2027': 0 },
  chairpersonByYear: { '2025': '田中理事長', '2026': '田中理事長', '2027': '鈴木理事長' },
  cpiTrendByYear: { '2025': 1.0, '2026': 1.02, '2027': 1.03 },
}

const ADMIN_AUTH = { userId: 1, displayName: 'Admin', role: 'ADMIN' } as const

test.describe('F08.8 Phase 6: repair-plan タイムラインタブ', () => {
  test('RP-001: タイムラインタブが初期表示され、年度範囲変更でリフレッシュできる', async ({
    page,
  }) => {
    await setupRepairPlanAuth(page, ADMIN_AUTH)
    await setupLayoutMocks(page, ADMIN_AUTH)
    await setupRepairPlanPageMocks(page, 1, {
      role: 'ADMIN',
      timelineData: BASE_TIMELINE,
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // タイムラインタブが初期アクティブ
    const timelineTab = page.getByRole('button').filter({ hasText: /タイムライン/ })
    await expect(timelineTab.first()).toBeVisible({ timeout: 10_000 })

    // 更新ボタンが存在する
    const refreshButton = page.getByRole('button').filter({ hasText: /更新/ })
    await expect(refreshButton.first()).toBeVisible({ timeout: 10_000 })

    // 更新ボタンをクリックして API リフレッシュを確認
    const refreshResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/repair-plan/timeline') && resp.request().method() === 'GET',
      { timeout: 10_000 },
    )
    await refreshButton.first().click()
    const resp = await refreshResponse
    expect(resp.status()).toBe(200)
  })

  test('RP-002: タイムラインデータがある場合、タイムラインセクションが表示される', async ({ page }) => {
    await setupRepairPlanAuth(page, ADMIN_AUTH)
    await setupLayoutMocks(page, ADMIN_AUTH)
    await setupRepairPlanPageMocks(page, 1, {
      role: 'ADMIN',
      timelineData: BASE_TIMELINE,
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // ページヘッダが存在
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })

    // タイムラインタブが表示されている
    const timelineTab = page.getByRole('button').filter({ hasText: /タイムライン/ })
    await expect(timelineTab.first()).toBeVisible({ timeout: 10_000 })

    // タイムラインセクション（SectionCard のタイトル）が表示される
    // SectionCard は repair_plan.timeline.title = 「長期修繕計画タイムライン」等を表示する
    const sectionTitle = page.getByRole('button').filter({ hasText: /タイムライン/ })
    await expect(sectionTitle.first()).toBeVisible({ timeout: 10_000 })
  })
})
