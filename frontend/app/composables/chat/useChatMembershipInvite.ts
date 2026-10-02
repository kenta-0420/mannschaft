import type { components } from '~/types/generated'

/** 招待発行リクエスト（生成型・F04.12）。 */
export type MembershipInviteRequest = components['schemas']['MembershipInviteRequest']
/** 招待発行レスポンス（生成型・F04.12）。 */
export type MembershipInviteResponse = components['schemas']['MembershipInviteResponse']
/** 招待可能スコープ 1 件（生成型・F04.12）。 */
export type InvitableScope = components['schemas']['InvitableScope']
/** 招待可能スコープ一覧（生成型・F04.12）。 */
export type InvitableScopesResponse = components['schemas']['InvitableScopesResponse']

/**
 * チャットからチーム/組織への承諾型招待 API を提供する composable（F04.12）。
 *
 * 提供する関数:
 * - {@link getInvitableScopes} — 自分が発行できるスコープ一覧（BE が認可の真実源・設計書 B-6）
 * - {@link issueMembershipInvite} — DM 相手をスコープへ招待（宛先付きトークン発行＋カード投稿）
 * - {@link joinInvite} — 招待の承諾（宛先本人のみ）
 * - {@link declineInvite} — 招待の辞退（宛先本人のみ）
 *
 * 型は生成型（types/generated）を優先使用する。
 */
export function useChatMembershipInvite() {
  const api = useApi()

  /**
   * 自分が招待発行できる（ADMIN/DEPUTY_ADMIN の）スコープ一覧を取得する。
   *
   * 管理スコープが 0 件でもエラーにせず空配列を返す（設計書 §4・B-6）。
   */
  async function getInvitableScopes(): Promise<InvitableScopesResponse> {
    const raw = await api<{ data: InvitableScopesResponse }>('/api/v1/me/invitable-scopes')
    return raw.data ?? { teams: [], organizations: [] }
  }

  /**
   * DM 相手を指定スコープへ招待する（宛先付きトークン発行＋招待カード投稿）。
   *
   * @param channelId DM チャンネル ID
   * @param body      招待リクエスト（scopeType / scopeId / roleId? / expiresInDays?）
   */
  async function issueMembershipInvite(
    channelId: number,
    body: MembershipInviteRequest,
  ): Promise<MembershipInviteResponse> {
    const raw = await api<{ data: MembershipInviteResponse }>(
      `/api/v1/chat/channels/${channelId}/membership-invite`,
      { method: 'POST', body },
    )
    return raw.data
  }

  /**
   * 招待を承諾する（宛先本人のみ・PENDING→参加）。
   *
   * BE 既存の共有リンク参加 API を流用。宛先照合は BE 側で行い、不一致は 403。
   */
  async function joinInvite(token: string): Promise<void> {
    await api(`/api/v1/invite/${encodeURIComponent(token)}/join`, {
      method: 'POST',
      body: {},
    })
  }

  /**
   * 招待を辞退する（宛先本人のみ・revoked_at を立てカードを REVOKED 表示）。
   */
  async function declineInvite(token: string): Promise<void> {
    await api(`/api/v1/invite/${encodeURIComponent(token)}/decline`, {
      method: 'POST',
    })
  }

  return {
    getInvitableScopes,
    issueMembershipInvite,
    joinInvite,
    declineInvite,
  }
}
