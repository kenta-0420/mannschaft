import type {
  AbsenceNoticeRequest,
  AdvanceNoticeResponse,
  LateNoticeRequest,
} from '~/types/care'

interface ApiResponse<T> {
  data: T
}

/**
 * F03.12 §15 事前遅刻・欠席連絡 API クライアント。
 *
 * <p>BE エンドポイント（{@code EventRsvpController} で実装）:</p>
 * <ul>
 *   <li>POST /api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/late-notice</li>
 *   <li>POST /api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/absence-notice</li>
 *   <li>GET  /api/v1/teams/{teamId}/events/{eventId}/advance-notices</li>
 * </ul>
 *
 * <p>送信は {@code rsvp-responses/} 配下、一覧取得は {@code /advance-notices} 直下と
 * パスが分かれている点に注意（BE 実装に合わせる）。</p>
 */
export function useAdvanceNoticeApi() {
  const api = useApi()

  function buildEventBase(teamId: number, eventId: number): string {
    return `/api/v1/teams/${teamId}/events/${eventId}`
  }

  /** 事前遅刻連絡を送信する。 */
  async function submitLateNotice(
    teamId: number,
    eventId: number,
    body: LateNoticeRequest,
  ): Promise<AdvanceNoticeResponse> {
    const res = await api<ApiResponse<AdvanceNoticeResponse>>(
      `${buildEventBase(teamId, eventId)}/rsvp-responses/late-notice`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** 事前欠席連絡を送信する。 */
  async function submitAbsenceNotice(
    teamId: number,
    eventId: number,
    body: AbsenceNoticeRequest,
  ): Promise<AdvanceNoticeResponse> {
    const res = await api<ApiResponse<AdvanceNoticeResponse>>(
      `${buildEventBase(teamId, eventId)}/rsvp-responses/absence-notice`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** 事前通知（遅刻・欠席）一覧を取得する（主催者向け）。 */
  async function getAdvanceNotices(
    teamId: number,
    eventId: number,
  ): Promise<AdvanceNoticeResponse[]> {
    const res = await api<ApiResponse<AdvanceNoticeResponse[]>>(
      `${buildEventBase(teamId, eventId)}/advance-notices`,
    )
    return res.data
  }

  return {
    submitLateNotice,
    submitAbsenceNotice,
    getAdvanceNotices,
  }
}
