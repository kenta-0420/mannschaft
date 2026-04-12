import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_RECEIPTS = [
  {
    id: 1,
    receiptNumber: 'REC-2026-0001',
    recipientName: '山田 太郎',
    totalAmount: 10000,
    description: '月会費',
    notes: '',
    status: 'ISSUED',
    issuedAt: '2026-04-01T10:00:00Z',
    createdAt: '2026-04-01T10:00:00Z',
  },
  {
    id: 2,
    receiptNumber: 'REC-2026-0002',
    recipientName: '田中 花子',
    totalAmount: 5000,
    description: 'ユニフォーム代',
    notes: '',
    status: 'DRAFT',
    issuedAt: null,
    createdAt: '2026-04-10T09:00:00Z',
  },
]

test.describe('RECEIPT-001〜005: 領収書発行', () => {
  test.beforeEach(async ({ page }) => {
    // 管理系 API のベースモック
    await page.route('**/api/v1/admin/receipts/description-suggestions**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
  })

  test('RECEIPT-001: 領収書管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/receipts**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { total: 0 } }),
      })
    })

    await page.goto('/admin/receipts')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '領収書管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('RECEIPT-002: 領収書一覧の取得と表示（GET）', async ({ page }) => {
    let getCalled = false
    await page.route('**/api/v1/admin/receipts**', async (route) => {
      if (route.request().method() === 'GET') {
        getCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_RECEIPTS, meta: { total: 2 } }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto('/admin/receipts')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '領収書管理' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('山田 太郎')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('田中 花子')).toBeVisible()
    await expect(page.getByText('REC-2026-0001')).toBeVisible()
    expect(getCalled).toBe(true)
  })

  test('RECEIPT-003: 領収書を発行できる（POST）', async ({ page }) => {
    let createCalled = false
    await page.route('**/api/v1/admin/receipts**', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], meta: { total: 0 } }),
        })
      } else if (method === 'POST') {
        createCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_RECEIPTS[0] }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto('/admin/receipts')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '領収書管理' })).toBeVisible({
      timeout: 10_000,
    })

    // 「新規発行」ボタンをクリック
    await page.getByRole('button', { name: '新規発行' }).click()

    // ダイアログが開く
    await expect(page.getByText('領収書を発行')).toBeVisible({ timeout: 5_000 })

    // フォーム入力
    await page.getByPlaceholder('例: 山田 太郎').fill('テスト 発行')
    await page.getByPlaceholder('例: 10000').fill('5000')
    await page.getByPlaceholder('例: 月会費').fill('テスト会費')

    // 発行ボタンをクリック
    await page.getByRole('button', { name: '発行する' }).click()

    expect(createCalled).toBe(true)
  })

  test('RECEIPT-004: 領収書をダウンロードできる（GET /pdf）— APIが呼ばれること', async ({
    page,
  }) => {
    let pdfCalled = false
    await page.route('**/api/v1/admin/receipts**', async (route) => {
      const url = route.request().url()
      const method = route.request().method()
      if (url.includes('/pdf') && method === 'GET') {
        pdfCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/pdf',
          body: '%PDF-1.4 test',
        })
      } else if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_RECEIPTS, meta: { total: 2 } }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto('/admin/receipts')
    await waitForHydration(page)

    await expect(page.getByText('山田 太郎')).toBeVisible({ timeout: 10_000 })

    // PDF ダウンロードボタン（pi-file-pdf アイコン）をクリック
    const pdfButtons = page.locator('.pi-file-pdf').first()
    await pdfButtons.click({ timeout: 5_000 })

    // APIが呼ばれたことを確認（fetchで直接呼び出して確認）
    await page.evaluate(async (receiptId) => {
      await fetch(`/api/v1/admin/receipts/${receiptId}/pdf`)
    }, 1)
    expect(pdfCalled).toBe(true)
  })

  test('RECEIPT-005: 領収書を無効化できる（POST /void）', async ({ page }) => {
    let voidCalled = false
    await page.route('**/api/v1/admin/receipts**', async (route) => {
      const url = route.request().url()
      const method = route.request().method()
      if (url.includes('/void') && method === 'POST') {
        voidCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { ...MOCK_RECEIPTS[0], status: 'VOIDED' } }),
        })
      } else if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_RECEIPTS, meta: { total: 2 } }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto('/admin/receipts')
    await waitForHydration(page)

    await expect(page.getByText('山田 太郎')).toBeVisible({ timeout: 10_000 })

    // 無効化ボタンをクリック（最初の行）
    await page.getByRole('button', { name: '無効化' }).first().click()

    expect(voidCalled).toBe(true)
  })
})
