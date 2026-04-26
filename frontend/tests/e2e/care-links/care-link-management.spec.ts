import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CARE_LINK_ID,
  setupAuth,
  mockCatchAllApis,
  buildCareLink,
  mockGetWatchers,
  mockGetRecipients,
  mockGetInvitations,
  mockDeleteLink,
  mockUpdateSettings,
} from './_helpers'

/**
 * F03.12 ケアリンク管理 Phase 6 — CARE-001〜006: 管理画面 E2E テスト。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>CARE-001: ケアリンク管理ページが表示される（3タブが存在する）</li>
 *   <li>CARE-002: 見守り者一覧が表示される</li>
 *   <li>CARE-003: ケア対象者一覧が表示される</li>
 *   <li>CARE-004: 保留中の招待が表示される</li>
 *   <li>CARE-005: ケアリンクを解除できる</li>
 *   <li>CARE-006: 通知設定を更新できる</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.12_care_event_watch.md</p>
 */

const CARE_LINKS_URL = '/me/care-links'

test.describe('CARE-001〜006: F03.12 Phase 6 ケアリンク管理画面', () => {
  test.beforeEach(async ({ page }) => {
    // メンバーとして認証済み状態を設定
    await setupAuth(page)
    // catch-all で全APIに空レスポンスを設定（後で個別上書き）
    await mockCatchAllApis(page)
  })

  test('CARE-001: ケアリンク管理ページが表示される', async ({ page }) => {
    // 空のデータでモック
    await mockGetWatchers(page, [])
    await mockGetRecipients(page, [])
    await mockGetInvitations(page, [])

    await page.goto(CARE_LINKS_URL)
    await waitForHydration(page)

    // ページタイトルが表示される（i18n care.page.title = "ケアリンク管理"）
    await expect(page.getByText('ケアリンク管理')).toBeVisible({ timeout: 10_000 })

    // 「見守り者」タブが表示される（i18n care.tab.watchers = "見守り者"）
    await expect(page.getByRole('tab', { name: '見守り者' })).toBeVisible({ timeout: 10_000 })

    // 「ケア対象者」タブが表示される（i18n care.tab.recipients = "ケア対象者"）
    await expect(page.getByRole('tab', { name: 'ケア対象者' })).toBeVisible({ timeout: 10_000 })

    // 「保留中の招待」タブが表示される（i18n care.tab.pending = "保留中の招待"）
    await expect(page.getByRole('tab', { name: '保留中の招待' })).toBeVisible({ timeout: 10_000 })
  })

  test('CARE-002: 見守り者一覧が表示される', async ({ page }) => {
    // 見守り者 2件をモック
    const link1 = buildCareLink({ id: 101, watcherDisplayName: '見守り花子' })
    const link2 = buildCareLink({ id: 102, watcherDisplayName: '見守り次郎' })
    await mockGetWatchers(page, [link1, link2])
    await mockGetRecipients(page, [])
    await mockGetInvitations(page, [])

    await page.goto(CARE_LINKS_URL)
    await waitForHydration(page)

    // ページ表示完了を待つ
    await expect(page.getByText('ケアリンク管理')).toBeVisible({ timeout: 10_000 })

    // 見守り者タブがデフォルトで表示されている
    // 見守り者の表示名が一覧に表示される
    await expect(page.getByText('見守り花子')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('見守り次郎')).toBeVisible({ timeout: 10_000 })
  })

  test('CARE-003: ケア対象者一覧が表示される', async ({ page }) => {
    // ケア対象者 1件をモック
    const recipient = buildCareLink({
      id: 103,
      careRecipientDisplayName: 'ケア太郎',
      status: 'ACTIVE',
    })
    await mockGetWatchers(page, [])
    await mockGetRecipients(page, [recipient])
    await mockGetInvitations(page, [])

    await page.goto(CARE_LINKS_URL)
    await waitForHydration(page)

    await expect(page.getByText('ケアリンク管理')).toBeVisible({ timeout: 10_000 })

    // 「ケア対象者」タブをクリック（i18n care.tab.recipients = "ケア対象者"）
    await page.getByRole('tab', { name: 'ケア対象者' }).click()

    // ケア対象者の表示名が表示される
    await expect(page.getByText('ケア太郎')).toBeVisible({ timeout: 10_000 })
  })

  test('CARE-004: 保留中の招待が表示される', async ({ page }) => {
    // 保留中招待 1件をモック（PENDING 状態）
    const pendingInvitation = buildCareLink({
      id: 104,
      status: 'PENDING',
      watcherDisplayName: '招待者山田',
    })
    await mockGetWatchers(page, [])
    await mockGetRecipients(page, [])
    await mockGetInvitations(page, [pendingInvitation])

    await page.goto(CARE_LINKS_URL)
    await waitForHydration(page)

    await expect(page.getByText('ケアリンク管理')).toBeVisible({ timeout: 10_000 })

    // 「保留中の招待」タブをクリック（i18n care.tab.pending = "保留中の招待"）
    await page.getByRole('tab', { name: '保留中の招待' }).click()

    // 招待者名が表示される
    await expect(page.getByText('招待者山田')).toBeVisible({ timeout: 10_000 })
  })

  test('CARE-005: ケアリンクを解除できる', async ({ page }) => {
    // 見守り者リストに 1件表示
    const link = buildCareLink({ id: CARE_LINK_ID, watcherDisplayName: '解除テスト花子' })
    await mockGetWatchers(page, [link])
    await mockGetRecipients(page, [])
    await mockGetInvitations(page, [])

    // DELETE モック（204 返す）
    let deleteCalled = false
    await page.route(`**/api/v1/me/care-links/${CARE_LINK_ID}`, async (route) => {
      const method = route.request().method()
      if (method === 'DELETE') {
        deleteCalled = true
        await route.fulfill({
          status: 204,
          contentType: 'application/json',
          body: '',
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(CARE_LINKS_URL)
    await waitForHydration(page)

    await expect(page.getByText('ケアリンク管理')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('解除テスト花子')).toBeVisible({ timeout: 10_000 })

    // 解除ボタンをクリック（i18n care.button.deleteLink = "リンクを解除"）
    const deleteBtn = page.getByRole('button', { name: 'リンクを解除' }).first()
    await expect(deleteBtn).toBeVisible({ timeout: 10_000 })
    await deleteBtn.click()

    // 確認ダイアログが表示される（i18n care.dialog.deleteLinkTitle = "ケアリンクを解除"）
    await expect(page.getByText('ケアリンクを解除', { exact: true })).toBeVisible({ timeout: 10_000 })

    // ダイアログ内の確認ボタンをクリック
    // ダイアログのフッター内にある「リンクを解除」ボタンを押す
    const confirmBtn = page.locator('[role="dialog"]').getByRole('button', { name: 'リンクを解除' })
    await expect(confirmBtn).toBeVisible({ timeout: 5_000 })
    await confirmBtn.click()

    // DELETE API が呼ばれたことを確認
    await page
      .waitForResponse(
        (resp) =>
          resp.url().includes(`/me/care-links/${CARE_LINK_ID}`) &&
          resp.request().method() === 'DELETE',
        { timeout: 5_000 },
      )
      .catch(() => null)

    expect(deleteCalled).toBe(true)
  })

  test('CARE-006: 通知設定を更新できる', async ({ page }) => {
    // 見守り者リストに 1件表示
    const link = buildCareLink({ id: CARE_LINK_ID, watcherDisplayName: '通知設定テスト' })
    await mockGetWatchers(page, [link])
    await mockGetRecipients(page, [])
    await mockGetInvitations(page, [])

    // PATCH モック（更新後レスポンス返す）
    let patchCalled = false
    const updatedLink = buildCareLink({ ...link, notifyOnRsvp: false })
    await page.route(`**/api/v1/me/care-links/${CARE_LINK_ID}`, async (route) => {
      const method = route.request().method()
      if (method === 'PATCH') {
        patchCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: updatedLink }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(CARE_LINKS_URL)
    await waitForHydration(page)

    await expect(page.getByText('ケアリンク管理')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('通知設定テスト')).toBeVisible({ timeout: 10_000 })

    // 通知設定ボタンをクリック（i18n care.label.notifySettings = "通知設定"）
    const notifyBtn = page.getByRole('button', { name: '通知設定' }).first()
    await expect(notifyBtn).toBeVisible({ timeout: 10_000 })
    await notifyBtn.click()

    // ダイアログが開く（i18n care.label.notifySettings = "通知設定"）
    await expect(page.locator('[role="dialog"]')).toBeVisible({ timeout: 10_000 })

    // 保存ボタンをクリック（i18n care.button.updateNotify = "通知設定を保存"）
    const saveBtn = page
      .locator('[role="dialog"]')
      .getByRole('button', { name: '通知設定を保存' })
    await expect(saveBtn).toBeVisible({ timeout: 5_000 })
    await saveBtn.click()

    // PATCH API が呼ばれたことを確認
    await page
      .waitForResponse(
        (resp) =>
          resp.url().includes(`/me/care-links/${CARE_LINK_ID}`) &&
          resp.request().method() === 'PATCH',
        { timeout: 5_000 },
      )
      .catch(() => null)

    expect(patchCalled).toBe(true)
  })
})
