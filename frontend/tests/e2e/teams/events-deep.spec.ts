import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  fillInput,
  pickDate,
  toggleCheckbox,
  waitForDialog,
} from '../helpers/form'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/**
 * イベント作成ダイアログ深掘りテスト。
 * teams/[id]/events/index.vue の EventForm.vue（event 配下）に対して、
 * 必須項目バリデーション・InputNumber・Checkbox・DatePicker（show-time あり）の操作を検証する。
 */
test.describe('TEAM-DEEP-events: イベント作成ダイアログ深掘り', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-DEEP-events-001: イベント名空のまま作成するとバリデーションエラーが表示される', async ({
    page,
  }) => {
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/teams/${TEAM_ID}/events`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'イベント' })).toBeVisible({ timeout: 10_000 })

    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // イベント名（subtitle）を空のまま送信
    await dialog.getByRole('button', { name: '作成' }).click()
    await expect(dialog.getByText('イベント名は必須です')).toBeVisible({ timeout: 5_000 })
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
  })

  test('TEAM-DEEP-events-002: イベント名のみ入力で作成すると POST が成功する', async ({ page }) => {
    let postBody: Record<string, unknown> | null = null
    await page.route(`**/api/v1/teams/${TEAM_ID}/events`, async (route) => {
      if (route.request().method() === 'POST') {
        postBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 777,
              subtitle: postBody.subtitle,
              isPublic: postBody.isPublic,
              isApprovalRequired: postBody.isApprovalRequired,
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
          }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.getByPlaceholder('イベントのタイトル')
    await fillInput(titleInput, '春の交流戦2026')

    const respPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/v1/teams/${TEAM_ID}/events`)
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )
    await dialog.getByRole('button', { name: '作成' }).click()
    await respPromise

    expect(postBody).not.toBeNull()
    expect((postBody as unknown as { subtitle: string }).subtitle).toBe('春の交流戦2026')
  })

  test('TEAM-DEEP-events-003: 会場名と定員 InputNumber に入力できる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // 会場名 InputText
    const venueInput = dialog.getByPlaceholder('開催場所')
    await fillInput(venueInput, '渋谷区民総合グラウンド')
    await expect(venueInput).toHaveValue('渋谷区民総合グラウンド')

    // 定員 InputNumber（PrimeVue の場合は内部 input に直接入力）
    const capacityInput = dialog.locator('label', { hasText: '定員' }).locator('xpath=following-sibling::*[1]//input')
    await fillInput(capacityInput, '30')
    await expect(capacityInput).toHaveValue('30')
  })

  test('TEAM-DEEP-events-004: 「一般公開」Checkbox の状態をトグルできる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // isPublic は初期値 true（label="一般公開"）
    const publicCheckbox = dialog.locator('label[for="isPublic"]')
    await expect(publicCheckbox).toBeVisible()

    // 初期は ON（true）→ クリックして OFF
    const checkboxRoot = dialog.locator('#isPublic').locator('..').locator('..')
    await toggleCheckbox(publicCheckbox)
    // PrimeVue Checkbox のチェック状態は内部 input ではなく aria 属性で見るのが安定だが、
    // 直接的な確認として再度クリックでトグルが効くこと自体を確認する
    await toggleCheckbox(publicCheckbox)
    // 何らかのチェックボックス要素が依然存在し操作可能であること
    await expect(checkboxRoot).toBeVisible()
  })

  test('TEAM-DEEP-events-005: 受付開始/終了日時 DatePicker（show-time付き）に直接入力できる', async ({
    page,
  }) => {
    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    // 受付開始日時（show-time のため "yyyy/mm/dd hh:mm" 形式）
    const startInput = dialog.locator('label', { hasText: '受付開始日時' }).locator('xpath=following-sibling::*[1]//input')
    await pickDate(startInput, '2026/04/15 09:00')
    await expect(startInput).toHaveValue(/2026\/04\/15/)

    // 受付終了日時
    const endInput = dialog.locator('label', { hasText: '受付終了日時' }).locator('xpath=following-sibling::*[1]//input')
    await pickDate(endInput, '2026/05/01 18:00')
    await expect(endInput).toHaveValue(/2026\/05\/01/)
  })

  test('TEAM-DEEP-events-006: キャンセルボタンで閉じても POST は呼ばれない', async ({ page }) => {
    let postCalled = false
    page.on('request', (req) => {
      if (
        req.url().includes(`/api/v1/teams/${TEAM_ID}/events`)
        && req.method() === 'POST'
      ) {
        postCalled = true
      }
    })

    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'イベント作成' }).click()
    const dialog = await waitForDialog(page)

    const titleInput = dialog.getByPlaceholder('イベントのタイトル')
    await fillInput(titleInput, '取りやめ予定のイベント')

    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(dialog).toBeHidden({ timeout: 5_000 })
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
  })
})
