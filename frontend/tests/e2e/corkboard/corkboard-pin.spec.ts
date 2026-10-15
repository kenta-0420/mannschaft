import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { mockCatchAllApis, setupOwnerAuth } from './_helpers'

/**
 * F09.8.1 ピン止め E2E — マイコルクボード（{@code /my/corkboard}）。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>CORK-PIN-001: マイコルクボードページが表示される</li>
 *   <li>CORK-PIN-002: ピン止めカード一覧が表示される（GET /pinned-cards）</li>
 *   <li>CORK-PIN-003: 📌 ボタン押下でピン解除 (PATCH .../pin {isPinned: false}) を呼ぶ</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F09.8.1_corkboard_pin_dashboard.md</p>
 */

interface PinnedItemPayload {
  cardId: number
  corkboardId: number
  corkboardName: string
  cardType: string
  colorLabel: string
  title: string | null
  body: string | null
  userNote: string | null
  pinnedAt: string
  reference: null | {
    type: string
    id: number
    snapshotTitle: string | null
    snapshotExcerpt: string | null
    isAccessible: boolean
    isDeleted: boolean
    navigateTo: string | null
  }
}

function buildPinnedItem(
  cardId: number,
  overrides: Partial<PinnedItemPayload> = {},
): PinnedItemPayload {
  return {
    cardId,
    corkboardId: 100,
    corkboardName: '仕事メモ',
    cardType: 'MEMO',
    colorLabel: 'YELLOW',
    title: 'ピン止めメモ',
    body: 'メモ本文',
    userNote: null,
    pinnedAt: '2026-05-03T14:23:00Z',
    reference: null,
    ...overrides,
  }
}

async function mockPinnedCardsApi(
  page: import('@playwright/test').Page,
  items: PinnedItemPayload[],
): Promise<void> {
  await page.route('**/api/v1/users/me/corkboards/pinned-cards**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            items,
            nextCursor: null,
            totalCount: items.length,
          },
        }),
      })
    } else {
      await route.continue()
    }
  })
}

test.describe('CORK-PIN: マイコルクボード（横断ピン止め）', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
  })

  test('CORK-PIN-001: ページが表示される（空）', async ({ page }) => {
    await mockPinnedCardsApi(page, [])
    await page.goto('/my/corkboard')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: /マイコルクボード/ })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('CORK-PIN-002: ピン止めカード一覧が表示される', async ({ page }) => {
    const items = [
      buildPinnedItem(101, { title: 'カードA', body: '本文A' }),
      buildPinnedItem(102, { title: 'カードB', body: '本文B' }),
    ]
    await mockPinnedCardsApi(page, items)
    await page.goto('/my/corkboard')
    await waitForHydration(page)

    await expect(page.getByText('カードA')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('カードB')).toBeVisible()
  })

  test('CORK-PIN-003: 📌 ボタンでピン解除を呼ぶ', async ({ page }) => {
    const items = [buildPinnedItem(101, { title: '解除対象', body: '...' })]
    await mockPinnedCardsApi(page, items)

    let pinPatched: { isPinned?: boolean } | null = null
    await page.route('**/api/v1/corkboards/100/cards/101/pin', async (route) => {
      if (route.request().method() === 'PATCH') {
        pinPatched = route.request().postDataJSON() as { isPinned?: boolean }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { id: 101, isPinned: false, pinnedAt: null },
          }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto('/my/corkboard')
    await waitForHydration(page)

    await expect(page.getByText('解除対象')).toBeVisible({ timeout: 10_000 })
    // aria-label でピン解除ボタンを取得
    await page.getByRole('button', { name: 'ピン止めを外す' }).first().click()

    await expect.poll(() => pinPatched, { timeout: 5_000 }).not.toBeNull()
    expect(pinPatched).toEqual({ isPinned: false })
  })
})
