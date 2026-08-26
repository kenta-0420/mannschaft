/**
 * 影響を受ける予約1件の表示用データ。
 * バックエンド `EmergencyClosurePreviewResponse.AffectedReservation` と一致させる。
 */
export interface ClosurePreviewItem {
  reservationId: number
  userId: number
  userDisplayName: string
  userEmail: string
  /** "yyyy-MM-dd" */
  slotDate: string
  /** "HH:mm:ss" */
  startTime: string
  /** "HH:mm:ss" */
  endTime: string
  status: string
}

/**
 * プレビュー API のレスポンス本体。
 * バックエンドは ApiResponse&lt;EmergencyClosurePreviewResponse&gt; を返すため
 * `data` には EmergencyClosurePreviewResponse 全体が入る（配列ではない）。
 */
export interface ClosurePreviewResponse {
  data: {
    startDate: string
    endDate: string
    /** 部分時間帯休業の開始時刻（"HH:mm"）。終日休業の場合は null */
    startTime: string | null
    /** 部分時間帯休業の終了時刻（"HH:mm"）。終日休業の場合は null */
    endTime: string | null
    affectedCount: number
    affectedReservations: ClosurePreviewItem[]
  }
}

export interface ClosureSendBody {
  startDate: string
  endDate: string
  /** 部分時間帯休業の開始時刻（"HH:mm"）。終日休業の場合は null */
  startTime: string | null
  /** 部分時間帯休業の終了時刻（"HH:mm"）。終日休業の場合は null */
  endTime: string | null
  reason: string
  subject: string
  messageBody: string
  cancelReservations: boolean
}

export interface ClosureSendResponse {
  data: {
    notifiedCount: number
  }
}

export interface ClosureHistoryItem {
  id: number
  /**
   * 数値 teamId（BE {@code EmergencyClosureResponse.teamId}）。
   * STOMP 確認状況トピック（数値必須）の購読に使う。HTTP パスは slug/数値どちらでも可だが、
   * トピックは数値のみのため、履歴レスポンスが返すこの数値 id を採用するのが最小侵襲。
   */
  teamId: number
  startDate: string
  endDate: string
  /** 部分時間帯休業の開始時刻（"HH:mm"）。終日休業の場合は null */
  startTime: string | null
  /** 部分時間帯休業の終了時刻（"HH:mm"）。終日休業の場合は null */
  endTime: string | null
  reason: string
  subject: string
  notifiedCount: number
  createdAt: string
}

export interface ClosureHistoryResponse {
  data: ClosureHistoryItem[]
}

export interface ClosureConfirmationItem {
  userId: number
  userDisplayName: string
  userEmail: string
  appointmentAt: string
  confirmed: boolean
  confirmedAt: string | null
  reminderSent: boolean
}

export interface ClosureConfirmationsResponse {
  data: ClosureConfirmationItem[]
}

/** `/api/v1/me/teams` の所属チーム 1 件（slug → 数値 teamId 解決に必要な最小フィールド）。 */
interface MyTeamItem {
  id: number
  slug: string
}

export function useEmergencyClosureApi() {
  const api = useApi()

  function base(teamId: string) {
    return `/api/v1/teams/${teamId}`
  }

  /**
   * チーム識別子（slug or 数値文字列）から **数値 teamId** を解決する。
   *
   * <p>STOMP 確認状況トピック {@code /topic/teams/{teamId}/...} は数値 teamId を要求するが、画面は slug を
   * 持つ。BE 正準の {@code GET /api/v1/me/teams}（{@code MyTeamResponse.id} が数値・{@code slug} 付き）を
   * 引いて当該チームの数値 id を得る（match の {@code useMatchOrgContext} と同じ入手源だが、本機能は親組織
   * コンテキストを要さないため org 解決は行わない）。</p>
   *
   * <p>数値文字列が渡された場合はそのまま {@code Number()} で数値化して返す（API 往復を省く）。
   * 解決不能（未所属・不通）の場合は null を返す（呼び出し側で null ガード・症状は隠さない）。</p>
   */
  async function resolveTeamId(teamRef: string): Promise<number | null> {
    const numeric = Number(teamRef)
    if (Number.isInteger(numeric) && numeric > 0 && String(numeric) === teamRef) {
      return numeric
    }
    try {
      const res = await api<{ data: MyTeamItem[] }>('/api/v1/me/teams')
      const myTeam = (res.data ?? []).find((tm) => tm.slug === teamRef)
      return myTeam ? myTeam.id : null
    } catch {
      // 安全系（休講確認）ゆえ取得失敗を沈黙させない。不通・未所属いずれも null に潰れる点をログで表面化する。
      console.warn('[useEmergencyClosureApi] チームID解決に失敗しました', { teamRef })
      return null
    }
  }

  async function previewClosure(
    teamId: string,
    startDate: string,
    endDate: string,
    startTime: string | null = null,
    endTime: string | null = null,
  ) {
    const query = new URLSearchParams()
    query.set('startDate', startDate)
    query.set('endDate', endDate)
    if (startTime && endTime) {
      query.set('startTime', startTime)
      query.set('endTime', endTime)
    }
    return api<ClosurePreviewResponse>(`${base(teamId)}/emergency-closures/preview?${query}`)
  }

  async function sendClosure(teamId: string, body: ClosureSendBody) {
    return api<ClosureSendResponse>(`${base(teamId)}/emergency-closures`, {
      method: 'POST',
      body,
    })
  }

  async function listClosures(teamId: string) {
    return api<ClosureHistoryResponse>(`${base(teamId)}/emergency-closures`)
  }

  async function confirmClosure(teamId: string, closureId: number) {
    return api<undefined>(`${base(teamId)}/emergency-closures/${closureId}/confirm`, {
      method: 'POST',
    })
  }

  async function getConfirmations(teamId: string, closureId: number) {
    return api<ClosureConfirmationsResponse>(
      `${base(teamId)}/emergency-closures/${closureId}/confirmations`,
    )
  }

  return { previewClosure, sendClosure, listClosures, confirmClosure, getConfirmations, resolveTeamId }
}
