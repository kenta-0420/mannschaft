import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CONTRACT_ID_MATCHED,
  buildJobContract,
  buildCheckInResponse,
  mockCatchAllApis,
  setupWorkerAuth,
  pagedOf,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-010: Worker が QR をスキャンして入場（CHECKED_IN → IN_PROGRESS）する。
 *
 * <p>Playwright の Chromium ではカメラ映像から QR を検出させるのは非現実的なので、
 * 手動入力タブ経由で {@code shortCode} を送信し、BE の応答として IN_PROGRESS を返すフローで
 * 「チェックイン ダイアログ → 勤務中表示」の繊維を検証する。</p>
 */

test.describe('JOB-010: F13.1 Worker QR スキャン（IN）', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-010-01: 手動入力で shortCode を送信 → 成功トースト → /me/jobs に戻り「勤務中」', async ({ page }) => {
    await mockCatchAllApis(page)

    // 契約取得（最初は MATCHED、POST 後は IN_PROGRESS）
    let postCalled = false
    await page.route(`**/api/v1/contracts/${CONTRACT_ID_MATCHED}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: buildJobContract({
            id: CONTRACT_ID_MATCHED,
            status: postCalled ? 'IN_PROGRESS' : 'MATCHED',
          }),
        }),
      })
    })

    // /me/contracts（/me/jobs 画面で表示される）
    await page.route('**/api/v1/me/contracts**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          pagedOf([
            buildJobContract({
              id: CONTRACT_ID_MATCHED,
              status: postCalled ? 'IN_PROGRESS' : 'MATCHED',
            }),
          ]),
        ),
      })
    })

    // チェックイン POST
    await page.route('**/api/v1/jobs/check-ins', async (route, request) => {
      if (request.method() !== 'POST') {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      postCalled = true
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

    // 手動入力タブに切り替え
    await page.getByTestId('qr-scanner-tab-manual').click()

    // 6 桁コード入力
    await page.getByTestId('qr-scanner-manual-input').fill('ABC123')
    await page.getByTestId('qr-scanner-manual-submit').click()

    // 成功トースト（i18n jobmatching.qr.scanner.submitted.inSuccess = "入場を記録しました"）
    await expect(page.getByText('入場を記録しました')).toBeVisible({ timeout: 10_000 })

    // /me/jobs へ遷移
    await page.waitForURL('**/me/jobs', { timeout: 10_000 })
    await waitForHydration(page)

    // 契約タブへ
    await page.getByRole('button', { name: '契約' }).click()

    // 勤務中（IN_PROGRESS → i18n jobmatching.status.contract.IN_PROGRESS = "業務中"）のバッジが出る
    // 翻訳値は "業務中" の想定。無い場合は raw 値にフォールバックするので IN_PROGRESS のどちらかが出る。
    await expect(
      page.getByText(/業務中|IN_PROGRESS/).first(),
    ).toBeVisible({ timeout: 10_000 })
  })
})
