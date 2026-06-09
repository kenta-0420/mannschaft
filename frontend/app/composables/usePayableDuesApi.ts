import type {
  PayableDuesResponse,
  BulkCheckoutRequest,
  BulkCheckoutResponse,
} from '~/types/payment'

/**
 * F08.9 P2: 後見まとめ払い API の型付きラッパー。
 *
 * - 支払い可能な会費一覧: GET /api/v1/me/payable-dues
 * - まとめ決済チェックアウト: POST /api/v1/me/payable-dues/bulk-checkout
 *
 * BE は全レスポンスを ApiResponse（{ data: ... }）でラップする。
 */
export function usePayableDuesApi() {
  const api = useApi()

  /**
   * 認証ユーザー（保護者を含む）が支払い可能な会費一覧を取得する。
   * alreadyPaid=true の項目も含まれる（UI 側でグレーアウト表示）。
   */
  async function getPayableDues(): Promise<{ data: PayableDuesResponse }> {
    return api<{ data: PayableDuesResponse }>('/api/v1/me/payable-dues')
  }

  /**
   * 指定した受益者・会費項目をまとめてチェックアウトする。
   * 既払い・認可不足の項目は SKIPPED として結果に含まれる。
   */
  async function bulkCheckout(req: BulkCheckoutRequest): Promise<{ data: BulkCheckoutResponse }> {
    return api<{ data: BulkCheckoutResponse }>('/api/v1/me/payable-dues/bulk-checkout', {
      method: 'POST',
      body: req,
    })
  }

  return { getPayableDues, bulkCheckout }
}
