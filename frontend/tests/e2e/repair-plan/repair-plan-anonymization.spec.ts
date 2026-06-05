import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import type { QuoteKanban, QuoteCard } from '../../../app/types/repairPlanKanban'
import { setupRepairPlanAuth, setupLayoutMocks, setupRepairPlanPageMocks } from './helpers'

/**
 * F08.8 Phase 6 E2E テスト — repair-plan-anonymization.spec.ts
 *
 * シナリオ: 住民ロール（MEMBER） → 業者名匿名化表示・金額レンジ表示確認
 *
 * visibilityToMember=ANONYMIZED のカンバンで:
 * - vendorNameSnapshot が null
 * - amountLabel が「〜100万円」等のレンジ文字列
 *
 * 全 API を page.route() でモックしバックエンド不要で実行できる。
 */

/** ANONYMIZED カード: 業者名非公開、金額はレンジ表示 */
const ANONYMIZED_CARDS: QuoteCard[] = [
  {
    id: 'card-anon-001',
    kanbanId: 'kanban-anon-001',
    vendorId: 10,
    vendorNameSnapshot: null, // ANONYMIZED では null
    stage: 'SHORTLISTED',
    amount: null,
    amountLabel: '〜100万円', // レンジ文字列
    complianceCheckStatus: 'PASSED',
    displayOrder: 0,
    createdAt: '2025-01-01T00:00:00Z',
  },
  {
    id: 'card-anon-002',
    kanbanId: 'kanban-anon-001',
    vendorId: 20,
    vendorNameSnapshot: null,
    stage: 'RECEIVED',
    amount: null,
    amountLabel: '100〜200万円',
    complianceCheckStatus: 'UNCHECKED',
    displayOrder: 1,
    createdAt: '2025-01-02T00:00:00Z',
  },
  {
    id: 'card-anon-003',
    kanbanId: 'kanban-anon-001',
    vendorId: 30,
    vendorNameSnapshot: null,
    stage: 'UNDER_REVIEW',
    amount: null,
    amountLabel: '200〜500万円',
    complianceCheckStatus: 'UNCHECKED',
    displayOrder: 2,
    createdAt: '2025-01-03T00:00:00Z',
  },
]

/** ANONYMIZED カンバン */
const ANONYMIZED_KANBAN: QuoteKanban = {
  id: 'kanban-anon-001',
  title: '外壁塗装業者選定（匿名）',
  scopeType: 'teams',
  scopeId: '1',
  organizationId: 100,
  workPackageId: null,
  repairPlanItemId: null,
  bidDeadlineAt: '2025-12-31T17:00:00Z',
  visibilityToMember: 'ANONYMIZED',
  status: 'OPEN',
  cards: ANONYMIZED_CARDS,
  createdAt: '2025-01-01T00:00:00Z',
  updatedAt: '2025-01-01T00:00:00Z',
}

const MEMBER_AUTH = { userId: 99, displayName: '住民A', role: 'MEMBER' } as const

test.describe('F08.8 Phase 6: repair-plan 匿名化表示（MEMBER ロール）', () => {
  test('RP-A01: MEMBER として Kanban タブを開くとカンバン一覧が表示される', async ({ page }) => {
    await setupRepairPlanAuth(page, MEMBER_AUTH)
    await setupLayoutMocks(page, MEMBER_AUTH)
    await setupRepairPlanPageMocks(page, 1, {
      role: 'MEMBER',
      kanbans: [ANONYMIZED_KANBAN],
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // カンバンタブへ切り替え
    const kanbanTab = page.getByRole('button').filter({ hasText: /カンバン/ })
    await expect(kanbanTab.first()).toBeVisible({ timeout: 10_000 })
    await kanbanTab.first().click()

    // ANONYMIZED カンバンのタイトルが表示される
    await expect(page.getByText('外壁塗装業者選定（匿名）')).toBeVisible({ timeout: 10_000 })
  })

  test('RP-A02: ANONYMIZED カンバンを開くと業者名が非表示（null 表示）になる', async ({
    page,
  }) => {
    await setupRepairPlanAuth(page, MEMBER_AUTH)
    await setupLayoutMocks(page, MEMBER_AUTH)
    await setupRepairPlanPageMocks(page, 1, {
      role: 'MEMBER',
      kanbans: [ANONYMIZED_KANBAN],
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // カンバンタブへ
    const kanbanTab = page.getByRole('button').filter({ hasText: /カンバン/ })
    await kanbanTab.first().click()

    // ANONYMIZED カンバンをクリック
    const kanbanCard = page.getByText('外壁塗装業者選定（匿名）')
    await expect(kanbanCard).toBeVisible({ timeout: 10_000 })
    await kanbanCard.click()

    // 業者名スナップショットが null なので vendor_hidden = "（非表示）" が表示される
    // QuoteKanbanCard.vue: vendorNameSnapshot===null → t('repair_plan.kanban.card.vendor_hidden')
    // ja/repair_plan.json: vendor_hidden = "（非表示）"
    const hiddenVendorLabel = page.getByText('（非表示）')
    const count = await hiddenVendorLabel.count()
    // 匿名カードが 3 件あるので、（非表示）が複数現れる
    expect(count).toBeGreaterThanOrEqual(1)
  })

  test('RP-A03: ANONYMIZED カンバンでは金額がレンジ表示（amountLabel）になる', async ({ page }) => {
    await setupRepairPlanAuth(page, MEMBER_AUTH)
    await setupLayoutMocks(page, MEMBER_AUTH)
    await setupRepairPlanPageMocks(page, 1, {
      role: 'MEMBER',
      kanbans: [ANONYMIZED_KANBAN],
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // カンバンタブへ
    const kanbanTab = page.getByRole('button').filter({ hasText: /カンバン/ })
    await kanbanTab.first().click()

    // ANONYMIZED カンバンをクリック
    const kanbanCard = page.getByText('外壁塗装業者選定（匿名）')
    await expect(kanbanCard).toBeVisible({ timeout: 10_000 })
    await kanbanCard.click()

    // amountLabel が表示される（レンジ文字列）
    await expect(page.getByText('〜100万円')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('100〜200万円')).toBeVisible({ timeout: 10_000 })
  })

  test('RP-A04: MEMBER ロールでは新規作成ボタンが非表示になる', async ({ page }) => {
    await setupRepairPlanAuth(page, MEMBER_AUTH)
    await setupLayoutMocks(page, MEMBER_AUTH)
    await setupRepairPlanPageMocks(page, 1, {
      role: 'MEMBER',
      kanbans: [ANONYMIZED_KANBAN],
    })

    await page.goto('/teams/1/repair-plan')
    await waitForHydration(page)

    // カンバンタブへ
    const kanbanTab = page.getByRole('button').filter({ hasText: /カンバン/ })
    await kanbanTab.first().click()

    // カンバン一覧表示を待つ
    await expect(page.getByText('外壁塗装業者選定（匿名）')).toBeVisible({ timeout: 10_000 })

    // MEMBER は isAdminOrDeputy = false なので新規作成ボタンが非表示
    // repair-plan.vue: v-if="isAdminOrDeputy" で条件付き表示
    const createButton = page.getByRole('button').filter({ hasText: /新規作成|カンバン作成/ })
    // ボタンが存在しないか非表示であることを確認
    if ((await createButton.count()) > 0) {
      await expect(createButton.first()).toBeHidden()
    } else {
      expect(await createButton.count()).toBe(0)
    }
  })
})
