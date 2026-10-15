import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CONTRACT_ID_MATCHED,
  buildJobContract,
  mockCatchAllApis,
  setupWorkerAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-013: 失効トークン拒否（BE 400 / 410 相当）。
 *
 * <p>{@code POST /api/v1/jobs/check-ins} に失効済み JWT / 期限切れ shortCode を送ると
 * BE は 400 を返す。FE はエラートーストで通知する（submit 押した画面に留まる）。</p>
 */

test.describe('JOB-013: F13.1 失効トークン拒否', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-013-01: 失効コード送信で BE 400 → エラートースト表示', async ({ page }) => {
    await mockCatchAllApis(page)

    await page.route(`**/api/v1/contracts/${CONTRACT_ID_MATCHED}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: buildJobContract({ id: CONTRACT_ID_MATCHED, status: 'MATCHED' }),
        }),
      })
    })

    await page.route('**/api/v1/jobs/check-ins', async (route, request) => {
      if (request.method() !== 'POST') {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          error: { code: 'QR_TOKEN_EXPIRED', message: 'QR token expired' },
        }),
      })
    })

    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=IN`)
    await waitForHydration(page)

    await page.getByTestId('qr-scanner-tab-manual').click()
    await page.getByTestId('qr-scanner-manual-input').fill('EXPIRE')
    await page.getByTestId('qr-scanner-manual-submit').click()

    // 失敗トースト（i18n jobmatching.qr.scanner.submitted.failedTitle = "チェックインに失敗しました"）
    await expect(
      page.getByText('チェックインに失敗しました').first(),
    ).toBeVisible({ timeout: 10_000 })

    // 画面に留まる（/me/jobs へ遷移していない）
    expect(page.url()).toContain('/scan')
  })
})
