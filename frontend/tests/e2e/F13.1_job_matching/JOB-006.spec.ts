import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  JOB_ID_OPEN,
  APPLICATION_ID_APPLIED,
  CONTRACT_ID_MATCHED,
  REQUESTER_USER_ID,
  WORKER_USER_ID,
  buildJobContract,
  pagedOf,
  mockCatchAllApis,
  setupRequesterAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-006: 完了差し戻し。
 *
 * <p>シナリオ:</p>
 * <ol>
 *   <li>Worker が完了報告済み（COMPLETION_REPORTED）の契約がある</li>
 *   <li>Requester で /me/jobs 契約タブを開く</li>
 *   <li>「差し戻す」ボタン押下 → 差し戻し理由ダイアログが開く</li>
 *   <li>理由を入力して「差し戻す」押下 → POST /reject-completion</li>
 *   <li>成功トースト → 再取得後に rejectionCount が +1 された契約が表示される</li>
 * </ol>
 *
 * <p>BE の state machine: COMPLETION_REPORTED → MATCHED（rejection_count + 1）。</p>
 *
 * <p>仕様書: docs/features/F13.1_skilled_job_matching.md</p>
 */

test.describe('JOB-006: F13.1 完了差し戻し（rejection_count 増加）', () => {
  test.beforeEach(async ({ page }) => {
    await setupRequesterAuth(page)
  })

  test('JOB-006-01: Requester が差し戻し理由を入力 → MATCHED に戻り rejectionCount が 1 になる', async ({ page }) => {
    await mockCatchAllApis(page)

    let rejected = false

    // 契約一覧
    await page.route('**/api/v1/me/contracts**', async (route) => {
      const contract = buildJobContract({
        id: CONTRACT_ID_MATCHED,
        jobPostingId: JOB_ID_OPEN,
        jobApplicationId: APPLICATION_ID_APPLIED,
        requesterUserId: REQUESTER_USER_ID,
        workerUserId: WORKER_USER_ID,
        status: rejected ? 'MATCHED' : 'COMPLETION_REPORTED',
        completionReportedAt: '2026-04-25T10:00:00Z',
        rejectionCount: rejected ? 1 : 0,
        lastRejectionReason: rejected ? '再度チェックして直してください' : null,
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([contract])),
      })
    })

    await page.route('**/api/v1/me/applications**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([])),
      })
    })

    // 差し戻し API
    await page.route(
      `**/api/v1/contracts/${CONTRACT_ID_MATCHED}/reject-completion`,
      async (route, request) => {
        rejected = true
        const body = request.postDataJSON() as { reason?: string }
        const updated = buildJobContract({
          id: CONTRACT_ID_MATCHED,
          jobPostingId: JOB_ID_OPEN,
          jobApplicationId: APPLICATION_ID_APPLIED,
          requesterUserId: REQUESTER_USER_ID,
          workerUserId: WORKER_USER_ID,
          status: 'MATCHED',
          rejectionCount: 1,
          lastRejectionReason: body.reason ?? '',
        })
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: updated }),
        })
      },
    )

    await page.goto('/me/jobs')
    await waitForHydration(page)

    // 契約タブへ
    await page.getByRole('button', { name: '契約' }).click()

    // 「差し戻す」ボタン（i18n jobmatching.contract.actions.rejectCompletion = "差し戻す"）
    // ContractActionPanel はアクションパネル内に出る。buttons 複数あり得るので first を使う。
    await page.getByRole('button', { name: '差し戻す' }).first().click()

    // 差し戻しダイアログが開く（i18n jobmatching.contract.rejectDialog.title = "完了報告を差し戻す"）
    await expect(page.getByText('完了報告を差し戻す')).toBeVisible({ timeout: 10_000 })

    // 理由入力（Textarea、Dialog 内）
    const dialog = page.getByRole('dialog')
    const reasonArea = dialog.locator('textarea')
    await reasonArea.click()
    await reasonArea.pressSequentially('再度チェックして直してください', { delay: 10 })

    // ダイアログの「差し戻す」ボタン（confirm）
    // 同名ボタンが発火元と Dialog footer に存在するが、Dialog が開いている間はダイアログ内の
    // button が最前面。getByRole で exact match しつつ Dialog scope 内を指定する。
    await dialog.getByRole('button', { name: '差し戻す' }).click()

    // 成功トースト（i18n jobmatching.contract.rejectSucceeded = "差し戻しました"）
    await expect(page.getByText('差し戻しました')).toBeVisible({ timeout: 10_000 })

    // 再取得で MATCHED に戻る（= "成立" バッジが出る）
    // i18n jobmatching.status.contract.MATCHED = "成立"
    await expect(page.getByText('成立').first()).toBeVisible({ timeout: 10_000 })
  })
})
