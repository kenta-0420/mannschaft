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

  it('発行はスコープ付き URL へ POST する', async () => {
    await useReceiptApi().issueReceipt('TEAM', 12, { recipientName: '山田' })

    expect(mockApi).toHaveBeenCalledWith('/api/v1/admin/receipts?scopeType=TEAM&scopeId=12', {
      method: 'POST',
      body: { recipientName: '山田' },
    })
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
