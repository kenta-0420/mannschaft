import type { components } from '~/types/generated'

// F17.2 Wave3 ⑤相性表示・⑥所属村一覧は生成型を正とする（手書きの嘘型を作らない・
// memory `project_village_fe_be_contract_drift_campaign`）。
type VillageAffinityResponse = components['schemas']['VillageAffinityResponse']
type ProfileVisibilityUpdateRequest = components['schemas']['ProfileVisibilityUpdateRequest']
type ProfileVisibilityResponse = components['schemas']['ProfileVisibilityResponse']
type UserVillageSummaryResponse = components['schemas']['UserVillageSummaryResponse']

/**
 * F17.2 Wave3 村機能 API composable — 加入前相性表示・所属村一覧公開トグル
 *
 * 設計書: docs/features/F17.2_village_events_activation.md §8 / §9
 *
 * Backend Controller:
 * - VillageAffinityController（`GET /villages/{villageId}/affinity/me`）
 * - VillageMembershipController（`PATCH /villages/{villageId}/memberships/me/profile-visibility`）
 * - UserVillageController（`GET /users/{userId}/villages`）
 */
export function useVillageAffinityApi() {
  const api = useApi()

  /**
   * §8.3 加入前相性表示（非メンバーもアクセス可・要ログイン）。
   * UNLISTED 村への非メンバーアクセスは 404（存在秘匿）、未ログインは 401。
   */
  async function getMyAffinity(villageId: string): Promise<VillageAffinityResponse> {
    const res = await api<{ data: VillageAffinityResponse }>(
      `/api/v1/villages/${villageId}/affinity/me`,
    )
    return res.data
  }

  /**
   * §9.2/§9.3 自分のその村所属の「所属村一覧への公開」トグルを切り替える（本人のみ）。
   */
  async function updateMyProfileVisibility(
    villageId: string,
    body: ProfileVisibilityUpdateRequest,
  ): Promise<ProfileVisibilityResponse> {
    const res = await api<{ data: ProfileVisibilityResponse }>(
      `/api/v1/villages/${villageId}/memberships/me/profile-visibility`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  /**
   * §9.3/§9.4 対象村人の所属村一覧を取得する。
   *
   * 閲覧者と対象者が「どこか同じ村」に同居しており、かつ対象者が公開 ON かつ
   * 村が visibility=PUBLIC の村のみ返る。返せる村が 0 件の場合は同居関係の有無を
   * 問わず一律 403（§9.4）。ニックネームは含まれない。
   */
  async function getUserVillages(userId: number): Promise<UserVillageSummaryResponse[]> {
    const res = await api<{ data: UserVillageSummaryResponse[] }>(
      `/api/v1/users/${userId}/villages`,
    )
    return res.data
  }

  return {
    getMyAffinity,
    updateMyProfileVisibility,
    getUserVillages,
  }
}
