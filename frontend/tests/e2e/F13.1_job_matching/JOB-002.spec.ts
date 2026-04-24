import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  TEAM_ID,
  buildFeePreview,
  mockCatchAllApis,
  setupRequesterAuth,
} from './_helpers'

/**
 * F13.1 スキマバイト — JOB-002: 求人投稿フォームのクライアント側バリデーション。
 *
 * <p>新規作成フォームは client-side で以下を validate → {@code useNotification().error()} で
 * i18n メッセージをトーストする（ブラウザネイティブ alert ではない）。</p>
 *
 * <p>InputNumber は min=500, max=1,000,000 を HTML 属性でも制約するため、
 * 499 や 1,000,001 の直接入力は PrimeVue によって丸められる可能性が高い。
 * 本 spec では「空のまま」や「workStartAt/EndAt の順序不整合」など、
 * バリデーションメッセージが安定して出るケースのみを扱う。</p>
 *
 * <p>仕様書: docs/features/F13.1_skilled_job_matching.md</p>
 */

test.describe('JOB-002: F13.1 求人投稿フォームのバリデーション', () => {
  test.beforeEach(async ({ page }) => {
    await setupRequesterAuth(page)
    await mockCatchAllApis(page)

    // 手数料プレビュー（報酬空なら呼ばれないが、念のため）
    await page.route('**/api/v1/jobs/fee-preview**', async (route) => {
      const url = new URL(route.request().url())
      const baseJpy = Number(url.searchParams.get('baseRewardJpy') ?? '0')
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: buildFeePreview(baseJpy) }),
      })
    })

    // POST /api/v1/jobs が呼ばれたら想定外（バリデーションで弾かれるべき）
    // ただし呼ばれた場合は 400 を返して UI 側エラーを確認しやすくする
    await page.route('**/api/v1/jobs', async (route, request) => {
      if (request.method() === 'POST') {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({
            error: { code: 'VALIDATION_FAILED', message: 'Validation error' },
          }),
        })
      }
      else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } }),
        })
      }
    })
  })

  test('JOB-002-01: タイトルが空のまま下書き保存 → 「求人タイトルは必須です」が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/jobs/new`)
    await waitForHydration(page)

    // 見出しが出るまで待つ
    await expect(page.getByRole('heading', { name: '新規求人作成' })).toBeVisible({
      timeout: 10_000,
    })

    // タイトルを空のまま下書き保存
    await page.getByRole('button', { name: '下書き保存' }).click()

    // i18n jobmatching.validation.titleRequired = "求人タイトルは必須です"
    await expect(page.getByText('求人タイトルは必須です')).toBeVisible({ timeout: 10_000 })
  })

  test('JOB-002-02: 説明が空のまま下書き保存 → 「業務内容は必須です」が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/jobs/new`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '新規求人作成' })).toBeVisible({
      timeout: 10_000,
    })

    // タイトルだけ入力して説明は空のまま
    const titleInput = page.locator('input#job-title')
    await titleInput.click()
    await titleInput.pressSequentially('テスト求人', { delay: 10 })

    await page.getByRole('button', { name: '下書き保存' }).click()

    // i18n jobmatching.validation.descriptionRequired = "業務内容は必須です"
    await expect(page.getByText('業務内容は必須です')).toBeVisible({ timeout: 10_000 })
  })

  test('JOB-002-03: 報酬が未入力のまま下書き保存 → 「基本報酬額を入力してください」が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/jobs/new`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '新規求人作成' })).toBeVisible({
      timeout: 10_000,
    })

    // タイトル・説明は埋めて、報酬は空のまま
    const titleInput = page.locator('input#job-title')
    await titleInput.click()
    await titleInput.pressSequentially('テスト求人', { delay: 10 })

    const descTextarea = page.locator('textarea#job-description')
    await descTextarea.click()
    await descTextarea.pressSequentially('テスト説明', { delay: 10 })

    await page.getByRole('button', { name: '下書き保存' }).click()

    // i18n jobmatching.validation.rewardRequired = "基本報酬額を入力してください"
    await expect(page.getByText('基本報酬額を入力してください')).toBeVisible({ timeout: 10_000 })
  })

  test('JOB-002-04: 現地勤務で勤務先住所が空 → 「勤務先住所を入力してください」が表示される', async ({ page }) => {
    // ページのデフォルト値は workLocationType=ONSITE なので、workAddress を空のままにすれば
    // 他のバリデーションを通過した後に workAddressRequired が発火する
    await page.goto(`/teams/${TEAM_ID}/jobs/new`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '新規求人作成' })).toBeVisible({
      timeout: 10_000,
    })

    // タイトル・説明・報酬を入れる（ONSITE デフォルトのため住所は空）
    const titleInput = page.locator('input#job-title')
    await titleInput.click()
    await titleInput.pressSequentially('テスト求人', { delay: 10 })

    const descTextarea = page.locator('textarea#job-description')
    await descTextarea.click()
    await descTextarea.pressSequentially('テスト説明', { delay: 10 })

    const rewardInput = page.locator('#job-base-reward')
    await rewardInput.click()
    await rewardInput.pressSequentially('5000', { delay: 10 })

    await page.getByRole('button', { name: '下書き保存' }).click()

    // i18n jobmatching.validation.workAddressRequired = "現地勤務の場合、勤務先住所を入力してください"
    await expect(page.getByText('現地勤務の場合、勤務先住所を入力してください')).toBeVisible({
      timeout: 10_000,
    })
  })
})
