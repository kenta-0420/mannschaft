import type {
  CancellationPaymentStatus,
  RecruitmentCancellationRecordSlice,
  WaiveCancellationFeeRequest,
} from '~/types/recruitment'

/**
 * F03.11.1 募集キャンセル料の免除 API クライアント（設計書 §12・免除 UI）。
 *
 * 受取先側の管理者・受取先本人・SYSTEM_ADMIN が、自分が受け取るべきキャンセル料の記録を
 * 一覧し、理由を添えて免除するための API を呼ぶ。
 */
export function useRecruitmentCancellationFee() {
  const api = useApi()

  /**
   * キャンセル料記録の一覧を取得する（キーセットページング）。
   *
   * BE 側は母集合が免除で縮むうえ、権威ある受取先（escrow）による絞り込みをアプリ層で行うため、
   * ページ番号送りではなくカーソル方式である。続きは前回の `meta.nextCursor` をそのまま渡す。
   *
   * @param statuses 絞り込む決済ステータス（未指定なら免除可能な既定 3 状態を BE 側が適用）
   * @param cursor   続きの位置（先頭ページは未指定）
   * @param size     1 ページの件数（BE 側は 1〜100 のみ受け付ける）
   */
  async function listCancellationRecords(
    statuses?: CancellationPaymentStatus[],
    cursor?: string | null,
    size = 20,
  ) {
    const params = new URLSearchParams()
    if (statuses && statuses.length > 0) {
      for (const s of statuses) params.append('status', s)
    }
    if (cursor) params.append('cursor', cursor)
    params.append('size', String(size))
    return api<RecruitmentCancellationRecordSlice>(
      `/api/v1/recruitment-cancellation-records?${params.toString()}`,
    )
  }

  /**
   * キャンセル料を免除する（理由必須）。
   */
  async function waiveCancellationFee(recordId: number, body: WaiveCancellationFeeRequest) {
    return api(
      `/api/v1/recruitment-cancellation-records/${recordId}/waive`,
      { method: 'POST', body },
    )
  }

  return {
    listCancellationRecords,
    waiveCancellationFee,
  }
}
