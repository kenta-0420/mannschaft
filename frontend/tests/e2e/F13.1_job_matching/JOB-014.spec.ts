import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CONTRACT_ID_MATCHED,
  buildJobContract,
  mockCatchAllApis,
  setupWorkerAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-014: nonce 再利用拒否（BE 409 相当）。
 *
 * <p>同一 nonce / token を 2 回目に送ると BE は 409 Conflict を返す。
 * 1 回目は成功、2 回目でエラートースト表示になる。</p>
 */

test.describe('JOB-014: F13.1 nonce 再利用拒否', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-014-01: 同じ shortCode を 2 回送ると 2 回目は 409 → エラートースト', async ({ page }) => {
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

    let callCount = 0
    await page.route('**/api/v1/jobs/check-ins', async (route, request) => {
      if (request.method() !== 'POST') {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      callCount++
      if (callCount === 1) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              checkInId: 1,
              contractId: CONTRACT_ID_MATCHED,
              type: 'IN',
              newStatus: 'IN_PROGRESS',
              workDurationMinutes: null,
              geoAnomaly: false,
            },
          }),
        })
      }
      else {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            error: { code: 'QR_NONCE_USED', message: 'nonce already used' },
          }),
        })
      }
    })

    // 1 回目: ページに留まらせるため、リダイレクトを確認した後に 2 回目を再現する
    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=IN`)
    await waitForHydration(page)

    await page.getByTestId('qr-scanner-tab-manual').click()
    await page.getByTestId('qr-scanner-manual-input').fill('DUP123')
    await page.getByTestId('qr-scanner-manual-submit').click()

    // 1 回目成功トーストが出て /me/jobs へ遷移する
    await expect(page.getByText('入場を記録しました')).toBeVisible({ timeout: 10_000 })
    await page.waitForURL('**/me/jobs', { timeout: 10_000 })

    // 2 回目: 再度 scan 画面へ戻り同じコードを送る
    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=IN`)
    await waitForHydration(page)
    await page.getByTestId('qr-scanner-tab-manual').click()
    await page.getByTestId('qr-scanner-manual-input').fill('DUP123')
    await page.getByTestId('qr-scanner-manual-submit').click()

    // 2 回目はエラートースト
    await expect(
      page.getByText('チェックインに失敗しました').first(),
    ).toBeVisible({ timeout: 10_000 })
    expect(callCount).toBeGreaterThanOrEqual(2)
  })
})
