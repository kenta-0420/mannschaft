import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

const { useReceiptApi } = await import('./useReceiptApi')

describe('useReceiptApi（admin 系はスコープ必須）', () => {
  beforeEach(() => {
    mockApi.mockReset()
  })

  it('一覧は scopeType/scopeId と 0 起点の page・size を送る', async () => {
    await useReceiptApi().getReceipts('TEAM', 12, { page: 0, size: 20 })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/admin/receipts?scopeType=TEAM&scopeId=12&page=0&size=20',
    )
  })

  it('明細取得は scopeType/scopeId をクエリに載せる', async () => {
    await useReceiptApi().getReceipt('ORGANIZATION', '7', 55)

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/admin/receipts/55?scopeType=ORGANIZATION&scopeId=7',
    )
  })

  // BE `CreateReceiptRequest` は金額を `amount` で受ける（`totalAmount` ではない）。
  // 名前違いは 400 にならず金額 null で 500 まで進んでいた（CMP-260907-0915）。
  it('発行はスコープ付き URL へ POST し、金額を amount という名前で送る', async () => {
    await useReceiptApi().issueReceipt('TEAM', 12, { recipientName: '山田', amount: 10000 })

    expect(mockApi).toHaveBeenCalledWith('/api/v1/admin/receipts?scopeType=TEAM&scopeId=12', {
      method: 'POST',
      body: { recipientName: '山田', amount: 10000 },
    })
    const body = mockApi.mock.calls[0]?.[1]?.body as Record<string, unknown>
    expect(body).not.toHaveProperty('totalAmount')
  })

  it('マイページ一覧は PagedResponse をそのまま返す（管理者向けとは別形）', async () => {
    mockApi.mockResolvedValueOnce({
      data: [{ id: 1, amount: 3000 }],
      meta: { total: 1, page: 0, size: 20, totalPages: 1 },
    })

    const res = await useReceiptApi().getMyReceipts({ page: 0, size: 20 })

    expect(mockApi).toHaveBeenCalledWith('/api/v1/my/receipts?page=0&size=20')
    expect(res.data[0]?.amount).toBe(3000)
  })

  it('承認は PATCH でスコープを送る', async () => {
    await useReceiptApi().approveReceipt('TEAM', 12, 3)

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/admin/receipts/3/approve?scopeType=TEAM&scopeId=12',
      { method: 'PATCH' },
    )
  })

  it('無効化は reason 本文とスコープを送る', async () => {
    await useReceiptApi().voidReceipt('TEAM', 12, 3, { reason: '金額誤り' })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/admin/receipts/3/void?scopeType=TEAM&scopeId=12',
      { method: 'POST', body: { reason: '金額誤り' } },
    )
  })

  it('PDF 取得もスコープ必須（未指定の kind はクエリに出さない）', async () => {
    await useReceiptApi().downloadPdf('TEAM', 12, 9)

    expect(mockApi).toHaveBeenCalledWith('/api/v1/admin/receipts/9/pdf?scopeType=TEAM&scopeId=12')
  })

  it('発行者設定は従来どおりスコープを送る', async () => {
    await useReceiptApi().getSettings('ORGANIZATION', 4)

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/admin/receipt-settings?scopeType=ORGANIZATION&scopeId=4',
    )
  })
})
