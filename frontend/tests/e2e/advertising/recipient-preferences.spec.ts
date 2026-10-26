import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F09.17 Phase 11-c-5 smoke E2E
 *
 * 受信者の広告受信設定ページで、4 チャネル別トグル (announcement / email / push / banner)
 * を切り替えて保存する happy path を検証する。
 */

const INITIAL_PREFS = {
  id: '11111111-2222-3333-4444-555555555555',
  acceptAnnouncementAds: true,
  acceptEmailAds: true,
  acceptPushAds: true,
  acceptBannerAds: true,
  blockedAdvertiserAccountIds: [],
  consentedAt: '2026-05-01T00:00:00Z',
  unsubscribeTokenVersion: 1,
  updatedAt: '2026-05-01T00:00:00Z',
}

interface AdPreferencesUpdateBody {
  acceptAnnouncementAds?: boolean
  acceptEmailAds?: boolean
  acceptPushAds?: boolean
  acceptBannerAds?: boolean
  blockedAdvertiserAccountIds?: number[]
  rotateUnsubscribeTokens?: boolean
}

test.describe('F09.17 Phase 11-c-5: 受信者 広告受信設定 (smoke)', () => {
  test('チャネル 4 トグルを切替えて保存 PUT が走る', async ({ page }) => {
    let putCalled = false
    let lastBody: AdPreferencesUpdateBody | null = null

    await page.route('**/api/v1/me/ad-preferences', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: INITIAL_PREFS }),
        })
        return
      }
      if (method === 'PUT') {
        putCalled = true
        try {
          lastBody = JSON.parse(route.request().postData() ?? '{}') as AdPreferencesUpdateBody
        }
        catch {
          lastBody = null
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              ...INITIAL_PREFS,
              ...(lastBody ?? {}),
              updatedAt: '2026-05-17T00:00:00Z',
            },
          }),
        })
        return
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/settings/ad-preferences')
    await waitForHydration(page)

    // 4 つのトグルが描画されている
    await expect(page.getByTestId('toggle-announcement')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('toggle-email')).toBeVisible()
    await expect(page.getByTestId('toggle-push')).toBeVisible()
    await expect(page.getByTestId('toggle-banner')).toBeVisible()

    // メール広告のトグルをオフに（クリックで切り替わる）
    await page.getByTestId('toggle-email').click()
    // プッシュ広告のトグルをオフに
    await page.getByTestId('toggle-push').click()

    // 保存ボタン押下
    await page.getByTestId('save-button').click()

    await expect.poll(() => putCalled, { timeout: 5_000 }).toBe(true)
    // 送信ボディに boolean フィールドが含まれていること
    const body = lastBody as AdPreferencesUpdateBody | null
    expect(body).not.toBeNull()
    expect(typeof body?.acceptEmailAds).toBe('boolean')
    expect(typeof body?.acceptPushAds).toBe('boolean')
  })
})
