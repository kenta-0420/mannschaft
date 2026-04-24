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
 * F13.1 スキマバイト — JOB-011: Worker が退場チェックアウト（IN_PROGRESS → CHECKED_OUT）する。
 *
 * <p>Worker は勤務中（IN_PROGRESS）の契約に対して scan?type=OUT 画面に遷移し、
 * shortCode を送る。BE 応答で {@code workDurationMinutes} を返し、トーストに業務時間が出る。</p>
 */

test.describe('JOB-011: F13.1 Worker QR スキャン（OUT）', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-011-01: IN_PROGRESS の契約で shortCode を送ると CHECKED_OUT に遷移し、業務時間が表示される', async ({ page }) => {
    await mockCatchAllApis(page)

    let postCalled = false
    await page.route(`**/api/v1/contracts/${CONTRACT_ID_MATCHED}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: buildJobContract({
            id: CONTRACT_ID_MATCHED,
            status: postCalled ? 'CHECKED_OUT' : 'IN_PROGRESS',
          }),
        }),
      })
    })

    await page.route('**/api/v1/me/contracts**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          pagedOf([
            buildJobContract({
              id: CONTRACT_ID_MATCHED,
              status: postCalled ? 'CHECKED_OUT' : 'IN_PROGRESS',
            }),
          ]),
        ),
      })
    })

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
            type: 'OUT',
            newStatus: 'CHECKED_OUT',
            workDurationMinutes: 245,
          }),
        }),
      })
    })

    await page.goto(`/contracts/${CONTRACT_ID_MATCHED}/scan?type=OUT`)
    await waitForHydration(page)

    await page.getByTestId('qr-scanner-tab-manual').click()
    await page.getByTestId('qr-scanner-manual-input').fill('OUT456')
    await page.getByTestId('qr-scanner-manual-submit').click()

    // 成功トースト + 業務時間詳細（245 分）
    await expect(page.getByText('退場を記録しました')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('業務時間 245 分')).toBeVisible({ timeout: 10_000 })

    await page.waitForURL('**/me/jobs', { timeout: 10_000 })
  })
})
