import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  INVITE_TOKEN,
  setupAuth,
  mockCatchAllApis,
  buildCareLink,
  buildInvitationResponse,
  mockGetWatchers,
  mockGetRecipients,
  mockGetInvitations,
  mockGetInvitationByToken,
  mockAcceptInvitation,
  mockRejectInvitation,
  mockInviteWatcher,
} from './_helpers'

/**
 * F03.12 ケアリンク管理 Phase 6 — CARE-007〜010: 招待フロー E2E テスト。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>CARE-007: 招待承認ページが表示される（招待情報が表示される）</li>
 *   <li>CARE-008: 招待を承認できる</li>
 *   <li>CARE-009: 招待を拒否できる</li>
 *   <li>CARE-010: 見守り者招待フォームが送信できる</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.12_care_event_watch.md</p>
 */

const INVITATION_URL = `/care-links/invitations/${INVITE_TOKEN}`
const INVITE_WATCHER_URL = '/me/care-links/invite-watcher'

test.describe('CARE-007〜010: F03.12 Phase 6 招待フロー', () => {
  test.beforeEach(async ({ page }) => {
    // catch-all で全APIに空レスポンスを設定（後で個別上書き）
    await mockCatchAllApis(page)
  })

  test('CARE-007: 招待承認ページが表示される', async ({ page }) => {
    // 招待情報をモック
    const invitation = buildInvitationResponse({
      inviterDisplayName: '招待者テスト花子',
      careCategory: 'MINOR',
      relationship: 'PARENT',
      expiresAt: '2026-05-01T00:00:00Z',
    })
    await mockGetInvitationByToken(page, INVITE_TOKEN, invitation)

    await page.goto(INVITATION_URL)
    await waitForHydration(page)

    // 招待確認ページのタイトルが表示される（i18n care.page.invitationAccept = "招待の確認"）
    await expect(page.getByText('招待の確認')).toBeVisible({ timeout: 10_000 })

    // 招待者の表示名が表示される（i18n care.label.inviterName = "招待者"）
    await expect(page.getByText('招待者テスト花子')).toBeVisible({ timeout: 10_000 })

    // ケアカテゴリが表示される（i18n care.category.MINOR = "未成年"）
    await expect(page.getByText('未成年')).toBeVisible({ timeout: 10_000 })

    // 続柄が表示される（i18n care.relationship.PARENT = "親"）
    await expect(page.getByText('親')).toBeVisible({ timeout: 10_000 })

    // 「招待を承認」ボタンが表示される（i18n care.button.acceptInvitation = "招待を承認"）
    await expect(page.getByRole('button', { name: '招待を承認' })).toBeVisible({
      timeout: 10_000,
    })

    // 「招待を拒否」ボタンが表示される（i18n care.button.rejectInvitation = "招待を拒否"）
    await expect(page.getByRole('button', { name: '招待を拒否' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('CARE-008: 招待を承認できる', async ({ page }) => {
    // 招待情報をモック
    const invitation = buildInvitationResponse({ inviterDisplayName: '承認テスト招待者' })
    await mockGetInvitationByToken(page, INVITE_TOKEN, invitation)

    // accept エンドポイントをモック（200 返す）
    let acceptCalled = false
    await page.route(`**/api/v1/care-links/invitations/${INVITE_TOKEN}/accept`, async (route) => {
      acceptCalled = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: buildCareLink({ status: 'ACTIVE' }) }),
      })
    })

    await page.goto(INVITATION_URL)
    await waitForHydration(page)

    // 招待ページが表示されることを確認
    await expect(page.getByText('招待の確認')).toBeVisible({ timeout: 10_000 })

    // 「招待を承認」ボタンをクリック
    const acceptBtn = page.getByRole('button', { name: '招待を承認' })
    await expect(acceptBtn).toBeVisible({ timeout: 10_000 })
    await acceptBtn.click()

    // accept API が呼ばれるのを待つ
    await page
      .waitForResponse(
        (resp) =>
          resp.url().includes(`/care-links/invitations/${INVITE_TOKEN}/accept`) &&
          resp.request().method() === 'POST',
        { timeout: 5_000 },
      )
      .catch(() => null)

    // accept API が呼ばれたことを確認
    expect(acceptCalled).toBe(true)

    // 完了フェーズ（done 状態）が表示される（i18n care.message.acceptSuccess = "招待を承認しました"）
    await expect(page.getByText('招待を承認しました')).toBeVisible({ timeout: 10_000 })
  })

  test('CARE-009: 招待を拒否できる', async ({ page }) => {
    // 招待情報をモック
    const invitation = buildInvitationResponse({ inviterDisplayName: '拒否テスト招待者' })
    await mockGetInvitationByToken(page, INVITE_TOKEN, invitation)

    // reject エンドポイントをモック（204 返す）
    let rejectCalled = false
    await page.route(`**/api/v1/care-links/invitations/${INVITE_TOKEN}/reject`, async (route) => {
      rejectCalled = true
      await route.fulfill({
        status: 204,
        contentType: 'application/json',
        body: '',
      })
    })

    await page.goto(INVITATION_URL)
    await waitForHydration(page)

    // 招待ページが表示されることを確認
    await expect(page.getByText('招待の確認')).toBeVisible({ timeout: 10_000 })

    // 「招待を拒否」ボタンをクリック
    const rejectBtn = page.getByRole('button', { name: '招待を拒否' })
    await expect(rejectBtn).toBeVisible({ timeout: 10_000 })
    await rejectBtn.click()

    // reject API が呼ばれるのを待つ
    await page
      .waitForResponse(
        (resp) =>
          resp.url().includes(`/care-links/invitations/${INVITE_TOKEN}/reject`) &&
          resp.request().method() === 'POST',
        { timeout: 5_000 },
      )
      .catch(() => null)

    // reject API が呼ばれたことを確認
    expect(rejectCalled).toBe(true)

    // 完了フェーズ（done 状態）が表示される（i18n care.message.rejectSuccess = "招待を拒否しました"）
    await expect(page.getByText('招待を拒否しました')).toBeVisible({ timeout: 10_000 })
  })

  test('CARE-010: 見守り者招待フォームが送信できる', async ({ page }) => {
    // 認証済み状態を設定（invite-watcher ページは auth ミドルウェアあり）
    await setupAuth(page)

    // invite-watcher POST モック（201 返す）
    let inviteCalled = false
    const createdLink = buildCareLink({ status: 'PENDING', invitedBy: 'CARE_RECIPIENT' })
    await page.route('**/api/v1/me/care-links/invite-watcher', async (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        inviteCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: createdLink }),
        })
      } else {
        await route.continue()
      }
    })

    // リダイレクト先の /me/care-links API をモック（遷移後のデータ取得）
    await mockGetWatchers(page, [])
    await mockGetRecipients(page, [])
    await mockGetInvitations(page, [])

    await page.goto(INVITE_WATCHER_URL)
    await waitForHydration(page)

    // ページタイトルが表示される（i18n care.page.inviteWatcher = "見守り者を招待"）
    await expect(page.getByRole('heading', { name: '見守り者を招待' })).toBeVisible({ timeout: 10_000 })

    // ユーザーIDを入力（InputNumber コンポーネント）
    const userIdInput = page.locator('input[type="text"]').or(page.locator('input[inputmode]')).first()
    await userIdInput.fill('42')

    // ケアカテゴリは既定値（GENERAL_FAMILY）のままにする
    // 続柄は既定値（OTHER）のままにする

    // 送信ボタンをクリック（i18n care.button.inviteWatcher = "見守り者を招待"）
    const submitBtn = page.getByRole('button', { name: '見守り者を招待' })
    await expect(submitBtn).toBeVisible({ timeout: 10_000 })
    await submitBtn.click()

    // POST API が呼ばれるのを待つ
    await page
      .waitForResponse(
        (resp) =>
          resp.url().includes('/me/care-links/invite-watcher') &&
          resp.request().method() === 'POST',
        { timeout: 5_000 },
      )
      .catch(() => null)

    // invite-watcher API が呼ばれたことを確認
    expect(inviteCalled).toBe(true)
  })
})
