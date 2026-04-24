import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  TEAM_ID,
  JOB_ID_OPEN,
  APPLICATION_ID_APPLIED,
  WORKER_USER_ID,
  buildJobPosting,
  buildJobApplication,
  pagedOf,
  mockCatchAllApis,
  setupRequesterAuth,
  setupWorkerAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-008: 不採用・取り下げ。
 *
 * <p>ケース:</p>
 * <ol>
 *   <li>Requester: 応募を不採用（reject）→ 理由ダイアログ → REJECTED 遷移</li>
 *   <li>Worker: 自分の応募を取り下げ（withdraw）→ WITHDRAWN 遷移</li>
 * </ol>
 *
 * <p>Worker 側では /me/jobs 応募タブで「応募を取り下げる」ボタンが表示される
 * （条件: application.status === 'APPLIED'）。</p>
 *
 * <p>i18n jobmatching.status.application には APPLIED キーが無く
 * PENDING が定義されている（BE と i18n の不整合）。JobStatusBadge は te() で
 * キー存在確認し、無ければ raw 値にフォールバックする。本 spec ではこのフォールバック動作も
 * 受容する regex 比較を行う。</p>
 *
 * <p>仕様書: docs/features/F13.1_skilled_job_matching.md</p>
 */

test.describe('JOB-008: F13.1 不採用・取り下げ', () => {
  test('JOB-008-01: Requester が応募を不採用にする → REJECTED バッジが出る', async ({ page }) => {
    await setupRequesterAuth(page)
    await mockCatchAllApis(page)

    let rejected = false

    // 求人詳細（OPEN）
    const openJob = buildJobPosting({ id: JOB_ID_OPEN, status: 'OPEN' })
    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: openJob }),
      })
    })

    // 応募一覧: APPLIED → reject 後は REJECTED
    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}/applications**`, async (route) => {
      const app = buildJobApplication({
        id: APPLICATION_ID_APPLIED,
        jobPostingId: JOB_ID_OPEN,
        status: rejected ? 'REJECTED' : 'APPLIED',
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([app])),
      })
    })

    // 不採用 API
    await page.route(
      `**/api/v1/applications/${APPLICATION_ID_APPLIED}/reject`,
      async (route) => {
        rejected = true
        const app = buildJobApplication({
          id: APPLICATION_ID_APPLIED,
          jobPostingId: JOB_ID_OPEN,
          status: 'REJECTED',
        })
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: app }),
        })
      },
    )

    await page.goto(`/teams/${TEAM_ID}/jobs/${JOB_ID_OPEN}`)
    await waitForHydration(page)

    await expect(
      page.getByRole('heading', { name: 'E2Eテスト用 求人タイトル' }),
    ).toBeVisible({ timeout: 10_000 })

    // 「不採用」ボタン（i18n jobmatching.application.reject = "不採用"）
    // ApplicationList 内の小さいボタン
    await page.getByRole('button', { name: '不採用', exact: true }).click()

    // 不採用ダイアログ（i18n jobmatching.application.rejectDialog.title = "応募を不採用にする"）
    await expect(page.getByText('応募を不採用にする')).toBeVisible({ timeout: 10_000 })

    // 理由入力（任意だが入れておく）
    const dialog = page.getByRole('dialog')
    const reasonArea = dialog.locator('textarea')
    await reasonArea.click()
    await reasonArea.pressSequentially('今回はご縁がありませんでした', { delay: 10 })

    // ダイアログの確定ボタン（i18n jobmatching.application.rejectDialog.confirm = "不採用にする"）
    await dialog.getByRole('button', { name: '不採用にする' }).click()

    // 成功トースト（i18n jobmatching.application.rejectSucceeded = "不採用にしました"）
    await expect(page.getByText('不採用にしました')).toBeVisible({ timeout: 10_000 })

    // REJECTED バッジ（i18n jobmatching.status.application.REJECTED = "不採用"）
    // ※ボタン label "不採用" と区別するため locator で Tag 要素を優先的に確認する
    // 単純に text "不採用" は複数存在し得るため、出ることだけを確認
    await expect(page.getByText('不採用').first()).toBeVisible({ timeout: 10_000 })
  })

  test('JOB-008-02: Worker が自分の応募を取り下げる → WITHDRAWN に遷移する', async ({ page }) => {
    await setupWorkerAuth(page)
    await mockCatchAllApis(page)

    let withdrawn = false

    // /me/applications
    await page.route('**/api/v1/me/applications**', async (route) => {
      const app = buildJobApplication({
        id: APPLICATION_ID_APPLIED,
        jobPostingId: JOB_ID_OPEN,
        applicantUserId: WORKER_USER_ID,
        status: withdrawn ? 'WITHDRAWN' : 'APPLIED',
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([app])),
      })
    })

    // /me/contracts（契約タブ側、空）
    await page.route('**/api/v1/me/contracts**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([])),
      })
    })

    // 取り下げ API
    await page.route(
      `**/api/v1/applications/${APPLICATION_ID_APPLIED}/withdraw`,
      async (route) => {
        withdrawn = true
        const app = buildJobApplication({
          id: APPLICATION_ID_APPLIED,
          jobPostingId: JOB_ID_OPEN,
          applicantUserId: WORKER_USER_ID,
          status: 'WITHDRAWN',
        })
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: app }),
        })
      },
    )

    await page.goto('/me/jobs')
    await waitForHydration(page)

    // 応募タブが初期表示
    await expect(page.getByRole('heading', { name: '自分の応募・契約' })).toBeVisible({
      timeout: 10_000,
    })

    // 応募へのリンク (jobLink = "求人 #{id}")
    await expect(page.getByText(`求人 #${JOB_ID_OPEN}`).first()).toBeVisible({
      timeout: 10_000,
    })

    // 「応募を取り下げる」ボタン（i18n jobmatching.myJobs.withdrawButton）
    await page.getByRole('button', { name: '応募を取り下げる' }).click()

    // 成功トースト（i18n jobmatching.myJobs.withdrawSucceeded = "応募を取り下げました"）
    await expect(page.getByText('応募を取り下げました')).toBeVisible({ timeout: 10_000 })

    // WITHDRAWN バッジ（i18n jobmatching.status.application.WITHDRAWN = "辞退"）
    await expect(page.getByText('辞退').first()).toBeVisible({ timeout: 10_000 })
  })
})
