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
 * F13.1 スキマバイト — JOB-016: Geolocation 500m 乖離で geo_anomaly 警告が出る。
 *
 * <p>BE は {@code CheckInResponse.geoAnomaly = true} を返す（距離計算は BE 側で実施）。
 * FE は位置情報同意チェックを ON にして submit し、応答を受けてトースト
 * 「位置情報の注意」が表示されることを確認する。</p>
 */

test.describe('JOB-016: F13.1 Geolocation 乖離検知', () => {
  test.beforeEach(async ({ page, context }) => {
    await setupWorkerAuth(page)
    // 位置情報モック（精度問わず適当な値で即応答）
    await context.grantPermissions(['geolocation'])
    await context.setGeolocation({ latitude: 35.0, longitude: 139.0 })
  })

  test('JOB-016-01: geoConsent オン + BE が geoAnomaly=true → 注意トーストが出る', async ({ page }) => {
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

    let capturedBody: Record<string, unknown> | null = null
    await page.route('**/api/v1/jobs/check-ins', async (route, request) => {
      if (request.method() !== 'POST') {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      capturedBody = request.postDataJSON() as Record<string, unknown>
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: buildCheckInResponse({
            type: 'IN',
            newStatus: 'IN_PROGRESS',
            geoAnomaly: true,
          }),
        }),
      })
    })

    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=IN`)
    await waitForHydration(page)

    // 位置情報同意
    await page.getByTestId('qr-scanner-geo-consent').click()

    await page.getByTestId('qr-scanner-tab-manual').click()
    await page.getByTestId('qr-scanner-manual-input').fill('GEO001')
    await page.getByTestId('qr-scanner-manual-submit').click()

    // 成功トースト + 位置情報注意トーストの両方
    await expect(page.getByText('入場を記録しました')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('位置情報の注意').first()).toBeVisible({ timeout: 10_000 })

    // 送信ボディに geoLat/geoLng が含まれていることを検証
    expect(capturedBody).not.toBeNull()
    const body = capturedBody as Record<string, unknown>
    expect(typeof body?.geoLat).toBe('number')
    expect(typeof body?.geoLng).toBe('number')
  })
})
