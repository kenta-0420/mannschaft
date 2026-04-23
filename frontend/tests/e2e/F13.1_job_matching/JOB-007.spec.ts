import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  TEAM_ID,
  JOB_ID_DRAFT,
  JOB_ID_OPEN,
  buildJobPosting,
  pagedOf,
  mockCatchAllApis,
  setupRequesterAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-007: 求人のキャンセル / 削除。
 *
 * <p>ケース:</p>
 * <ol>
 *   <li>DRAFT 求人 + 応募ゼロ → 削除ボタン押下で成功トースト + 一覧へ戻る</li>
 *   <li>OPEN 求人 → 「キャンセルする」押下 → CANCELLED に遷移しバッジが変わる</li>
 * </ol>
 *
 * <p>仕様書: docs/features/F13.1_skilled_job_matching.md</p>
 */

test.describe('JOB-007: F13.1 求人のキャンセル/削除', () => {
  test.beforeEach(async ({ page }) => {
    await setupRequesterAuth(page)
  })

  test('JOB-007-01: DRAFT 求人を削除 → 一覧画面に戻り成功トーストが出る', async ({ page }) => {
    await mockCatchAllApis(page)

    // DRAFT 求人
    const draft = buildJobPosting({ id: JOB_ID_DRAFT, status: 'DRAFT' })
    await page.route(`**/api/v1/jobs/${JOB_ID_DRAFT}`, async (route, request) => {
      if (request.method() === 'DELETE') {
        await route.fulfill({ status: 204, contentType: 'application/json', body: '' })
      }
      else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: draft }),
        })
      }
    })

    // 応募一覧（空）
    await page.route(`**/api/v1/jobs/${JOB_ID_DRAFT}/applications**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([])),
      })
    })

    // チーム配下求人一覧（削除後の遷移先）
    await page.route('**/api/v1/jobs?**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([])),
      })
    })

    // --- 詳細画面 ---
    await page.goto(`/teams/${TEAM_ID}/jobs/${JOB_ID_DRAFT}`)
    await waitForHydration(page)

    await expect(
      page.getByRole('heading', { name: 'E2Eテスト用 求人タイトル' }),
    ).toBeVisible({ timeout: 10_000 })

    // DRAFT では削除ボタンが出る（i18n jobmatching.detail.delete = "削除"）
    await page.getByRole('button', { name: '削除' }).click()

    // 削除成功トースト（i18n jobmatching.detail.deleteSucceeded = "削除しました"）
    await expect(page.getByText('削除しました')).toBeVisible({ timeout: 10_000 })

    // 一覧画面に遷移
    await page.waitForURL(`**/teams/${TEAM_ID}/jobs`, { timeout: 10_000 })
    await waitForHydration(page)

    // 一覧画面の見出し（i18n jobmatching.list.teamTitle = "チーム内求人一覧"）
    await expect(page.getByRole('heading', { name: 'チーム内求人一覧' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('JOB-007-02: OPEN 求人をキャンセル → CANCELLED バッジに切り替わる', async ({ page }) => {
    await mockCatchAllApis(page)

    let cancelled = false

    // 詳細: 初回 OPEN、cancel 後は CANCELLED
    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}`, async (route) => {
      const job = buildJobPosting({
        id: JOB_ID_OPEN,
        status: cancelled ? 'CANCELLED' : 'OPEN',
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: job }),
      })
    })

    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}/applications**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(pagedOf([])),
      })
    })

    // キャンセル API
    await page.route(`**/api/v1/jobs/${JOB_ID_OPEN}/cancel`, async (route) => {
      cancelled = true
      const cancelledJob = buildJobPosting({
        id: JOB_ID_OPEN,
        status: 'CANCELLED',
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: cancelledJob }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/jobs/${JOB_ID_OPEN}`)
    await waitForHydration(page)

    // OPEN ステータス（i18n jobmatching.status.posting.OPEN = "募集中"）
    await expect(page.getByText('募集中').first()).toBeVisible({ timeout: 10_000 })

    // キャンセルボタン（i18n jobmatching.detail.cancel = "キャンセルする"）
    await page.getByRole('button', { name: 'キャンセルする' }).click()

    // 成功トースト（i18n jobmatching.detail.cancelSucceeded = "キャンセルしました"）
    await expect(page.getByText('キャンセルしました')).toBeVisible({ timeout: 10_000 })

    // CANCELLED バッジ（i18n jobmatching.status.posting.CANCELLED = "キャンセル"）
    await expect(page.getByText('キャンセル').first()).toBeVisible({ timeout: 10_000 })
  })
})
