/**
 * F02.5 行動メモ — Teams / Orgs / Members ドメイン。
 *
 * <p>Phase 3 リファクタにて {@code useActionMemoApi.ts} から分離。
 * fetchAvailableTeams / fetchAvailableOrgs / fetchTeamMembers /
 * fetchMemberMemos の 4 関数を提供する。</p>
 */
import type {
  ActionMemoListResponse,
  AvailableOrg,
  AvailableTeam,
} from '~/types/actionMemo'
import {
  ACTION_MEMO_BASE,
  normalizeAvailableTeam,
  normalizeMemo,
  rethrow,
  type RawAvailableTeam,
  type RawListResponse,
} from './shared/normalize'

export function useActionMemoTeams() {
  const api = useApi()
  const BASE = ACTION_MEMO_BASE

  // === Available Teams (Phase 3) ===

  /**
   * チーム投稿先候補一覧を取得する。
   * {@code GET /api/v1/action-memos/available-teams}
   */
  async function fetchAvailableTeams(): Promise<AvailableTeam[]> {
    try {
      const res = await api<{ data: RawAvailableTeam[] }>(`${BASE}/available-teams`)
      return (res.data ?? []).map(normalizeAvailableTeam)
    } catch (error) {
      rethrow(error)
    }
  }

  // === Available Orgs (Phase 5-2) ===

  /**
   * 組織スコープ投稿先候補一覧を取得する。
   * {@code GET /api/v1/action-memos/available-orgs}
   */
  async function fetchAvailableOrgs(): Promise<AvailableOrg[]> {
    try {
      const res = await api<{ data: { id: number; name: string }[] }>(`${BASE}/available-orgs`)
      return (res.data ?? []).map((o) => ({ id: o.id, name: o.name }))
    } catch (error) {
      rethrow(error)
    }
  }

  // === Team members (Phase 6-1 / Phase 7) ===

  /**
   * チームメンバー一覧を全ページ取得する（Phase 7: 200名超チームへの対応）。
   *
   * <p>size=500 でページングしながら totalPages に達するまで繰り返し取得することで
   * 大規模チームでも全メンバーを取りこぼしなく返す。</p>
   */
  async function fetchTeamMembers(teamId: number): Promise<{ userId: number; displayName: string; avatarUrl: string | null }[]> {
    type Member = { userId: number; displayName: string; avatarUrl: string | null }
    type PagedRes = { data: Member[]; meta: { totalPages: number } }

    const all: Member[] = []
    let page = 0
    let totalPages = 1

    try {
      while (page < totalPages) {
        const res = await api<PagedRes>(`/api/v1/teams/${teamId}/members?size=500&page=${page}`)
        all.push(...(res.data ?? []))
        totalPages = res.meta?.totalPages ?? 1
        page++
      }
      return all
    } catch (error) {
      rethrow(error)
    }
  }

  // === Phase 4-β: 管理職ダッシュボード ===

  async function fetchMemberMemos(
    teamId: number,
    memberId: number,
    params: { cursor?: string; limit?: number } = {},
  ): Promise<ActionMemoListResponse> {
    const query = new URLSearchParams()
    if (params.cursor) query.set('cursor', params.cursor)
    if (params.limit !== undefined) query.set('limit', String(params.limit))
    try {
      const res = await api<RawListResponse>(
        `/api/v1/teams/${teamId}/members/${memberId}/action-memos?${query.toString()}`,
      )
      return {
        data: (res.data ?? []).map(normalizeMemo),
        nextCursor: res.next_cursor ?? null,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  return {
    fetchAvailableTeams,
    fetchAvailableOrgs,
    fetchTeamMembers,
    fetchMemberMemos,
  }
}
