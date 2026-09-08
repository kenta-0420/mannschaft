import type {
  CreateSwapRequestRequest,
  ResolveSwapRequestRequest,
  SwapRequestResponse,
} from '~/types/shift'

/** v2.2: 交代リクエストの送信先モード */
export type SwapRecipientMode = 'SPECIFIC' | 'OPEN_CALL'

/**
 * v2.2: 3モード対応の交代リクエスト作成パラメータ。
 *
 * - `SPECIFIC`: targetUserIds に指定したメンバーのみに送信
 * - `OPEN_CALL`: 全メンバーに公開募集（openCall=true に相当）
 * - 省略時: 後方互換モード（既存の createSwapRequest と同動作）
 */
export interface CreateSwapRequestOptions {
  recipientMode?: SwapRecipientMode
  targetUserIds?: number[]
}

export function useShiftSwapApi() {
  const api = useApi()

  /**
   * 指定チームの交代リクエスト一覧を取得する（当該チームの管理者のみ）。
   *
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
    const res = await api<{ data: SwapRequestResponse[] }>(
      `/api/v1/shifts/swap-requests?${params.toString()}`,
    )
    return res.data
  }

  /**
   * シフト交代リクエストを作成する。
   *
   * v2.2 以降は `options.recipientMode` で送信先を指定できる:
   * - `SPECIFIC`: `options.targetUserIds` に指定したメンバーのみに送信
   * - `OPEN_CALL`: 全メンバーに公開募集
   * - 省略時: バックエンドのデフォルト動作（後方互換）
   *
   * @param payload  スロット ID・理由など基本情報（後方互換シグネチャ）
   * @param options  v2.2 拡張オプション（省略可）
   */
  async function createSwapRequest(
    payload: CreateSwapRequestRequest,
    options?: CreateSwapRequestOptions,
  ): Promise<SwapRequestResponse> {
    const body: CreateSwapRequestRequest = { ...payload }
    if (options?.recipientMode === 'OPEN_CALL') {
      body.openCall = true
    }
    else if (options?.recipientMode === 'SPECIFIC' && options.targetUserIds) {
      body.openCall = false
      body.targetUserIds = options.targetUserIds
    }
    const res = await api<{ data: SwapRequestResponse }>('/api/v1/shifts/swap-requests', {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function deleteSwapRequest(swapId: number): Promise<void> {
    await api(`/api/v1/shifts/swap-requests/${swapId}`, { method: 'DELETE' })
  }

  async function acceptSwap(swapId: number): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(
      `/api/v1/shifts/swap-requests/${swapId}/accept`,
      { method: 'POST' },
    )
    return res.data
  }

  async function resolveSwap(
    swapId: number,
    payload: ResolveSwapRequestRequest,
  ): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(
      `/api/v1/shifts/swap-requests/${swapId}/resolve`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  return {
    listSwapRequests,
    createSwapRequest,
    deleteSwapRequest,
    acceptSwap,
    resolveSwap,
  }
}
