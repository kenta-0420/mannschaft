import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F09.17 Phase 11-d-4 smoke E2E (teams scope)
 *
 * <p>チーム ADMIN がチーム配下の広告キャンペーンを作成 → submit する happy path を
 * モック backend を相手に通しで疎通させる smoke スイート。
 * 組織版 (advertiser-campaign-flow.spec.ts) を複製して URL を scope='TEAM' 化したもの。</p>
 *
 * <p>backend 検証は別途 integration test で実施するため、ここでは frontend が
 * 新 URL `/api/v1/teams/{teamId}/advertiser/campaigns/messaging/*` に対して
 * 正しく POST/GET を発行することのみ検証する。</p>
 */

const TEAM_ID = 42
const CAMPAIGN_ID = 'b48b8b02-2026-7b02-9b02-bbbb00000001'

const DRAFT_CAMPAIGN = {
  id: CAMPAIGN_ID,
  scopeType: 'TEAM',
  scopeId: TEAM_ID,
  name: 'チーム春のキャンペーン',
  status: 'DRAFT',
  moderationStatus: 'PENDING',
  totalBudgetYen: 100_000,
  consumedBudgetYen: 0,
  startsAt: '2026-06-01T00:00:00Z',
  endsAt: '2026-06-30T00:00:00Z',
  scheduledTimezone: 'Asia/Tokyo',
  frequencyCapOverride: null,
  blockedReason: null,
  channels: [],
  audienceSegments: [],
  moderationLogs: [],
  createdAt: '2026-05-17T00:00:00Z',
  updatedAt: '2026-05-17T00:00:00Z',
}

function withStatus(status: string) {
  return { ...DRAFT_CAMPAIGN, status }
}

test.describe('F09.17 Phase 11-d-4: チーム広告キャンペーンフロー (smoke)', () => {
  test('チーム配下で DRAFT 作成 → submit の API 呼び出しが新 URL に向く', async ({ page }) => {
    const transitions: string[] = []
    let currentStatus = 'DRAFT'

    // 一覧 / 詳細 / 遷移を /teams/{teamId}/... に限定して捕捉
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/advertiser/campaigns/messaging**`,
      async (route) => {
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
      },
    )

    // 一覧ページが開ける
    await page.goto(`/teams/${TEAM_ID}/advertiser/messaging-campaigns`)
    await waitForHydration(page)
    await expect(page.getByText('チーム春のキャンペーン')).toBeVisible({ timeout: 10_000 })

    // submit (新 URL を直接叩く)
    currentStatus = 'DRAFT'
    const submitRes = await page.request.post(
      `/api/v1/teams/${TEAM_ID}/advertiser/campaigns/messaging/${CAMPAIGN_ID}/submit`,
    )
    expect(submitRes.ok()).toBe(true)

    expect(transitions).toEqual(['submit'])
  })
})
