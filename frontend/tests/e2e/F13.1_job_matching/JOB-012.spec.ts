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
 * F13.1 スキマバイト — JOB-012: 手動コード入力フォールバックの成功ケース。
 *
 * <p>カメラが使えない／QR が読み取れない状況を想定し、shortCode のみで送信する。
 * BE 応答 {@code CheckInResponse} を受け取って成功トースト＋遷移を確認する。</p>
 */

test.describe('JOB-012: F13.1 手動コード入力フォールバック', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-012-01: 手動タブで 6 桁コードを送信すると manualCodeFallback=true で成功する', async ({ page }) => {
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
          data: buildCheckInResponse({ type: 'IN', newStatus: 'IN_PROGRESS' }),
        }),
      })
    })

    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=IN`)
    await waitForHydration(page)

    await page.getByTestId('qr-scanner-tab-manual').click()
    await page.getByTestId('qr-scanner-manual-input').fill('abc123')
    await page.getByTestId('qr-scanner-manual-submit').click()

    await expect(page.getByText('入場を記録しました')).toBeVisible({ timeout: 10_000 })

    // 送信されたボディを検証（manualCodeFallback = true、token は null、shortCode は ABC123 に大文字化）
    expect(capturedBody).not.toBeNull()
    expect((capturedBody as Record<string, unknown>)?.manualCodeFallback).toBe(true)
    expect((capturedBody as Record<string, unknown>)?.token).toBeNull()
    expect((capturedBody as Record<string, unknown>)?.shortCode).toBe('ABC123')
  })

  test('JOB-012-02: 5 桁以下など不正な shortCode は送信せずエラー表示する', async ({ page }) => {
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

    let called = false
    await page.route('**/api/v1/jobs/check-ins', async (route) => {
      called = true
      await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
    })

    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=IN`)
    await waitForHydration(page)

    await page.getByTestId('qr-scanner-tab-manual').click()

    // 5 桁だけ入れて送信ボタンが disabled のまま（maxlength=6 / pattern 不一致）
    await page.getByTestId('qr-scanner-manual-input').fill('ABC12')
    const submitBtn = page.getByTestId('qr-scanner-manual-submit')
    await expect(submitBtn).toBeDisabled()

    expect(called).toBe(false)
  })
})
