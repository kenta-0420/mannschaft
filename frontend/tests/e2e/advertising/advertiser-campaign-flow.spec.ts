import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F09.17 Phase 11-c-5 smoke E2E
 *
 * 広告主のメッセージ型キャンペーン CRUD + 状態遷移 happy path:
 * DRAFT 作成 → submit → APPROVED 後 launch → pause → resume を
 * モック backend を相手に通しで疎通させる smoke スイート。
 *
 * 注: エッジケース（バリデーション失敗、403、429 等）は別 Phase で追加予定。
 */

const ORG_ID = 1
const CAMPAIGN_ID = 'a37a7a01-2026-7a01-9a01-aaaa00000001'

const DRAFT_CAMPAIGN = {
  id: CAMPAIGN_ID,
  organizationId: ORG_ID,
  name: '春のキャンペーン',
  status: 'DRAFT',
  totalBudgetJpy: 100_000,
  consumedBudgetJpy: 0,
  startsAt: '2026-06-01T00:00:00Z',
  endsAt: '2026-06-30T00:00:00Z',
  scheduledTimezone: 'Asia/Tokyo',
  frequencyCapOverride: null,
  channels: [],
  segments: [],
  moderationLogs: [],
  createdAt: '2026-05-17T00:00:00Z',
  updatedAt: '2026-05-17T00:00:00Z',
}

function withStatus(status: string) {
  return { ...DRAFT_CAMPAIGN, status }
}

test.describe('F09.17 Phase 11-c-5: 広告主キャンペーンフロー (smoke)', () => {
  test('DRAFT 作成 → submit → launch → pause → resume の状態遷移が可能', async ({ page }) => {
    const transitions: string[] = []
    let currentStatus = 'DRAFT'

    // 一覧 / 詳細
    await page.route('**/api/v1/advertiser/campaigns/messaging**', async (route) => {
      const url = route.request().url()
      const method = route.request().method()

      // 状態遷移エンドポイント
      const match = url.match(/messaging\/[^/]+\/(submit|launch|pause|resume|cancel)/)
      if (match && method === 'POST') {
        const action = match[1] ?? ''
        transitions.push(action)
        const transitionMap: Record<string, string> = {
          submit: 'REVIEW',
          launch: 'DELIVERING',
          pause: 'PAUSED',
          resume: 'DELIVERING',
          cancel: 'CANCELLED',
        }
        const newStatus = transitionMap[action]
        if (newStatus) currentStatus = newStatus
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: withStatus(currentStatus) }),
        })
        return
      }

      // 詳細 GET
      if (url.includes(`/messaging/${CAMPAIGN_ID}`) && method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: withStatus(currentStatus) }),
        })
        return
      }

      // 一覧 GET
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [withStatus(currentStatus)],
            meta: { totalElements: 1, page: 0, size: 20, totalPages: 1 },
          }),
        })
        return
      }

      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    // 一覧ページが開ける
    await page.goto(`/organizations/${ORG_ID}/advertiser/messaging-campaigns`)
    await waitForHydration(page)
    await expect(page.getByText('春のキャンペーン')).toBeVisible({ timeout: 10_000 })

    // submit
    currentStatus = 'DRAFT'
    const submitRes = await page.request.post(
      `/api/v1/advertiser/campaigns/messaging/${CAMPAIGN_ID}/submit?organizationId=${ORG_ID}`,
    )
    expect(submitRes.ok()).toBe(true)

    // launch（バックエンド側で APPROVED 後を想定）
    currentStatus = 'APPROVED'
    const launchRes = await page.request.post(
      `/api/v1/advertiser/campaigns/messaging/${CAMPAIGN_ID}/launch?organizationId=${ORG_ID}`,
    )
    expect(launchRes.ok()).toBe(true)

    // pause
    const pauseRes = await page.request.post(
      `/api/v1/advertiser/campaigns/messaging/${CAMPAIGN_ID}/pause?organizationId=${ORG_ID}`,
    )
    expect(pauseRes.ok()).toBe(true)

    // resume
    const resumeRes = await page.request.post(
      `/api/v1/advertiser/campaigns/messaging/${CAMPAIGN_ID}/resume?organizationId=${ORG_ID}`,
    )
    expect(resumeRes.ok()).toBe(true)

    expect(transitions).toEqual(['submit', 'launch', 'pause', 'resume'])
  })
})
