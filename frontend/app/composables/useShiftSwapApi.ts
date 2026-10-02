import type {
  CreateSwapRequestRequest,
  ResolveSwapRequestRequest,
  SwapRequestResponse,
} from '~/types/shift'

/**
 * F03.5 シフト交代リクエスト API クライアント（v1 範囲）。
 *
 * v2.1 オープンコール拡張は Phase 3 で追加予定。
 *
 * エンドポイントベース: `/api/v1/shifts/swap-requests`
 */
export function useShiftSwapApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/swap-requests'

  /**
   * 指定チームの交代リクエスト一覧を取得する（当該チームの管理者のみ）。
   * @param teamId 対象チームの<b>数値ID</b>（必須）。バックエンドは `@RequestParam Long teamId` で
   *               受けるため、slug 文字列を渡すとバインドに失敗して 400 になる。
   *               型を `number` に固定して slug の混入をコンパイル時に落とす。
   * @param status ステータスフィルタ（省略可）
   */
  async function listSwapRequests(
    teamId: number,
    status?: string,
  ): Promise<SwapRequestResponse[]> {
    const params = new URLSearchParams({ teamId: String(teamId) })
    if (status) {
      params.set('status', status)
    }
    const res = await api<{ data: SwapRequestResponse[] }>(`${BASE}?${params.toString()}`)
    return res.data
  }

  /**
   * 交代リクエストを作成する。
   * @param payload 作成リクエスト
   */
  async function createSwapRequest(payload: CreateSwapRequestRequest): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(BASE, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  /**
   * 交代リクエストを承諾する（交代相手）。
   * @param swapId 交代リクエスト ID
   */
  async function acceptSwapRequest(swapId: number): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(`${BASE}/${swapId}/accept`, {
      method: 'POST',
    })
    return res.data
  }

  /**
   * 交代リクエストを承認・却下する（管理者）。
   * @param swapId  交代リクエスト ID
   * @param payload 承認/却下リクエスト
   */
  async function resolveSwapRequest(
    swapId: number,
    payload: ResolveSwapRequestRequest,
  ): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(`${BASE}/${swapId}/resolve`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  /**
   * 交代リクエストをキャンセルする。
   * @param swapId 交代リクエスト ID
   */
  async function cancelSwapRequest(swapId: number): Promise<void> {
    await api(`${BASE}/${swapId}`, { method: 'DELETE' })
  }

  return {
    listSwapRequests,
    createSwapRequest,
    acceptSwapRequest,
    resolveSwapRequest,
    cancelSwapRequest,
  }
}
