import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  fillInput,
  pickDate,
  toggleCheckbox,
  waitForDialog,
  clearAndFillInput,
} from '../helpers/form'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

/**
 * 組織版スケジュール作成フォーム深掘りテスト。
 * organizations/[id]/schedule.vue 配下の EventForm.vue（schedule 配下）に対し、
 * 必須項目バリデーション・終日トグル・場所/説明入力・キャンセル動作を検証する。
 *
 * チーム版と異なり API は /api/v1/organizations/{id}/schedules を使用する。
 * EventForm 自体は scope-type="organization" を受け取って共通化されている。
 */
test.describe('ORG-DEEP-schedule: 組織スケジュール作成フォーム深掘り', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
  })

  test('ORG-DEEP-schedule-001: タイトル空のまま作成ボタンを押すとバリデーションエラーが表示される', async ({
    page,
  }) => {
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/organizations/${ORG_ID}/schedules`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({
      timeout: 10_000,
    })

    // 「イベント作成」ボタンでダイアログを開く
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // タイトル未入力のまま作成
    await dialog.getByRole('button', { name: '作成' }).click()
    await expect(dialog.getByText('タイトルは必須です')).toBeVisible({ timeout: 5_000 })
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
  })

  test('ORG-DEEP-schedule-002: タイトルと開始日/終了日を入力すると POST が成功する', async ({
    page,
  }) => {
    let postBody: Record<string, unknown> | null = null
    await page.route(`**/api/v1/organizations/${ORG_ID}/schedules`, async (route) => {
      if (route.request().method() === 'POST') {
        postBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 200,
              title: postBody.title,
              startAt: postBody.startAt,
              endAt: postBody.endAt,
              allDay: postBody.allDay,
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { page: 0, size: 100, totalElements: 0, totalPages: 0 },
          }),
        })
      }
    })

    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // タイトル入力
    const titleInput = dialog.locator('label:has-text("タイトル") + input')
    await fillInput(titleInput, '組織理事会ミーティング')

    // 開始日・終了日を入力（input-id="schedule-start-date" / "schedule-end-date"）
    // dialog.locator() は .last() の遅延評価でカレンダーパネル開放後に誤ダイアログを参照するため
    // page レベルで ID 直指定することでカレンダーダイアログの影響を回避する
    const startDateInput = page.locator('#schedule-start-date')
    await pickDate(startDateInput, '2026/05/15')
    const endDateInput = page.locator('#schedule-end-date')
    await pickDate(endDateInput, '2026/05/15')

    const respPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/v1/organizations/${ORG_ID}/schedules`)
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )
    await dialog.getByRole('button', { name: '作成' }).click()
    await respPromise

    expect(postBody).not.toBeNull()
    expect((postBody as unknown as { title: string }).title).toBe('組織理事会ミーティング')
  })

  test('ORG-DEEP-schedule-003: 終日 ToggleSwitch をオンにすると時刻入力欄が消える', async ({
    page,
  }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // 初期状態では「開始時刻」「終了時刻」のラベルが見える
    await expect(dialog.locator('label:has-text("開始時刻")')).toBeVisible()
    await expect(dialog.locator('label:has-text("終了時刻")')).toBeVisible()

    // 終日ラベルの隣にある ToggleSwitch をクリック（PrimeVue ToggleSwitch ラッパー）
    const allDayToggle = dialog.locator('.p-toggleswitch').first()
    await toggleCheckbox(allDayToggle)

    // 時刻入力欄が消える（v-if で削除）
    await expect(dialog.locator('label:has-text("開始時刻")')).toBeHidden()
    await expect(dialog.locator('label:has-text("終了時刻")')).toBeHidden()

    // もう一度クリックでオフに戻る
    await toggleCheckbox(allDayToggle)
    await expect(dialog.locator('label:has-text("開始時刻")')).toBeVisible()
  })

  test('ORG-DEEP-schedule-004: 場所と説明の自由記述欄に入力できる', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    const locationInput = dialog.getByPlaceholder('場所（任意）')
    await fillInput(locationInput, '組織本部 第1会議室')
    await expect(locationInput).toHaveValue('組織本部 第1会議室')

    // 説明 Textarea
    const descTextarea = dialog.locator('label:has-text("説明") + textarea')
    await fillInput(descTextarea, '議題: 来期予算 → 新規企画 → 質疑応答')
    await expect(descTextarea).toHaveValue('議題: 来期予算 → 新規企画 → 質疑応答')
  })

  test('ORG-DEEP-schedule-005: タイトル入力後にクリアして再入力できる + キャンセルでダイアログが閉じる', async ({
    page,
  }) => {
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/organizations/${ORG_ID}/schedules`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // 入力 → クリア → 再入力
    const titleInput = dialog.locator('label:has-text("タイトル") + input')
    await fillInput(titleInput, '間違った組織予定')
    await expect(titleInput).toHaveValue('間違った組織予定')
    await clearAndFillInput(titleInput, '正しい組織予定')
    await expect(titleInput).toHaveValue('正しい組織予定')

    // キャンセルでダイアログが閉じ、POST は発行されない
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(dialog).toBeHidden({ timeout: 5_000 })
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
  })
})
