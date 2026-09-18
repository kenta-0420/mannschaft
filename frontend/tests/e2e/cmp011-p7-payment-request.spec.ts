import { expect, test, type Page, type Route } from '@playwright/test'
import { setupAuth, waitForHydration } from './surveys/_helpers'

const ORG_ID = 1
const TEAM_ID = 2
const REQUEST_ID = 'payment-request-1'

const draftRequest = (status: 'DRAFT' | 'SENT' = 'DRAFT') => ({
  id: REQUEST_ID,
  organizationId: ORG_ID,
  payerScopeId: TEAM_ID,
  title: '大会参加費',
  description: '秋季大会の参加費',
  faceAmount: 1500,
  currency: 'JPY',
  dueDate: '2026-12-31',
  status,
})

const processingRequest = (status: 'PROCESSING' | 'PAID') => ({
  ...draftRequest(),
  status,
})

const pendingAdvance = (settlementStatus: 'PENDING' | 'SETTLED') => ({
  id: 'advance-1',
  payerUserId: 10,
  advancedAmount: 1500,
  currency: 'JPY',
  settlementStatus,
})

async function mockCommon(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem('locale', 'ja')
  })

  await page.route('**/api/v1/auth/refresh', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { accessToken: 'e2e-token', refreshToken: 'e2e-refresh' } }),
    })
  })
  await page.route('**/api/v1/notifications/unread-count', emptyResponse)
  await page.route('**/api/v1/chat/channels**', emptyResponse)
  await page.route('**/api/v1/mentions**', emptyResponse)

  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, jsonResponse({ data: { roleName: 'ADMIN', permissions: [] } }))
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, jsonResponse({ data: { roleName: 'ADMIN', permissions: [] } }))
  await page.route(`**/api/v1/me/organizations`, jsonResponse({ data: [{ id: ORG_ID, slug: String(ORG_ID) }] }))
  await page.route(`**/api/v1/me/teams`, jsonResponse({ data: [{ id: TEAM_ID, slug: String(TEAM_ID) }] }))
  await page.route(`**/api/v1/organizations/${ORG_ID}/teams`, jsonResponse({ data: [{ id: TEAM_ID, name: 'Team A', nickname1: 'Team A' }] }))

  // 親 shell が表示に使うデータ。子画面の契約とは無関係な API は空の正常応答にする。
  await page.route(`**/api/v1/organizations/${ORG_ID}`, jsonResponse({ data: { id: ORG_ID, name: 'Organization A', nickname1: 'Organization A' } }))
  await page.route(`**/api/v1/teams/${TEAM_ID}`, jsonResponse({ data: { id: TEAM_ID, name: 'Team A', nickname1: 'Team A' } }))
}

function emptyResponse(route: Route): Promise<void> {
  if (route.request().method() !== 'GET') return route.continue()
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) })
}

function jsonResponse(body: unknown, status = 200) {
  return async (route: Route): Promise<void> => {
    if (route.request().method() !== 'GET') return route.continue()
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  }
}

