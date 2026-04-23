import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  TEAM_ID,
  JOB_ID_OPEN,
  APPLICATION_ID_APPLIED,
  CONTRACT_ID_MATCHED,
  buildJobPosting,
  buildJobApplication,
  buildJobContract,
  pagedOf,
  mockCatchAllApis,
  setupRequesterAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-004: Requester 採用確定（契約生成）。
 *
 * <p>シナリオ:</p>
 * <ol>
 *   <li>Requester で求人詳細画面（{@code /teams/{id}/jobs/{jobId}}）を開く</li>
 *   <li>応募者一覧に APPLIED 状態の応募が見える</li>
 *   <li>「採用する」ボタン押下 → POST /api/v1/applications/{id}/accept</li>
 *   <li>レスポンスは {@link JobContractResponse}（契約生成）</li>
 *   <li>採用成功トーストが表示される</li>
 * </ol>
 *
 * <p>契約一覧画面は Worker の /me/jobs から参照するため、
 * Requester 視点の確認はこの画面内で完結させる。契約の MATCHED 状態確認は JOB-005 で行う。</p>
 *
 * <p>仕様書: docs/features/F13.1_skilled_job_matching.md</p>
 */

test.describe('JOB-004: F13.1 Requester 採用確定（契約生成）', () => {
  test.beforeEach(async ({ page }) => {
    await setupRequesterAuth(page)
  })

  test('JOB-004-01: 応募を採用 → 契約レコードが返り採用成功トーストが出る', async ({ page }) => {
    await mockCatchAllApis(page)

    // 求人詳細（OPEN）
    const openJob = buildJobPosting({ id: JOB_ID_OPEN, status: 'OPEN' })
    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: openJob }),
      })
    })

    // 応募一覧: APPLIED の応募 1 件
    // accept 後の再取得では ACCEPTED になるよう state を切り替える
    let accepted = false
    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}/applications**`, async (route) => {
      const app = buildJobApplication({
        id: APPLICATION_ID_APPLIED,
        jobPostingId: JOB_ID_OPEN,
        status: accepted ? 'ACCEPTED' : 'APPLIED',
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([app])),
      })
    })

    // 採用確定（POST /api/v1/applications/{id}/accept）
    // レスポンスは JobContractResponse
    const contract = buildJobContract({
      id: CONTRACT_ID_MATCHED,
      jobPostingId: JOB_ID_OPEN,
      jobApplicationId: APPLICATION_ID_APPLIED,
      status: 'MATCHED',
    })
    await page.route(
      `**/api/v1/applications/${APPLICATION_ID_APPLIED}/accept`,
      async (route) => {
        accepted = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: contract }),
        })
      },
    )

    // --- 求人詳細画面 ---
    await page.goto(`/teams/${TEAM_ID}/jobs/${JOB_ID_OPEN}`)
    await waitForHydration(page)

    // タイトル見出し
    await expect(
      page.getByRole('heading', { name: 'E2Eテスト用 求人タイトル' }),
    ).toBeVisible({ timeout: 10_000 })

    // 応募者ラベルが表示される（i18n jobmatching.application.applicantLabel = "応募者 #{userId}"）
    await expect(page.getByText(/応募者 #/).first()).toBeVisible({ timeout: 10_000 })

    // 「採用する」ボタン（i18n jobmatching.application.accept = "採用する"）
    await page.getByRole('button', { name: '採用する' }).click()

    // 採用成功トースト（i18n jobmatching.application.acceptSucceeded = "採用しました"）
    await expect(page.getByText('採用しました')).toBeVisible({ timeout: 10_000 })
  })
})
