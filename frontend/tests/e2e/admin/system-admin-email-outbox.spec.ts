import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { springPage } from '../../fixtures/spring-page'

/** APIモックのブラウザ回帰。実機の認可・実データ検証とは別に扱う。 */
const rows = [
  { id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', status: 'PENDING', templateKind: 'VERIFICATION', sourceDomain: 'auth' },
  { id: 'b2c3d4e5-f6a7-8901-bcde-f12345678901', status: 'DEAD_LETTER', templateKind: 'PASSWORD_RESET', sourceDomain: 'auth' },
  { id: 'c3d4e5f6-a7b8-9012-cdef-123456789012', status: 'SENT', templateKind: 'ANALYTICS_KPI_MONTHLY', sourceDomain: 'analytics' },
].map(row => ({ ...row, sourceEventId: null, locale: 'ja', retryCount: 0, createdAt: '2026-05-21T10:00:00', nextAttemptAt: null, sentAt: null, lastError: null }))
const metrics = { data: { queueDepthPending: 42, queueDepthSending: 3, queueDepthDeadLetter: 5, queueDepthFailed: 1, queueDepthCancelled: 10, successRate24h: 0.987, oldestPendingAgeSeconds: 125 } }
const detail = { data: { ...rows[0], toAddress: 'test@example.com', payloadVars: { verificationUrl: 'https://example.com/verify' }, sesMessageId: null, bodyPurgedAt: null } }

async function listMocks(page: Page) {
  // 全サブパスを飲み込まず、一覧だけをWで返す。後登録優先の競合を避ける。
  await page.route(/\/api\/v1\/system-admin\/email-outbox(?:\?.*)?$/, async route => {
    const status = new URL(route.request().url()).searchParams.get('status')
    const content = status ? rows.filter(row => row.status === status) : rows
    await route.fulfill({ json: { data: springPage(content) } })
  })
  await page.route('**/api/v1/system-admin/email-outbox/metrics', route => route.fulfill({ json: metrics }))
}
async function openList(page: Page) {
  await page.goto('/system-admin/email-outbox')
  await waitForHydration(page)
  await expect(page.getByText('VERIFICATION', { exact: true })).toBeVisible()
}

test('MAIL-001/002: 本物のW形式で一覧3行・総件数・KPIを表示する', async ({ page }) => {
  await listMocks(page)
  await openList(page)
  await expect(page.locator('tbody tr')).toHaveCount(3)
  await expect(page.getByText('3 件中', { exact: false })).toBeVisible()
  await expect(page.getByText('42', { exact: true })).toBeVisible()
  await expect(page.getByText('98.7%', { exact: true })).toBeVisible()
})

test('MAIL-003: ステータスfilterをHTTPへ適用して行を絞る', async ({ page }) => {
  await listMocks(page)
  await openList(page)
  await page.locator('[data-pc-name="select"]').first().click()
  await page.getByRole('option', { name: 'デッドレター' }).click()
  const request = page.waitForRequest(req => req.url().includes('/email-outbox?') && req.url().includes('status=DEAD_LETTER'))
  await page.getByRole('button', { name: '適用', exact: true }).click()
  expect(new URL((await request).url()).searchParams.get('page')).toBe('0')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await expect(page.getByText('PASSWORD_RESET', { exact: true })).toBeVisible()
})

for (const action of ['retry', 'cancel'] as const) {
  for (const status of [204, 409]) {
    test(`MAIL-005/006/007: ${action} ${status}を固定assertする`, async ({ page }) => {
      await listMocks(page)
      const row = rows[action === 'retry' ? 1 : 0]!
      let listCount = 0
      page.on('request', req => { if (/\/email-outbox(?:\?.*)?$/.test(req.url())) listCount++ })
      await page.route(`**/api/v1/system-admin/email-outbox/${row.id}/${action}`, route => route.fulfill({ status, ...(status === 409 ? { json: { error: 'CONFLICT', message: '状態競合' } } : {}) }))
      await openList(page)
      const request = page.waitForRequest(req => req.method() === 'POST' && req.url().endsWith(`/${row.id}/${action}`))
      await page.getByRole('button', { name: action === 'retry' ? '再試行' : 'キャンセル', exact: true }).click()
      await request
      if (status === 204) {
        await expect.poll(() => listCount).toBe(2)
        await expect(page.locator('tbody tr')).toHaveCount(3)
      } else {
        await expect(page.getByText(action === 'retry' ? 'この状態では再試行できません' : 'この状態ではキャンセルできません', { exact: true })).toBeVisible()
        expect(listCount).toBe(1)
      }
    })
  }
}

test('MAIL-004: 詳細の宛先とpayload変数を表示する', async ({ page }) => {
  await page.route(`**/api/v1/system-admin/email-outbox/${rows[0]!.id}`, route => route.fulfill({ json: detail }))
  await page.goto(`/system-admin/email-outbox/${rows[0]!.id}`)
  await waitForHydration(page)
  await expect(page.getByText('test@example.com', { exact: true })).toBeVisible()
  await expect(page.getByText('verificationUrl', { exact: true })).toBeVisible()
})

test('MAIL-008: 詳細404を空本文の成功で済ませずエラー文言を表示する', async ({ page }) => {
  await page.route('**/api/v1/system-admin/email-outbox/non-existent-id', route => route.fulfill({ status: 404, json: { error: 'NOT_FOUND', message: 'not found' } }))
  await page.goto('/system-admin/email-outbox/non-existent-id')
  await waitForHydration(page)
  await expect(page.getByText('データがありません', { exact: true })).toBeVisible()
})
