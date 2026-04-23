import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  JOB_ID_OPEN,
  APPLICATION_ID_APPLIED,
  buildJobPosting,
  buildJobPostingSummary,
  buildJobApplication,
  pagedOf,
  mockCatchAllApis,
  setupWorkerAuth,
  mockMyTeams,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-003: Worker 応募フロー。
 *
 * <p>シナリオ:</p>
 * <ol>
 *   <li>Worker でログイン → {@code /jobs}（参加チーム内の公開中求人検索）</li>
 *   <li>OPEN 求人が一覧に出る → クリックで詳細画面（{@code /jobs/{id}}）</li>
 *   <li>「この求人に応募する」ボタン押下 → 自己PR 入力ダイアログ</li>
 *   <li>応募送信 → 「応募しました」トースト + /me/jobs へ遷移</li>
 *   <li>応募タブで応募レコードが表示される（jobPostingId 参照）</li>
 * </ol>
 *
 * <p>仕様書: docs/features/F13.1_skilled_job_matching.md</p>
 */

test.describe('JOB-003: F13.1 Worker 応募フロー', () => {
  test.beforeEach(async ({ page }) => {
    await setupWorkerAuth(page)
  })

  test('JOB-003-01: /jobs で OPEN 求人を見つけて応募 → /me/jobs 応募タブで確認できる', async ({ page }) => {
    await mockCatchAllApis(page)
    await mockMyTeams(page)

    // /jobs で teamId=1, status=OPEN を叩く → OPEN 求人1件
    const openJob = buildJobPostingSummary({ id: JOB_ID_OPEN, status: 'OPEN' })
    await page.route('**/api/v1/jobs?**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([openJob])),
      })
    })

    // 詳細画面の GET
    const openJobFull = buildJobPosting({ id: JOB_ID_OPEN, status: 'OPEN' })
    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: openJobFull }),
      })
    })

    // 応募 POST → 応募レコードを返す
    const application = buildJobApplication({
      id: APPLICATION_ID_APPLIED,
      jobPostingId: JOB_ID_OPEN,
      status: 'APPLIED',
      selfPr: '全力で頑張ります！',
    })
    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}/apply`, async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: application }),
      })
    })

    // /me/applications (応募タブ)
    await page.route('**/api/v1/me/applications**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([application])),
      })
    })

    // /me/contracts (契約タブ) は空
    await page.route('**/api/v1/me/contracts**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([])),
      })
    })

    // --- 検索画面へ遷移 ---
    await page.goto('/jobs')
    await waitForHydration(page)

    // i18n jobmatching.workerSearch.title = "スキマバイトを探す"
    await expect(page.getByRole('heading', { name: 'スキマバイトを探す' })).toBeVisible({
      timeout: 10_000,
    })

    // 求人カードのタイトルが表示される（複数箇所に出得るので first を指定）
    await expect(page.getByText('E2Eテスト用 求人タイトル').first()).toBeVisible({
      timeout: 10_000,
    })

    // --- 求人カードをクリックして詳細画面へ ---
    await page.getByText('E2Eテスト用 求人タイトル').first().click()
    await page.waitForURL(`**/jobs/${JOB_ID_OPEN}`, { timeout: 10_000 })
    await waitForHydration(page)

    // タイトル見出し
    await expect(
      page.getByRole('heading', { name: 'E2Eテスト用 求人タイトル' }),
    ).toBeVisible({ timeout: 10_000 })

    // 「この求人に応募する」ボタン（i18n jobmatching.workerDetail.applyButton）
    await page.getByRole('button', { name: 'この求人に応募する' }).click()

    // 応募ダイアログが開く（i18n jobmatching.workerDetail.applyDialog.title = "応募の確認"）
    await expect(page.getByText('応募の確認')).toBeVisible({ timeout: 10_000 })

    // 自己PR を入力
    const selfPrArea = page
      .locator('textarea')
      .filter({ hasText: '' })
      .last()
    await selfPrArea.click()
    await selfPrArea.pressSequentially('全力で頑張ります！', { delay: 10 })

    // 応募する（i18n jobmatching.workerDetail.applyDialog.confirm = "応募する"）
    await page.getByRole('button', { name: '応募する' }).click()

    // /me/jobs に遷移
    await page.waitForURL('**/me/jobs', { timeout: 10_000 })
    await waitForHydration(page)

    // 応募タブが初期表示される
    // i18n jobmatching.myJobs.title = "自分の応募・契約"
    await expect(page.getByRole('heading', { name: '自分の応募・契約' })).toBeVisible({
      timeout: 10_000,
    })

    // 応募レコードへのリンク（i18n jobmatching.myJobs.jobLink = "求人 #{id}" → "求人 #2002"）
    await expect(page.getByText(`求人 #${JOB_ID_OPEN}`).first()).toBeVisible({ timeout: 10_000 })
  })
})