test.describe('CMP-011 P7 支払依頼 UI', () => {
  test('組織側で支払依頼を作成して送信できる', async ({ page }) => {
    await setupAuth(page, { userId: 1, displayName: '管理者', role: 'ADMIN', scopeType: 'ORGANIZATION', scopeId: ORG_ID })
    await mockCommon(page)

    let requests = [draftRequest()]
    await page.route(`**/api/v1/organizations/${ORG_ID}/payment-requests**`, async (route) => {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON() as Record<string, unknown>
        requests = [{ ...draftRequest(), title: String(body.title), faceAmount: Number(body.faceAmount) }]
        await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ data: requests[0] }) })
        return
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: requests, meta: { total: requests.length } }) })
    })
    await page.route(`**/api/v1/organizations/${ORG_ID}/payment-requests/${REQUEST_ID}/send`, async (route) => {
      expect(route.request().method()).toBe('PATCH')
      requests = [{ ...requests[0], status: 'SENT' }]
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: requests[0] }) })
    })

    await page.goto(`/organizations/${ORG_ID}/payment-requests/new`)
    await waitForHydration(page)
    await page.locator('#payer-team').click()
    await page.getByText('Team A', { exact: true }).last().click()
    await page.locator('#request-title').fill('大会参加費')
    await page.locator('#request-description').fill('秋季大会の参加費')
    await page.locator('#face-amount').fill('1500')
    await page.locator('#due-date').fill('2026/12/31')
    await page.getByRole('button', { name: /作成/ }).click()

    await expect(page).toHaveURL(new RegExp(`/organizations/${ORG_ID}/payment-requests$`))
    await expect(page.getByText('大会参加費', { exact: true })).toBeVisible()
    await page.getByTestId(`send-payment-request-${REQUEST_ID}`).click()
    await page.getByRole('dialog').getByRole('button', { name: /確認/ }).click()
    await expect(page.getByText(/送信しました/)).toBeVisible()
    await expect(page.getByTestId('payment-request-status')).toHaveAttribute('data-status', 'SENT')
  })

  test('支払い開始で冪等キーを送りPayment Elementダイアログを開く', async ({ page }) => {
    await setupAuth(page, { userId: 2, displayName: 'チーム管理者', role: 'ADMIN', scopeType: 'TEAM', scopeId: TEAM_ID })
    await mockCommon(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/payment-requests/${REQUEST_ID}`, jsonResponse({ data: draftRequest('SENT') }))
    let idempotencyKey: string | undefined
    await page.route(`**/api/v1/teams/${TEAM_ID}/payment-requests/${REQUEST_ID}/pay`, async (route) => {
      expect(route.request().method()).toBe('POST')
      idempotencyKey = route.request().headers()['idempotency-key']
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { clientSecret: 'pi_e2e_secret' } }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/payment-requests/${REQUEST_ID}`)
    await page.getByTestId('payment-request-pay').click()

    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByTestId('payment-request-status')).toHaveAttribute('data-status', 'PROCESSING')
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i)
  })

  test('チーム側のPROCESSINGを短期pollしてPAIDを表示する', async ({ page }) => {
    await setupAuth(page, { userId: 2, displayName: 'チーム管理者', role: 'ADMIN', scopeType: 'TEAM', scopeId: TEAM_ID })
    await mockCommon(page)

    let detailReads = 0
    await page.route(`**/api/v1/teams/${TEAM_ID}/payment-requests/${REQUEST_ID}`, async (route) => {
      detailReads += 1
      const status = detailReads === 1 ? 'PROCESSING' : 'PAID'
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: processingRequest(status) }) })
    })

    await page.goto(`/teams/${TEAM_ID}/payment-requests/${REQUEST_ID}`)
    await expect(page.getByTestId('payment-request-status')).toHaveAttribute('data-status', 'PROCESSING')
    await expect(page.getByTestId('payment-request-status')).toHaveAttribute('data-status', 'PAID', { timeout: 10_000 })
    expect(detailReads).toBeGreaterThanOrEqual(2)
  })

  test('立替精算を確認してSETTLEDを表示する', async ({ page }) => {
    await setupAuth(page, { userId: 2, displayName: 'チーム管理者', role: 'ADMIN', scopeType: 'TEAM', scopeId: TEAM_ID })
    await mockCommon(page)

    let settled = false
    await page.route(`**/api/v1/teams/${TEAM_ID}/payment-requests`, jsonResponse({ data: [] }))
    await page.route(`**/api/v1/teams/${TEAM_ID}/payment-advances`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [pendingAdvance(settled ? 'SETTLED' : 'PENDING')] }) })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/payment-advances/advance-1/confirm-settlement`, async (route) => {
      expect(route.request().method()).toBe('POST')
      settled = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: pendingAdvance('SETTLED') }) })
    })

    await page.goto(`/teams/${TEAM_ID}/payment-requests`)
    await page.getByTestId('payment-advances-link').click()
    await expect(page).toHaveURL(new RegExp(`/teams/${TEAM_ID}/payments/advances$`))
    await page.getByTestId('confirm-advance').click()
    await page.getByRole('dialog').getByRole('button', { name: /確認/ }).click()
    await expect(page.getByTestId('payment-advance-status')).toHaveAttribute('data-status', 'SETTLED')
    await expect(page.getByText(/精算を確認しました/)).toBeVisible()
  })
})
