import type { ContactResponse } from '~/types/contact'
import type { MemberResponse } from '~/types/member'

/**
 * 連絡先を、チャットの候補選択が使う MemberResponse 形へ写す。
 * 連絡先に所属ロールは無いので roleName は MEMBER 固定（候補一覧では表示しない）。
 */
export function contactToCandidate(c: ContactResponse): MemberResponse {
  return {
    userId: c.user.id,
    displayName: c.customName || c.user.fullName,
    avatarUrl: c.user.avatarUrl,
    roleName: 'MEMBER',
    joinedAt: c.addedAt,
  }
}

/**
 * チャットで「相手を選ぶ」候補を取得する。
 *
 * - チーム画面: そのチームのメンバー
 * - 組織画面: その組織のメンバー
 * - どちらでもない汎用の /chat 画面: 自分の連絡先
 *   （F04.2 は汎用画面の候補を規定していないため、DM 開始と同じく
 *   「自分が連絡を取れる相手」である連絡先を最小の合理的な範囲とする）
 */
export function useChatCandidateMembers() {
  const teamApi = useTeamApi()
  const orgApi = useOrganizationApi()
  const contactApi = useContactApi()

  async function loadCandidates(scope: {
    teamId?: string
    organizationId?: string
  }): Promise<MemberResponse[]> {
    if (scope.teamId) {
      return (await teamApi.getMembers(scope.teamId, { size: 200 })).data
    }
    if (scope.organizationId) {
      return (await orgApi.getMembers(scope.organizationId, { size: 200 })).data
    }
    // BE の GET /api/v1/contacts はページングなしの ApiResponse<List<ContactResponse>>（meta 無し）。
    const res = await contactApi.listContacts()
    return (res.data ?? []).map(contactToCandidate)
  }

  return { loadCandidates }
}
