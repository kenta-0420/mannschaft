import type {
  MemberWorkConstraintRequest,
  MemberWorkConstraintResponse,
} from '~/types/shift'

/**
 * F03.5 メンバー勤務制約 API クライアント（v2 新規）。
 *
 * エンドポイントベース: `/api/v1/shifts/teams/{teamId}/work-constraints`
 *
 * 権限:
 * - 一覧・デフォルト取得: ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）
 * - 個別取得: 本人 + ADMIN / DEPUTY_ADMIN
 * - upsert / delete: ADMIN / DEPUTY_ADMIN のみ
 */
export function useMemberWorkConstraintApi() {
  const api = useApi()

  function basePath(teamId: number) {
    return `/api/v1/shifts/teams/${teamId}/work-constraints`
  }

  // =====================================================
  // チーム全体（一覧）
  // =====================================================

  /**
   * チーム内の全勤務制約（デフォルト + 個別）を取得する（ADMIN/DEPUTY_ADMIN のみ）。
   * @param teamId チーム ID
   */
  async function listConstraints(teamId: number): Promise<MemberWorkConstraintResponse[]> {
    const res = await api<{ data: MemberWorkConstraintResponse[] }>(basePath(teamId))
    return res.data
  }

  // =====================================================
  // メンバー個別制約
  // =====================================================

  /**
   * メンバー個別の勤務制約を取得する（個別 → デフォルトの解決順序）。
   * @param teamId チーム ID
   * @param userId 対象ユーザー ID
   */
  async function getConstraint(
    teamId: number,
    userId: number,
  ): Promise<MemberWorkConstraintResponse> {
    const res = await api<{ data: MemberWorkConstraintResponse }>(
      `${basePath(teamId)}/members/${userId}`,
    )
    return res.data
  }

  /**
   * メンバー個別の勤務制約を upsert する（ADMIN/DEPUTY_ADMIN のみ）。
   * @param teamId  チーム ID
   * @param userId  対象ユーザー ID
   * @param payload 制約リクエスト（全項目 null は 400）
   */
  async function upsertConstraint(
    teamId: number,
    userId: number,
    payload: MemberWorkConstraintRequest,
  ): Promise<MemberWorkConstraintResponse> {
    const res = await api<{ data: MemberWorkConstraintResponse }>(
      `${basePath(teamId)}/members/${userId}`,
      { method: 'PUT', body: payload },
    )
    return res.data
  }

  /**
   * メンバー個別の勤務制約を削除する（チームデフォルトにフォールバック）。
   * @param teamId チーム ID
   * @param userId 対象ユーザー ID
   */
  async function deleteConstraint(teamId: number, userId: number): Promise<void> {
    await api(`${basePath(teamId)}/members/${userId}`, { method: 'DELETE' })
  }

  // =====================================================
  // チームデフォルト
  // =====================================================

  /**
   * チームデフォルト勤務制約を取得する（チームメンバー全員が閲覧可）。
   * @param teamId チーム ID
   */
  async function getTeamDefault(teamId: number): Promise<MemberWorkConstraintResponse> {
    const res = await api<{ data: MemberWorkConstraintResponse }>(
      `${basePath(teamId)}/default`,
    )
    return res.data
  }

  /**
   * チームデフォルト勤務制約を upsert する（ADMIN/DEPUTY_ADMIN のみ）。
   * @param teamId  チーム ID
   * @param payload 制約リクエスト（全項目 null は 400）
   */
  async function upsertTeamDefault(
    teamId: number,
    payload: MemberWorkConstraintRequest,
  ): Promise<MemberWorkConstraintResponse> {
    const res = await api<{ data: MemberWorkConstraintResponse }>(
      `${basePath(teamId)}/default`,
      { method: 'PUT', body: payload },
    )
    return res.data
  }

  /**
   * チームデフォルト勤務制約を削除する（ADMIN/DEPUTY_ADMIN のみ）。
   * @param teamId チーム ID
   */
  async function deleteTeamDefault(teamId: number): Promise<void> {
    await api(`${basePath(teamId)}/default`, { method: 'DELETE' })
  }

  return {
    listConstraints,
    getConstraint,
    upsertConstraint,
    deleteConstraint,
    getTeamDefault,
    upsertTeamDefault,
    deleteTeamDefault,
  }
}
