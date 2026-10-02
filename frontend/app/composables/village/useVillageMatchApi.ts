import type {
  VillageMatchApplicationCreateRequest,
  VillageMatchApplicationResponse,
  VillageMatchApplicationReviewRequest,
  VillageMatchRecruitCreateRequest,
  VillageMatchRecruitListParams,
  VillageMatchRecruitListResponse,
  VillageMatchRecruitResponse,
  VillageMatchRecruitUpdateRequest,
} from '~/types/village'

/**
 * F17.1 村機能 API composable — 練習試合募集・応募
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/*.java
 * 設計書: docs/features/F17.1_village_community.md §4 Phase 2
 */
export function useVillageMatchApi() {
  const api = useApi()

  // クエリ文字列ヘルパー
  function qs(params?: object | null): string {
    if (!params) return ''
    const u = new URLSearchParams()
    for (const [k, v] of Object.entries(params as Record<string, unknown>)) {
      if (v !== undefined && v !== null && v !== '') u.set(k, String(v))
    }
    const s = u.toString()
    return s ? `?${s}` : ''
  }

  // =====================================================================
  // Phase 2: 練習試合募集 (VillageMatchRecruitController)
  // /api/v1/villages/{villageId}/match-recruits
  // =====================================================================

  /**
   * 練習試合募集一覧
   *
   * BE は配列ではなく `{items, page, size, total}` のエンベロープを返す
   * （`MatchRecruitListResponse`。Spring の `Page` 形状ではない独自形状）。
   */
  async function listMatchRecruits(
    villageId: string,
    params?: VillageMatchRecruitListParams,
  ) {
    const res = await api<{ data: VillageMatchRecruitListResponse }>(
      `/api/v1/villages/${villageId}/match-recruits${qs(params)}`,
    )
    return res.data
  }

  /** 練習試合募集詳細 */
  async function getMatchRecruit(villageId: string, id: string) {
    const res = await api<{ data: VillageMatchRecruitResponse }>(
      `/api/v1/villages/${villageId}/match-recruits/${id}`,
    )
    return res.data
  }

  /** 練習試合募集作成 */
  async function createMatchRecruit(
    villageId: string,
    body: VillageMatchRecruitCreateRequest,
  ) {
    const res = await api<{ data: VillageMatchRecruitResponse }>(
      `/api/v1/villages/${villageId}/match-recruits`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** 練習試合募集更新 */
  async function updateMatchRecruit(
    villageId: string,
    id: string,
    body: VillageMatchRecruitUpdateRequest,
  ) {
    const res = await api<{ data: VillageMatchRecruitResponse }>(
      `/api/v1/villages/${villageId}/match-recruits/${id}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  /** 練習試合募集を締切（CLOSED 化） */
  async function closeMatchRecruit(villageId: string, id: string) {
    const res = await api<{ data: VillageMatchRecruitResponse }>(
      `/api/v1/villages/${villageId}/match-recruits/${id}/close`,
      { method: 'POST' },
    )
    return res.data
  }

  // =====================================================================
  // Phase 2: 練習試合応募 (VillageMatchApplicationController)
  // /api/v1/villages/{villageId}/match-recruits/{recruitId}/applications
  // =====================================================================

  /** 応募する */
  async function applyToMatchRecruit(
    villageId: string,
    recruitId: string,
    body: VillageMatchApplicationCreateRequest,
  ) {
    const res = await api<{ data: VillageMatchApplicationResponse }>(
      `/api/v1/villages/${villageId}/match-recruits/${recruitId}/applications`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** 応募一覧（募集主向け） */
  async function listApplications(villageId: string, recruitId: string) {
    const res = await api<{ data: VillageMatchApplicationResponse[] }>(
      `/api/v1/villages/${villageId}/match-recruits/${recruitId}/applications`,
    )
    return res.data
  }

  /**
   * 応募を審査（承認 / 却下）
   *
   * BE は `/accept` `/reject` のような動詞パスではなく、単一の `/review` に
   * `{status: 'ACCEPTED' | 'REJECTED', reviewComment?}` を渡す契約
   * （`VillageMatchRecruitController#review` / `MatchApplicationReviewRequest`）。
   */
  async function reviewApplication(
    villageId: string,
    recruitId: string,
    applicationId: string,
    body: VillageMatchApplicationReviewRequest,
  ) {
    const res = await api<{ data: VillageMatchApplicationResponse }>(
      `/api/v1/villages/${villageId}/match-recruits/${recruitId}/applications/${applicationId}/review`,
      { method: 'POST', body },
    )
    return res.data
  }

  /** 応募を取り下げる */
  async function withdrawApplication(
    villageId: string,
    recruitId: string,
    applicationId: string,
  ) {
    const res = await api<{ data: VillageMatchApplicationResponse }>(
      `/api/v1/villages/${villageId}/match-recruits/${recruitId}/applications/${applicationId}/withdraw`,
      { method: 'POST' },
    )
    return res.data
  }

  return {
    // Phase 2: 練習試合募集
    listMatchRecruits,
    getMatchRecruit,
    createMatchRecruit,
    updateMatchRecruit,
    closeMatchRecruit,
    // Phase 2: 練習試合応募
    applyToMatchRecruit,
    listApplications,
    reviewApplication,
    withdrawApplication,
  }
}
