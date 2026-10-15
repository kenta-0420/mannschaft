import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CONTRACT_ID_MATCHED,
  buildJobContract,
  mockCatchAllApis,
  setupWorkerAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-017: 掛け持ち禁止（BE 403 相当）。
 *
 * <p>既に別の契約で勤務中の Worker が新しい契約にチェックインしようとすると
 * BE は 403 Forbidden を返す（policy でガード）。FE は失敗トーストを表示する。</p>
 */

test.describe('JOB-017: F13.1 掛け持ち禁止', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-017-01: 他契約で勤務中の Worker がチェックイン → 403 → 失敗トースト', async ({ page }) => {
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
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: 'WORKER_ALREADY_CHECKED_IN',
            message: 'Worker is already checked in to another contract',
          },
        }),
      })
    })

    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=IN`)
    await waitForHydration(page)

    await page.getByTestId('qr-scanner-tab-manual').click()
    await page.getByTestId('qr-scanner-manual-input').fill('CONF01')
    await page.getByTestId('qr-scanner-manual-submit').click()

    await expect(
      page.getByText('チェックインに失敗しました').first(),
    ).toBeVisible({ timeout: 10_000 })
    expect(page.url()).toContain('/scan')
  })
})
