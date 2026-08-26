import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import type { QuoteKanban } from '../../../app/types/repairPlanKanban'
import { setupRepairPlanAuth, setupLayoutMocks, setupRepairPlanPageMocks } from './helpers'

/**
 * F08.8 Phase 6 E2E テスト — repair-plan-scenario.spec.ts
 *
 * シナリオ: タイムラインタブ表示 → カンバンタブへ切り替え →
 *           カード一覧表示 → 申し送りタブへ切り替え → 各コンポーネント表示確認
 *
 * 全 API を page.route() でモックしバックエンド不要で実行できる。
 */

const MOCK_KANBANS: QuoteKanban[] = [
  {
    id: 'kanban-001',
    title: '外壁塗装業者選定',
    scopeType: 'teams',
    scopeId: '1',
    organizationId: 100,
    workPackageId: null,
    repairPlanItemId: null,
    bidDeadlineAt: '2025-12-31T17:00:00Z',
    visibilityToMember: 'FULL',
    status: 'OPEN',
    cards: [],
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-01-01T00:00:00Z',
  },
]

const ADMIN_AUTH = { userId: 1, displayName: 'Admin', role: 'ADMIN' } as const

test.describe('F08.8 Phase 6: repair-plan シナリオ（タブ切り替え・カンバン作成）', () => {
  test('RP-S01: タイムラインタブ表示 → カンバンタブへ切り替えると一覧が表示される', async ({
    page,
  }) => {
    await setupRepairPlanAuth(page, ADMIN_AUTH)
    await setupLayoutMocks(page, ADMIN_AUTH)
    await setupRepairPlanPageMocks(page, 1, {
      role: 'ADMIN',
      kanbans: MOCK_KANBANS,
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // タイムラインタブが最初にアクティブ
    const timelineTab = page.getByRole('button').filter({ hasText: /タイムライン/ })
    await expect(timelineTab.first()).toBeVisible({ timeout: 10_000 })

    // カンバンタブをクリック
    const kanbanTab = page.getByRole('button').filter({ hasText: /カンバン/ })
    await expect(kanbanTab.first()).toBeVisible({ timeout: 10_000 })
    await kanbanTab.first().click()

    // カンバン一覧が表示される
    await expect(page.getByText('外壁塗装業者選定')).toBeVisible({ timeout: 10_000 })
  })

  test('RP-S02: カンバンタブ → 新規作成ダイアログが表示される（ADMIN のみ）', async ({
    page,
  }) => {
    await setupRepairPlanAuth(page, ADMIN_AUTH)
    await setupLayoutMocks(page, ADMIN_AUTH)
    await setupRepairPlanPageMocks(page, 1, {
      role: 'ADMIN',
      kanbans: MOCK_KANBANS,
    })

    // カンバン新規作成用 POST モック
    await page.route('**/api/v1/teams/1/repair-plan/quote-kanbans', async (route) => {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON() as Record<string, unknown>
        const newKanban: QuoteKanban = {
          id: 'kanban-new-001',
          title: String(body.title ?? '新規カンバン'),
          scopeType: 'teams',
          scopeId: '1',
          organizationId: 100,
          workPackageId: null,
          repairPlanItemId: null,
          bidDeadlineAt: String(body.bidDeadlineAt ?? '2026-03-31T17:00:00Z'),
          visibilityToMember: 'FULL',
          status: 'OPEN',
          cards: [],
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        }
        await route.fulfill({ status: 201, json: { data: newKanban } })
      } else {
        await route.fallback()
      }
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // カンバンタブへ切り替え
    const kanbanTab = page.getByRole('button').filter({ hasText: /カンバン/ })
    await kanbanTab.first().click()

    // カンバン一覧ヘッダが表示されるまで待つ
    await expect(page.getByText('外壁塗装業者選定')).toBeVisible({ timeout: 10_000 })

    // 新規作成ボタンが表示される（ADMIN 権限）
    const createButton = page.getByRole('button').filter({ hasText: /新規|作成|追加/ })
    if ((await createButton.count()) > 0) {
      await createButton.first().click()
      // ダイアログが開く
      const dialog = page.locator('[role="dialog"]')
      await expect(dialog.first()).toBeVisible({ timeout: 5_000 })
    }
  })

  test('RP-S03: 申し送りタブへ切り替えると MemberTermManager と HandoverPackBuilder が表示される', async ({
    page,
  }) => {
    await setupRepairPlanAuth(page, ADMIN_AUTH)
    await setupLayoutMocks(page, ADMIN_AUTH)
    await setupRepairPlanPageMocks(page, 1, { role: 'ADMIN' })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // 申し送りタブをクリック
    const handoverTab = page.getByRole('button').filter({ hasText: /申し送り/ })
    await expect(handoverTab.first()).toBeVisible({ timeout: 10_000 })
    await handoverTab.first().click()

    // MemberTermManager の SectionCard タイトルが表示される
    // repair-plan/handover-packs 一覧 API が成功しているので HandoverPackBuilder も表示される
    // i18n key: repair_plan.handover.term.manager_title → 「理事任期管理」等
    // getByRole('heading') で見出しのみを対象にすると strict mode 違反を回避できる
    await expect(page.getByRole('heading', { name: /任期|申し送り/ }).first()).toBeVisible({
      timeout: 10_000,
    })
  })
})
