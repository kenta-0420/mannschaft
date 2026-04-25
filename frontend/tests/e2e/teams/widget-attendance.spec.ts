import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/**
 * TEAM-075〜076: WidgetAttendanceResults — schedules API リクエスト形式検証
 *
 * 根本原因:
 *   GET /api/v1/teams/{id}/schedules?from=...&to=...
 *   コントローラーは LocalDateTime を受け取るため、日付のみ（"2026-03-26"）を送ると 400 になる。
 *   WidgetAttendanceResults.vue が `.slice(0, 19)` で ISO8601 日時形式（"2026-03-26T00:00:00"）を
 *   送るよう修正済みであることをここで担保する。
 */
test.describe('TEAM-075〜076: WidgetAttendanceResults schedules API パラメータ形式', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-075: schedules API の from/to パラメータが ISO 日時形式（T付き）で送信される', async ({
    page,
  }) => {
    let capturedFromParam: string | null = null
    let capturedToParam: string | null = null

    // schedules エンドポイントへのリクエストを傍受してパラメータを検証する
    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules**`, async (route) => {
      const url = new URL(route.request().url())
      capturedFromParam = url.searchParams.get('from')
      capturedToParam = url.searchParams.get('to')

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 10, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    // ウィジェットが描画されるまで少し待つ
    await page.waitForTimeout(1_000)

    // from/to が送信されていることを確認
    expect(capturedFromParam).not.toBeNull()
    expect(capturedToParam).not.toBeNull()

    // ISO 8601 日時形式（"YYYY-MM-DDTHH:mm:ss"）であることを検証
    // 日付のみ形式（"YYYY-MM-DD"）では 400 になるため、T が含まれていなければならない
    const isoDateTimePattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/
    expect(capturedFromParam).toMatch(isoDateTimePattern)
    expect(capturedToParam).toMatch(isoDateTimePattern)
  })

  test('TEAM-076: schedules API が 400 を返さずにウィジェットが正常表示される', async ({
    page,
  }) => {
    let schedulesStatus = 200

    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules**`, async (route) => {
      const url = new URL(route.request().url())
      const from = url.searchParams.get('from')
      const to = url.searchParams.get('to')

      // 日付形式チェック: 日付のみ（"YYYY-MM-DD"）ならバックエンドと同様に 400 を返す
      const isoDateTimePattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/
      if (!from || !to || !isoDateTimePattern.test(from) || !isoDateTimePattern.test(to)) {
        schedulesStatus = 400
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Invalid date format' }),
        })
      } else {
        schedulesStatus = 200
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { page: 0, size: 10, totalElements: 0, totalPages: 0 },
          }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)
    await page.waitForTimeout(1_000)

    // 400 にならず 200 が返ること
    expect(schedulesStatus).toBe(200)

    // ウィジェット内の「対象のイベントがありません」またはローディング解除後の空状態が表示される
    await expect(page.getByText('対象のイベントがありません')).toBeVisible({ timeout: 5_000 })
  })
})
