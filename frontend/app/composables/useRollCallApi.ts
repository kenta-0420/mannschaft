import type {
  RollCallCandidate,
  RollCallEntryPatchRequest,
  RollCallSessionRequest,
  RollCallSessionResponse,
} from '~/types/care'

interface ApiResponse<T> {
  data: T
}

/**
 * F03.12 §14 主催者点呼 API クライアント。
 *
 * <p>BE エンドポイント:</p>
 * <ul>
 *   <li>GET    /api/v1/teams/{teamId}/events/{eventId}/roll-call/candidates</li>
 *   <li>POST   /api/v1/teams/{teamId}/events/{eventId}/roll-call</li>
 *   <li>GET    /api/v1/teams/{teamId}/events/{eventId}/roll-call/sessions</li>
 *   <li>PATCH  /api/v1/teams/{teamId}/events/{eventId}/roll-call/{userId}</li>
 * </ul>
 *
 * <p>BE は controller {@code EventRollCallController} で実装。
 * PATCH のパスは設計書の {@code /entries/{checkinId}} ではなく実装どおりの {@code /{userId}} を採用する。</p>
 */
export function useRollCallApi() {
  const api = useApi()

  function buildBase(teamId: number, eventId: number): string {
    return `/api/v1/teams/${teamId}/events/${eventId}/roll-call`
  }

  /** 点呼候補者（RSVP=ATTENDING/MAYBE）一覧を取得する。 */
  async function getCandidates(teamId: number, eventId: number): Promise<RollCallCandidate[]> {
    const res = await api<ApiResponse<RollCallCandidate[]>>(
      `${buildBase(teamId, eventId)}/candidates`,
    )
    return res.data
  }

  /**
   * 点呼セッションを一括登録する。
   *
   * @param body 冪等キー {@code rollCallSessionId} 付きのセッションリクエスト
   */
  async function submitRollCall(
    teamId: number,
    eventId: number,
    body: RollCallSessionRequest,
  ): Promise<RollCallSessionResponse> {
    const res = await api<ApiResponse<RollCallSessionResponse>>(buildBase(teamId, eventId), {
      method: 'POST',
      body,
    })
    return res.data
  }

  /** 過去の点呼セッションIDリストを取得する（履歴）。 */
  async function getSessions(teamId: number, eventId: number): Promise<string[]> {
    const res = await api<ApiResponse<string[]>>(`${buildBase(teamId, eventId)}/sessions`)
    return res.data
  }

  /**
   * 点呼結果を個別修正する。
   *
   * @param userId 修正対象ユーザーID
   * @param body   修正内容（status / lateArrivalMinutes / absenceReason）
   */
  async function patchEntry(
    teamId: number,
    eventId: number,
    userId: number,
    body: RollCallEntryPatchRequest,
  ): Promise<void> {
    await api(`${buildBase(teamId, eventId)}/${userId}`, {
      method: 'PATCH',
      body,
    })
  }

  return {
    getCandidates,
    submitRollCall,
    getSessions,
    patchEntry,
  }
}
