import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CONTRACT_ID_MATCHED,
  buildJobContract,
  buildQrTokenResponse,
  mockCatchAllApis,
  setupRequesterAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-009: Requester が QR を発行すると shortCode が表示され、
 * 60 秒手前で自動ローテーションされる。
 *
 * <p>Phase 13.1.2 QR 表示画面（/contracts/:id/qr?type=IN）を対象とする。
 * 実時間で 60 秒待つのは非現実的なので、モックが返す {@code expiresAt} を短めにして
 * {@link #startAutoRotation} の再発行が実際に走ることを確認する。</p>
 */

test.describe('JOB-009: F13.1 QR 発行と自動ローテーション', () => {
  test.beforeEach(async ({ page }) => {
    await setupRequesterAuth(page)
  })

  test('JOB-009-01: QR 表示画面で shortCode が表示され、短 TTL で再発行が連続する', async ({ page }) => {
    await mockCatchAllApis(page)

    // 契約取得
    const contract = buildJobContract({ id: CONTRACT_ID_MATCHED, status: 'MATCHED' })
    await page.route(`**/api/v1/contracts/${CONTRACT_ID_MATCHED}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: contract }),
      })
    })

    // QR トークン発行（POST）。呼び出し回数に応じて shortCode を変えてローテーションを可視化する。
    let issueCount = 0
    const codes = ['ABC123', 'XYZ789', 'MNP456']
    await page.route(
      `**/api/v1/contracts/${CONTRACT_ID_MATCHED}/qr-tokens`,
      async (route, request) => {
        if (request.method() !== 'POST') {
          await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
          return
        }
        const shortCode = codes[Math.min(issueCount, codes.length - 1)]
        issueCount++
        // 7 秒 TTL（startAutoRotation は expiresAt-5s で再発行 → 2 秒ごとにローテ）
        const now = Date.now()
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildQrTokenResponse({
              shortCode,
              type: 'IN',
              issuedAt: new Date(now).toISOString(),
              expiresAt: new Date(now + 7_000).toISOString(),
            }),
          }),
        })
      },
    )

    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/qr?type=IN`)
    await waitForHydration(page)

    // 初回発行の shortCode が表示される
    const shortCodeEl = page.getByTestId('qr-short-code')
    await expect(shortCodeEl).toContainText('ABC123', { timeout: 10_000 })

    // 残秒表示が出る
    await expect(page.getByTestId('qr-remaining-seconds')).toBeVisible({ timeout: 5_000 })

    // ローテーション: expiresAt-5s = 2s 後に再発行
    await expect(shortCodeEl).toContainText('XYZ789', { timeout: 10_000 })

    // 2 回以上発行されていることを確認
    expect(issueCount).toBeGreaterThanOrEqual(2)
  })
})
