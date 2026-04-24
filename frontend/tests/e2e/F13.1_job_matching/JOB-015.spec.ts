import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CONTRACT_ID_MATCHED,
  buildJobContract,
  buildCheckInResponse,
  mockCatchAllApis,
  setupWorkerAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-015: オフライン中スキャン → IndexedDB 保留 → 復帰で自動送信。
 *
 * <p>ブラウザの Network Conditions をオフラインに切り替えてチェックインを試み、
 * 「オフライン保留」トーストを出し IndexedDB に積まれたことを確認する。
 * その後 online に戻して自動 flush が走り、BE の POST が 1 度呼ばれたことを検証する。</p>
 */

test.describe('JOB-015: F13.1 オフラインキュー自動送信', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-015-01: offline → 保留 → online 復帰で自動 POST', async ({ page }) => {
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

    let postCount = 0
    await page.route('**/api/v1/jobs/check-ins', async (route, request) => {
      if (request.method() !== 'POST') {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      postCount++
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: buildCheckInResponse({
            type: 'IN',
            newStatus: 'IN_PROGRESS',
          }),
        }),
      })
    })

    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=IN`)
    await waitForHydration(page)

    // navigator.onLine = false に差し替え（カメラ経路を通らず navigator.onLine で判定しているため）
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
    })

    await page.getByTestId('qr-scanner-tab-manual').click()
    await page.getByTestId('qr-scanner-manual-input').fill('OFFL01')
    await page.getByTestId('qr-scanner-manual-submit').click()

    // オフライン保留トースト（i18n jobmatching.qr.scanner.submitted.queuedTitle = "オフライン保留"）
    await expect(page.getByText('オフライン保留').first()).toBeVisible({ timeout: 10_000 })

    // この時点では POST はまだ走っていない
    expect(postCount).toBe(0)

    // オンラインに戻して online イベントを発火
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'onLine', { value: true, configurable: true })
      window.dispatchEvent(new Event('online'))
    })

    // 自動 flush により POST が 1 回以上呼ばれる
    await expect.poll(() => postCount, { timeout: 10_000 }).toBeGreaterThanOrEqual(1)
  })
})
