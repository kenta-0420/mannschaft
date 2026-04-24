import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CONTRACT_ID_REPORTED,
  buildJobContract,
  mockCatchAllApis,
  setupWorkerAuth,
  pagedOf,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-018: 差し戻し → IN_PROGRESS 再開 → 再 COMPLETION_REPORTED。
 *
 * <p>Requester が完了を差し戻したあと（contract.status は MATCHED/IN_PROGRESS 相当に戻る）、
 * Worker が再度「完了報告」ボタンで COMPLETION_REPORTED に遷移することを検証する。
 * Phase 13.1.2 ではシナリオとしてこのループを通せることが重要。</p>
 *
 * <p>BE の実際の遷移は Phase 13.1.2 実装で {@code rejection_count + 1} / status を MATCHED に
 * 戻す仕様。ここでは mock で単純に MATCHED → COMPLETION_REPORTED を再現する。</p>
 */

test.describe('JOB-018: F13.1 差し戻し → 再完了報告', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-018-01: Worker が再度完了報告を送信 → COMPLETION_REPORTED に遷移', async ({ page }) => {
    await mockCatchAllApis(page)

    let reported = false
    await page.route('**/api/v1/me/contracts**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          pagedOf([
            buildJobContract({
              id: CONTRACT_ID_REPORTED,
              status: reported ? 'COMPLETION_REPORTED' : 'MATCHED',
              rejectionCount: 1,
              lastRejectionReason: '写真が足りません',
            }),
          ]),
        ),
      })
    })

    await page.route('**/api/v1/me/applications**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([])),
      })
    })

    // 完了報告 POST
    await page.route(
      `**/api/v1/contracts/${CONTRACT_ID_REPORTED}/report-completion`,
      async (route) => {
        reported = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildJobContract({
              id: CONTRACT_ID_REPORTED,
              status: 'COMPLETION_REPORTED',
              rejectionCount: 1,
            }),
          }),
        })
      },
    )

    await page.goto('/me/jobs')
    await waitForHydration(page)

    // 契約タブに切替
    await page.getByRole('button', { name: '契約' }).click()

    // 再完了報告ボタン（i18n jobmatching.contract.actions.reportCompletion = "完了報告"）
    await page.getByRole('button', { name: '完了報告' }).click()

    // 成功トースト（i18n jobmatching.contract.reportSucceeded）
    await expect(
      page.getByText(/完了報告しました|完了報告を送信しました/).first(),
    ).toBeVisible({ timeout: 10_000 })
  })
})
