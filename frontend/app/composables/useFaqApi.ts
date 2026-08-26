import type {
  FaqEditorResponse,
  PublicFaqItem,
  SaveFaqRequest,
} from '~/types/faq'

/**
 * F21.1 §5.5「FAQ駆動GEO」用 API クライアント composable。
 *
 * 管理 API（ADMIN / SYSTEM_ADMIN 限定。認可は BE 側で 401/403 を返す）と、
 * 認証不要の公開 GET API の両方を提供する。
 *
 * - 管理 GET: `GET /api/v1/admin/{teams|organizations}/{id}/faqs` → {@link FaqEditorResponse}
 * - 管理 PUT: `PUT /api/v1/admin/{teams|organizations}/{id}/faqs`（成功 204）
 * - 公開 GET: `GET /api/v1/public/{teams|organizations}/{id}/faqs` → {@link PublicFaqItem}[]
 *
 * 設計書: docs/features/F21.1_geo_optimization.md §5.5.6
 */
export function useFaqApi() {
  const api = useApi()

  /** チームの FAQ 編集ペイロードを取得する（ADMIN / SYSTEM_ADMIN 限定）。 */
  async function fetchTeamFaqEditor(teamId: string): Promise<FaqEditorResponse> {
    return api<FaqEditorResponse>(`/api/v1/admin/teams/${teamId}/faqs`)
  }

  /** チームの FAQ を一括 upsert する（ADMIN / SYSTEM_ADMIN 限定。成功 204）。 */
  async function saveTeamFaqs(teamId: string, req: SaveFaqRequest): Promise<void> {
    await api(`/api/v1/admin/teams/${teamId}/faqs`, {
      method: 'PUT',
      body: req,
    })
  }

  /** 組織の FAQ 編集ペイロードを取得する（ADMIN / SYSTEM_ADMIN 限定）。 */
  async function fetchOrgFaqEditor(orgId: string): Promise<FaqEditorResponse> {
    return api<FaqEditorResponse>(`/api/v1/admin/organizations/${orgId}/faqs`)
  }

  /** 組織の FAQ を一括 upsert する（ADMIN / SYSTEM_ADMIN 限定。成功 204）。 */
  async function saveOrgFaqs(orgId: string, req: SaveFaqRequest): Promise<void> {
    await api(`/api/v1/admin/organizations/${orgId}/faqs`, {
      method: 'PUT',
      body: req,
    })
  }

  /**
   * チームの公開 FAQ 一覧を取得する（認証不要）。
   * PRIVATE / 不在 / 削除済みは 404（IDOR 対策）。
   */
  async function fetchPublicTeamFaqs(teamId: string): Promise<PublicFaqItem[]> {
    return api<PublicFaqItem[]>(`/api/v1/public/teams/${teamId}/faqs`)
  }

  /** 組織の公開 FAQ 一覧を取得する（認証不要）。 */
  async function fetchPublicOrgFaqs(orgId: string): Promise<PublicFaqItem[]> {
    return api<PublicFaqItem[]>(`/api/v1/public/organizations/${orgId}/faqs`)
  }

  return {
    fetchTeamFaqEditor,
    saveTeamFaqs,
    fetchOrgFaqEditor,
    saveOrgFaqs,
    fetchPublicTeamFaqs,
    fetchPublicOrgFaqs,
  }
}
