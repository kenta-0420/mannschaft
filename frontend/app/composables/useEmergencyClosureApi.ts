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

export function useEmergencyClosureApi() {
  const api = useApi()

  function base(teamId: number) {
    return `/api/v1/teams/${teamId}`
  }

  async function previewClosure(
    teamId: number,
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

  async function sendClosure(teamId: number, body: ClosureSendBody) {
    return api<ClosureSendResponse>(`${base(teamId)}/emergency-closures`, {
      method: 'POST',
      body,
    })
  }

  async function listClosures(teamId: number) {
    return api<ClosureHistoryResponse>(`${base(teamId)}/emergency-closures`)
  }

  async function confirmClosure(teamId: number, closureId: number) {
    return api<undefined>(`${base(teamId)}/emergency-closures/${closureId}/confirm`, {
      method: 'POST',
    })
  }

  async function getConfirmations(teamId: number, closureId: number) {
    return api<ClosureConfirmationsResponse>(
      `${base(teamId)}/emergency-closures/${closureId}/confirmations`,
    )
  }

  return { previewClosure, sendClosure, listClosures, confirmClosure, getConfirmations }
}
