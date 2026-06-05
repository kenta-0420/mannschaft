/**
 * 重要事項説明書（参考） API クライアント — F09.14 Phase 2-β-5
 *
 * バックエンド: DisclosureController（Phase 2-β-4）
 *  - 様式テンプレート: /api/v1/disclosure-templates?organizationId=
 *  - ドラフト:        /api/v1/organizations/{orgId}/disclosure-drafts
 *  - 出力履歴:        /api/v1/organizations/{orgId}/disclosure-exports
 *
 * レスポンス契約:
 *  - 単体:  { data: T }
 *  - 一覧:  { data: T[], meta?: { total, page, size, totalPages } }
 */
import type {
  DisclosureDraftListFilter,
  DisclosureExport,
  DisclosureExportListFilter,
  DisclosureFormDraft,
  DisclosureFormDraftRequest,
  DisclosureFormTemplate,
  DisclosureListMeta,
  DisclosureOutputFormat,
  ExtendExpiryRequest,
  ExtendExpiryResponse,
} from '~/types/disclosure'

/**
 * 重要事項説明書 API。
 * @param organizationId 対象組織 ID（必須）。
 */
export function useDisclosureApi(organizationId: string) {
  const api = useApi()
  const draftBase = `/api/v1/organizations/${organizationId}/disclosure-drafts`
  const exportBase = `/api/v1/organizations/${organizationId}/disclosure-exports`
  const templateBase = '/api/v1/disclosure-templates'

  // === Templates ===

  /** 利用可能な様式テンプレート一覧（システム提供 + 組織カスタム）。 */
  async function listTemplates(prefectureCode?: string | null): Promise<DisclosureFormTemplate[]> {
    const query = new URLSearchParams({ organizationId: String(organizationId) })
    if (prefectureCode) query.set('prefectureCode', prefectureCode)
    const res = await api<{ data: DisclosureFormTemplate[] }>(`${templateBase}?${query.toString()}`)
    return res.data
  }

  /** 単一テンプレートを取得。 */
  async function getTemplate(templateId: number): Promise<DisclosureFormTemplate> {
    const res = await api<{ data: DisclosureFormTemplate }>(`${templateBase}/${templateId}`)
    return res.data
  }

  // === Drafts ===

  /** ドラフト一覧を取得（ページング・フィルタ対応）。 */
  async function listDrafts(filter: DisclosureDraftListFilter = {}): Promise<{
    data: DisclosureFormDraft[]
    meta: DisclosureListMeta | undefined
  }> {
    const query = new URLSearchParams()
    if (filter.status) query.set('status', filter.status)
    if (filter.templateId !== undefined && filter.templateId !== null) {
      query.set('templateId', String(filter.templateId))
    }
    if (filter.page !== undefined) query.set('page', String(filter.page))
    if (filter.size !== undefined) query.set('size', String(filter.size))
    const qs = query.toString()
    const url = qs ? `${draftBase}?${qs}` : draftBase
    const res = await api<{ data: DisclosureFormDraft[]; meta?: DisclosureListMeta }>(url)
    return { data: res.data, meta: res.meta }
  }

  /** 単一ドラフトを取得。 */
  async function getDraft(draftId: number): Promise<DisclosureFormDraft> {
    const res = await api<{ data: DisclosureFormDraft }>(`${draftBase}/${draftId}`)
    return res.data
  }

  /** ドラフトを新規作成。 */
  async function createDraft(req: DisclosureFormDraftRequest): Promise<DisclosureFormDraft> {
    const res = await api<{ data: DisclosureFormDraft }>(draftBase, {
      method: 'POST',
      body: req,
    })
    return res.data
  }

  /**
   * ドラフトを更新（楽観的ロック）。
   * 409 が返った場合は呼び出し側で再取得して競合解決すること。
   */
  async function updateDraft(
    draftId: number,
    req: DisclosureFormDraftRequest,
  ): Promise<DisclosureFormDraft> {
    const res = await api<{ data: DisclosureFormDraft }>(`${draftBase}/${draftId}`, {
      method: 'PUT',
      body: req,
    })
    return res.data
  }

  /** ドラフトを論理削除。 */
  async function deleteDraft(draftId: number): Promise<void> {
    await api(`${draftBase}/${draftId}`, { method: 'DELETE' })
  }

  /**
   * 自動引用データの再取得（個人情報許諾フラグ付き）。
   * - 引用ソース（DwellingUnitOwner / OrganizationName / PropertyHistoryPackages 等）から
   *   最新値をフェッチし、ドラフトの formData に AUTO_FIELD / AUTO_TABLE 値を上書きする。
   */
  async function refreshAutoFill(
    draftId: number,
    allowPersonalInfo: boolean,
  ): Promise<DisclosureFormDraft> {
    const query = new URLSearchParams({ allowPersonalInfo: String(allowPersonalInfo) })
    const res = await api<{ data: DisclosureFormDraft }>(
      `${draftBase}/${draftId}/refresh-auto-fill?${query.toString()}`,
      { method: 'POST' },
    )
    return res.data
  }

  /**
   * ドラフトを出力（PDF/Excel/Word）。
   * バックエンドは SharedFile を生成し、presigned URL を含む DisclosureExportResponse を返す。
   */
  async function exportDraft(
    draftId: number,
    format: DisclosureOutputFormat,
  ): Promise<DisclosureExport> {
    const query = new URLSearchParams({ format })
    const res = await api<{ data: DisclosureExport }>(
      `${draftBase}/${draftId}/export?${query.toString()}`,
      { method: 'POST' },
    )
    return res.data
  }

  // === Exports ===

  /** 出力履歴一覧を取得。 */
  async function listExports(filter: DisclosureExportListFilter = {}): Promise<{
    data: DisclosureExport[]
    meta: DisclosureListMeta | undefined
  }> {
    const query = new URLSearchParams()
    if (filter.outputFormat) query.set('outputFormat', filter.outputFormat)
    if (filter.page !== undefined) query.set('page', String(filter.page))
    if (filter.size !== undefined) query.set('size', String(filter.size))
    const qs = query.toString()
    const url = qs ? `${exportBase}?${qs}` : exportBase
    const res = await api<{ data: DisclosureExport[]; meta?: DisclosureListMeta }>(url)
    return { data: res.data, meta: res.meta }
  }

  /**
   * 出力履歴を再ダウンロードする。
   * バックエンドは SHA-256 整合性検証 → presigned URL 再発行を行う。
   * 検証失敗時は DISCLOSURE_010（503）が返る。
   */
  async function getExportDownloadUrl(exportId: number): Promise<DisclosureExport> {
    const res = await api<{ data: DisclosureExport }>(`${exportBase}/${exportId}/download`)
    return res.data
  }

  /**
   * 出力履歴の自動削除予定日（{@code expires_at}）を延長する（F09.14 Phase 3-E / 4-B、設計書 §5.7）。
   *
   * @param exportId       出力履歴 ID
   * @param newExpiresAt   新しい自動削除予定日時（ISO-8601 LocalDateTime 文字列。例: "2026-12-31T23:59:00"）
   * @returns 更新後の {@link DisclosureExport}
   *
   * バックエンド側でも検証されるが、過去日時 / 本日から 7 年超は呼び出し前に弾くこと。
   * 422 で {@code DISCLOSURE_011} が返ると延長不可。
   */
  async function extendExpiry(
    exportId: number,
    newExpiresAt: string,
  ): Promise<ExtendExpiryResponse> {
    const body: ExtendExpiryRequest = { newExpiresAt }
    const res = await api<{ data: DisclosureExport }>(
      `${exportBase}/${exportId}/extend-expiry`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  return {
    // テンプレート
    listTemplates,
    getTemplate,
    // ドラフト
    listDrafts,
    getDraft,
    createDraft,
    updateDraft,
    deleteDraft,
    refreshAutoFill,
    exportDraft,
    // 出力履歴
    listExports,
    getExportDownloadUrl,
    extendExpiry,
  }
}
