import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * F22.1 謝礼決済 フォロー Wave B — 受取側 ADMIN の謝礼受取／返金管理画面 E2E スモーク。
 *
 * 設計思想（market.spec.ts を手本にする）:
 *   - すべての API を page.route() でモックし、バックエンド非起動の CI でも決定的に動作させる。
 *   - 認証は addInitScript で localStorage の currentUser を注入し、/auth/refresh をモックする。
 *   - data-testid セレクタを優先。
 *
 * テストID:
 *   RECVPAY-001: 受取一覧の表示 → 返金可能行で返金ダイアログを開ける
 */

const MOCK_USER = {
  id: 1,
  email: 'payee@example.com',
  fullName: '受取ユーザー',
  profileImageUrl: null,
}

/**
 * 受取側エスクロー一覧モック（BE 契約 camelCase・ReceivedEscrowResponse / PagedResponse と 1:1）。
 * meta は BE PagedResponse.PageMeta（total/page/size/totalPages）。
 */
const MOCK_RECEIVED_PAGE = {
  data: [
    {
      escrowTransactionId: '11111111-1111-7111-8111-111111111111',
      sourceKind: 'RECRUITMENT',
      sourceId: 1001,
      sourceParticipantId: 5001,
      captureMode: 'MANUAL',
      status: 'CAPTURED',
      faceAmount: 3000,
      chargeAmount: 3075,
      applicationFeeAmount: 150,
      refundedAmount: 0,
      createdAt: '2026-06-01T10:00:00',
    },
    {
      escrowTransactionId: '22222222-2222-7222-8222-222222222222',
      sourceKind: 'RECRUITMENT',
      sourceId: 1002,
      sourceParticipantId: 5002,
      captureMode: 'MANUAL',
      status: 'PENDING_CONFIRMATION',
      faceAmount: 5000,
      chargeAmount: 5125,
      applicationFeeAmount: 250,
      refundedAmount: 0,
      createdAt: '2026-06-02T11:00:00',
    },
  ],
  meta: { total: 2, page: 0, size: 20, totalPages: 1 },
}

async function injectAuth(page: Page): Promise<void> {
  await page.addInitScript((user) => {
    localStorage.setItem('currentUser', JSON.stringify(user))
  }, MOCK_USER)
  await page.route('**/api/v1/auth/refresh**', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { accessToken: 'mock', user: MOCK_USER } }),
    })
  })
}

async function mockScopeAndPayments(page: Page): Promise<void> {
  // scope 候補: 本人のみ（チーム/組織は空配列）。
  await page.route('**/api/v1/me/teams**', (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) }),
  )
  await page.route('**/api/v1/me/organizations**', (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) }),
  )
  // Connect 状態（onboarding 部品）: 未登録扱い（404）。
  await page.route('**/api/v1/payment/connect/status**', (route: Route) =>
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ message: 'not found' }) }),
  )
  // 受取側一覧。
  await page.route('**/api/v1/payment/escrow/received**', (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_RECEIVED_PAGE) }),
  )
}

test.describe('F22.1 受取側 謝礼受取・返金管理（RECVPAY）', () => {
  // 本スペックは injectAuth で独自に認証を注入するため、setup プロジェクトの storageState に依存しない。
  test.use({ storageState: { cookies: [], origins: [] } })


  test('RECVPAY-001: 受取一覧の表示 → 返金可能行で返金ダイアログを開ける', async ({ page }) => {
    await injectAuth(page)
    await mockScopeAndPayments(page)

    await page.goto('/me/recruitment-payments')

    // 一覧が表示される（2 行）。
    const rows = page.getByTestId('received-row')
    await expect(rows).toHaveCount(2)

    // CAPTURED 行には返金ボタンが出る。PENDING_CONFIRMATION 行には出ない。
    const refundButtons = page.getByTestId('received-refund-btn')
    await expect(refundButtons).toHaveCount(1)

    // 返金ボタン押下でダイアログが開く（feeBearer ラジオ等が見える）。
    await refundButtons.first().click()
    await expect(page.getByText('決済手数料の負担者')).toBeVisible()
  })
})
