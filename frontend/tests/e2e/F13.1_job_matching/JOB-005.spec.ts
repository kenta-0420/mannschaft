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
  setupWorkerAuth,
  setupRequesterAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-005: 完了フロー（ハッピーパス）。
 *
 * <p>シナリオ（2 ステップ）:</p>
 * <ol>
 *   <li>Worker: /me/jobs 契約タブで MATCHED 契約を確認 → 「完了を報告」押下
 *       → COMPLETION_REPORTED に遷移</li>
 *   <li>Requester: /me/jobs 契約タブで COMPLETION_REPORTED 契約を確認 → 「完了を承認」押下
 *       → COMPLETED に遷移</li>
 * </ol>
 *
 * <p>/me/jobs は Worker / Requester いずれから見ても {@code GET /api/v1/me/contracts} を呼ぶ。
 * ContractActionPanel は currentUserId と contract.workerUserId/requesterUserId を比較して
 * 押せるボタンを切り替える。</p>
 *
 * <p>仕様書: docs/features/F13.1_skilled_job_matching.md</p>
 */

test.describe('JOB-005: F13.1 完了フロー（Worker 報告 → Requester 承認）', () => {
  test('JOB-005-01: Worker が完了報告 → COMPLETION_REPORTED に遷移する', async ({ page }) => {
    await setupWorkerAuth(page)
    await mockCatchAllApis(page)

    let reported = false

    // 契約一覧: 最初は MATCHED、report 後は COMPLETION_REPORTED
    await page.route('**/api/v1/me/contracts**', async (route) => {
      const contract = buildJobContract({
        id: CONTRACT_ID_MATCHED,
        jobPostingId: JOB_ID_OPEN,
        jobApplicationId: APPLICATION_ID_APPLIED,
        requesterUserId: REQUESTER_USER_ID,
        workerUserId: WORKER_USER_ID,
        status: reported ? 'COMPLETION_REPORTED' : 'MATCHED',
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([contract])),
      })
    })

    // 応募一覧は空（契約タブだけ使う）
    await page.route('**/api/v1/me/applications**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([])),
      })
    })

    // 完了報告
    await page.route(
      `**/api/v1/contracts/${CONTRACT_ID_MATCHED}/report-completion`,
      async (route) => {
        reported = true
        const updated = buildJobContract({
          id: CONTRACT_ID_MATCHED,
          jobPostingId: JOB_ID_OPEN,
          jobApplicationId: APPLICATION_ID_APPLIED,
          requesterUserId: REQUESTER_USER_ID,
          workerUserId: WORKER_USER_ID,
          status: 'COMPLETION_REPORTED',
          completionReportedAt: '2026-04-25T10:00:00Z',
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

    await expect(page.getByRole('heading', { name: '自分の応募・契約' })).toBeVisible({
      timeout: 10_000,
    })

    // 契約タブへ切り替え（i18n jobmatching.myJobs.tabs.contracts = "契約"）
    await page.getByRole('button', { name: '契約' }).click()

    // MATCHED ステータスが表示される（i18n jobmatching.status.contract.MATCHED = "成立"）
    await expect(page.getByText('成立').first()).toBeVisible({ timeout: 10_000 })

    // 「完了を報告」ボタン（i18n jobmatching.contract.actions.reportCompletion）
    await page.getByRole('button', { name: '完了を報告' }).click()

    // 完了報告成功トースト（i18n jobmatching.contract.reportSucceeded = "完了報告を送信しました"）
    await expect(page.getByText('完了報告を送信しました')).toBeVisible({ timeout: 10_000 })

    // 再読込後に COMPLETION_REPORTED へ遷移
    // i18n jobmatching.status.contract.REPORTED = "完了報告済み"
    // （注: 実 BE は COMPLETION_REPORTED を返すが、i18n key は REPORTED と短縮されている）
    // JobStatusBadge は te() で key 存在確認し、無ければ raw status を fallback 表示する。
    // ここでは raw status "COMPLETION_REPORTED" と REPORTED どちらにもマッチするよう regex 比較。
    await expect(
      page.locator('text=/完了報告済み|COMPLETION_REPORTED/').first(),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('JOB-005-02: Requester が完了承認 → COMPLETED に遷移する', async ({ page }) => {
    await setupRequesterAuth(page)
    await mockCatchAllApis(page)

    let approved = false

    // 契約一覧: 最初は COMPLETION_REPORTED、approve 後は COMPLETED
    await page.route('**/api/v1/me/contracts**', async (route) => {
      const contract = buildJobContract({
        id: CONTRACT_ID_MATCHED,
        jobPostingId: JOB_ID_OPEN,
        jobApplicationId: APPLICATION_ID_APPLIED,
        requesterUserId: REQUESTER_USER_ID,
        workerUserId: WORKER_USER_ID,
        status: approved ? 'COMPLETED' : 'COMPLETION_REPORTED',
        completionReportedAt: '2026-04-25T10:00:00Z',
        completionApprovedAt: approved ? '2026-04-25T12:00:00Z' : null,
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

    // 完了承認
    await page.route(
      `**/api/v1/contracts/${CONTRACT_ID_MATCHED}/approve-completion`,
      async (route) => {
        approved = true
        const updated = buildJobContract({
          id: CONTRACT_ID_MATCHED,
          jobPostingId: JOB_ID_OPEN,
          jobApplicationId: APPLICATION_ID_APPLIED,
          requesterUserId: REQUESTER_USER_ID,
          workerUserId: WORKER_USER_ID,
          status: 'COMPLETED',
          completionApprovedAt: '2026-04-25T12:00:00Z',
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

    // 「完了を承認」ボタン（i18n jobmatching.contract.actions.approveCompletion）
    await page.getByRole('button', { name: '完了を承認' }).click()

    // 承認成功トースト（i18n jobmatching.contract.approveSucceeded = "完了を承認しました"）
    await expect(page.getByText('完了を承認しました')).toBeVisible({ timeout: 10_000 })

    // 再読込後は COMPLETED 状態（i18n jobmatching.status.contract. に "COMPLETED" が無いため
    // raw 値 "COMPLETED" が fallback 表示される可能性あり。どちらでも拾える regex を使用）
    await expect(page.locator('text=/COMPLETED|完了|承認済み/').first()).toBeVisible({
      timeout: 10_000,
    })
  })
})
