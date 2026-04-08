import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  fillInput,
  pickDate,
  toggleCheckbox,
  waitForDialog,
  clearAndFillInput,
} from '../helpers/form'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/**
 * スケジュール作成フォーム深掘りテスト。
 * teams/[id]/schedule.vue 配下の EventForm.vue（schedule 配下）に対し、
 * 必須項目バリデーション・終日トグル・場所/説明入力・キャンセル動作を検証する。
 */
test.describe('TEAM-DEEP-schedule: スケジュール作成フォーム深掘り', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-DEEP-schedule-001: タイトル空のまま作成ボタンを押すとバリデーションエラーが表示される', async ({
    page,
  }) => {
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/teams/${TEAM_ID}/schedules`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/teams/${TEAM_ID}/schedule`)
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

  test('TEAM-DEEP-schedule-002: タイトルと開始日/終了日を入力すると POST が成功する', async ({
    page,
  }) => {
    let postBody: Record<string, unknown> | null = null
    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules`, async (route) => {
      if (route.request().method() === 'POST') {
        postBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 100,
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

    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // タイトル入力
    const titleInput = dialog.locator('label:has-text("タイトル") + input')
    await fillInput(titleInput, 'チーム合同練習')

    // 開始日・終了日を入力
    const startDateInput = dialog.locator('label', { hasText: '開始日' }).locator('xpath=following-sibling::*[1]//input')
    await pickDate(startDateInput, '2026/05/01')
    const endDateInput = dialog.locator('label', { hasText: '終了日' }).locator('xpath=following-sibling::*[1]//input')
    await pickDate(endDateInput, '2026/05/01')

    const respPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/v1/teams/${TEAM_ID}/schedules`)
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )
    await dialog.getByRole('button', { name: '作成' }).click()
    await respPromise

    expect(postBody).not.toBeNull()
    expect((postBody as unknown as { title: string }).title).toBe('チーム合同練習')
  })

  test('TEAM-DEEP-schedule-003: 終日 ToggleSwitch をオンにすると時刻入力欄が消える', async ({
    page,
  }) => {
    await page.goto(`/teams/${TEAM_ID}/schedule`)
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

  test('TEAM-DEEP-schedule-004: 場所と説明の自由記述欄に入力できる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    const locationInput = dialog.getByPlaceholder('場所（任意）')
    await fillInput(locationInput, '渋谷区民体育館 第2アリーナ')
    await expect(locationInput).toHaveValue('渋谷区民体育館 第2アリーナ')

    // 説明 Textarea
    const descTextarea = dialog.locator('label:has-text("説明") + textarea')
    await fillInput(descTextarea, 'ストレッチ → 基礎練 → ゲーム形式練習の予定')
    await expect(descTextarea).toHaveValue('ストレッチ → 基礎練 → ゲーム形式練習の予定')
  })

  test('TEAM-DEEP-schedule-005: タイトル入力後にクリアして再入力できる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.locator('label:has-text("タイトル") + input')
    await fillInput(titleInput, '間違ったタイトル')
    await expect(titleInput).toHaveValue('間違ったタイトル')

    await clearAndFillInput(titleInput, '正しいタイトル')
    await expect(titleInput).toHaveValue('正しいタイトル')
  })

  test('TEAM-DEEP-schedule-006: キャンセルボタンでダイアログを閉じても POST は呼ばれない', async ({
    page,
  }) => {
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/teams/${TEAM_ID}/schedules`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.locator('label:has-text("タイトル") + input')
    await fillInput(titleInput, '送信前にキャンセル')

    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(dialog).toBeHidden({ timeout: 5_000 })
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
  })
})
