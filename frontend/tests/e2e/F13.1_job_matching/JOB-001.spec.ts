import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  TEAM_ID,
  JOB_ID_DRAFT,
  buildJobPosting,
  buildFeePreview,
  mockCatchAllApis,
  setupRequesterAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-001: 求人投稿ハッピーパス。
 *
 * <p>シナリオ:</p>
 * <ol>
 *   <li>Requester でログイン → チーム配下の求人投稿フォーム画面へ</li>
 *   <li>必須項目を入力（タイトル・説明・報酬）</li>
 *   <li>手数料プレビューパネルが表示されていることを確認</li>
 *   <li>「下書き保存」押下 → DRAFT 作成成功 → 詳細画面遷移</li>
 *   <li>詳細画面で DRAFT バッジが表示される</li>
 *   <li>「公開する」ボタン押下 → OPEN に遷移しバッジが変わる</li>
 * </ol>
 *
 * <p>仕様書: docs/features/F13.1_skilled_job_matching.md</p>
 */

test.describe('JOB-001: F13.1 求人投稿ハッピーパス', () => {
  test.beforeEach(async ({ page }) => {
    await setupRequesterAuth(page)
  })

  test('JOB-001-01: 求人投稿フォームを入力して DRAFT 作成 → 公開で OPEN に遷移する', async ({ page }) => {
    await mockCatchAllApis(page)

    // 手数料プレビュー（FeePreviewPanel が debounce 後に呼ぶ）
    await page.route('**/api/v1/jobs/fee-preview**', async (route) => {
      const url = new URL(route.request().url())
      const baseJpy = Number(url.searchParams.get('baseRewardJpy') ?? '0')
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: buildFeePreview(baseJpy) }),
      })
    })

    // DRAFT 作成（POST /api/v1/jobs）
    const draft = buildJobPosting({ id: JOB_ID_DRAFT, status: 'DRAFT' })
    await page.route('**/api/v1/jobs', async (route, request) => {
      if (request.method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: draft }),
        })
      }
      else {
        // 一覧 GET は本テストで到達しないが念のため空 data
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } }),
        })
      }
    })

    // 詳細画面の GET
    await page.route(`**/api/v1/jobs/${JOB_ID_DRAFT}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: draft }),
      })
    })

    // 詳細画面で呼ばれる応募一覧（空）
    await page.route(`**/api/v1/jobs/${JOB_ID_DRAFT}/applications**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } }),
      })
    })

    // 公開（POST /api/v1/jobs/{id}/publish）
    const opened = buildJobPosting({ id: JOB_ID_DRAFT, status: 'OPEN' })
    await page.route(`**/api/v1/jobs/${JOB_ID_DRAFT}/publish`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: opened }),
      })
    })

    // --- 求人投稿フォーム画面 ---
    await page.goto(`/teams/${TEAM_ID}/jobs/new`)
    await waitForHydration(page)

    // i18n jobmatching.create.title = "新規求人作成"
    await expect(page.getByRole('heading', { name: '新規求人作成' })).toBeVisible({
      timeout: 10_000,
    })

    // タイトル入力（label "求人タイトル"）
    const titleInput = page.locator('input#job-title')
    await titleInput.click()
    await titleInput.pressSequentially('E2Eテスト用 求人タイトル', { delay: 10 })

    // 説明入力
    const descTextarea = page.locator('textarea#job-description')
    await descTextarea.click()
    await descTextarea.pressSequentially('E2Eテスト用の業務内容説明', { delay: 10 })

    // 報酬額入力（InputNumber。内部の input にフォーカスして入力する）
    const rewardInput = page.locator('#job-base-reward')
    await rewardInput.click()
    await rewardInput.pressSequentially('5000', { delay: 10 })

    // 手数料プレビューが表示されることを確認
    // i18n jobmatching.fee.title = "手数料プレビュー"
    await expect(page.getByText('手数料プレビュー')).toBeVisible({ timeout: 10_000 })
    // debounce 待ち (350ms) + プレビュー描画を待つ
    // i18n jobmatching.fee.requester = "発注者負担"
    await expect(page.getByText('発注者負担')).toBeVisible({ timeout: 10_000 })

    // 下書き保存ボタン（i18n jobmatching.create.saveDraft = "下書き保存"）
    await page.getByRole('button', { name: '下書き保存' }).click()

    // 詳細画面に遷移し、DRAFT ステータスバッジが表示される
    await page.waitForURL(`**/teams/${TEAM_ID}/jobs/${JOB_ID_DRAFT}`, { timeout: 10_000 })
    await waitForHydration(page)

    // 詳細画面のタイトル
    await expect(
      page.getByRole('heading', { name: 'E2Eテスト用 求人タイトル' }),
    ).toBeVisible({ timeout: 10_000 })

    // i18n jobmatching.status.posting.DRAFT = "下書き" が Tag で表示される
    await expect(page.getByText('下書き').first()).toBeVisible({ timeout: 10_000 })

    // 「公開する」ボタンを押下
    // i18n jobmatching.detail.publish = "公開する"
    await page.getByRole('button', { name: '公開する' }).click()

    // OPEN ステータスになる
    // i18n jobmatching.status.posting.OPEN = "募集中"
    await expect(page.getByText('募集中').first()).toBeVisible({ timeout: 10_000 })
  })
})
