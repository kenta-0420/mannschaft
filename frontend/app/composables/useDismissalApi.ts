import type { DismissalRequest, DismissalStatusResponse } from '~/types/care'

interface ApiResponse<T> {
  data: T
}

/**
 * F03.12 §16 イベント解散通知 API クライアント。
 *
 * <p>BE エンドポイント（{@code EventDismissalController} で実装）:</p>
 * <ul>
 *   <li>POST /api/v1/teams/{teamId}/events/{eventId}/dismissal</li>
 *   <li>GET  /api/v1/teams/{teamId}/events/{eventId}/dismissal/status</li>
 * </ul>
 *
 * <p>POST は本文を持たず（{@code ApiResponse.of(null)} を返す）、
 * 最新の状態は status エンドポイントで取り直す運用。
 * 本 composable は呼び出し側の利便性のため status を返り値として受ける形にしている。</p>
 */
export function useDismissalApi() {
  const api = useApi()

  function buildBase(teamId: number, eventId: number): string {
    return `/api/v1/teams/${teamId}/events/${eventId}/dismissal`
  }

  /**
   * 解散通知を送信する。
   *
   * <p>BE は本文 null で 201 Created を返すのみ。
   * 呼び出し側で最新状態が欲しい場合は本関数の後に {@link getDismissalStatus} を続ける。
   * 本関数はその利便性を内部に取り込むため、送信成功後に自動で status を取得して返す。</p>
   */
  async function submitDismissal(
    teamId: number,
    eventId: number,
    body: DismissalRequest,
  ): Promise<DismissalStatusResponse> {
    await api(buildBase(teamId, eventId), { method: 'POST', body })
    return getDismissalStatus(teamId, eventId)
  }

  /** 解散通知の送信状態を取得する。 */
  async function getDismissalStatus(
    teamId: number,
    eventId: number,
  ): Promise<DismissalStatusResponse> {
    const res = await api<ApiResponse<DismissalStatusResponse>>(
      `${buildBase(teamId, eventId)}/status`,
    )
    return res.data
  }

  return {
    submitDismissal,
    getDismissalStatus,
  }
}
