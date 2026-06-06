/**
 * 物件履歴パッケージ API クライアント（F09.13 Phase 1-ε）。
 *
 * バックエンド: PropertyWorkPackageController
 *  パス: /api/v1/{scope}/{scopeId}/property-history
 *
 * レスポンス契約:
 *  - 単体:  ApiResponse<T>     = { data: T }
 *  - 一覧:  PagedResponse<T>   = { data: T[], meta: { total, page, size, totalPages } }
 *  - 配列:  ApiResponse<T[]>   = { data: T[] }
 *  - バイナリ: PDF / Excel は直接 Blob で返却（Content-Disposition でファイル名）
 */
import type {
  ChangeWorkPackageStatusRequest,
  CategorySuggestionResponse,
  PropertyWorkDocumentRequest,
  PropertyWorkDocumentResponse,
  PropertyWorkPackageRequest,
  PropertyWorkPackageResponse,
  PropertyWorkPackageSummaryResponse,
  ScopeName,
  WorkPackageExportFormat,
  WorkPackageListFilter,
} from '~/types/property'

export interface PropertyWorkPackageListMeta {
  total: number
  page: number
  size: number
  totalPages: number
}

export function usePropertyWorkPackageApi(scope: ScopeName, scopeId: string) {
  const api = useApi()
  const base = `/api/v1/${scope}/${scopeId}/property-history`

  function buildFilterQuery(
    filter: WorkPackageListFilter | undefined,
    extra?: Record<string, string>,
  ): string {
    const query = new URLSearchParams()
    if (filter) {
      if (filter.from) query.set('from', filter.from)
      if (filter.to) query.set('to', filter.to)
      if (filter.workType) query.set('workType', filter.workType)
      if (filter.vendorId !== undefined && filter.vendorId !== null) {
        query.set('vendorId', String(filter.vendorId))
      }
      if (filter.status) query.set('status', filter.status)
      if (filter.page !== undefined) query.set('page', String(filter.page))
      if (filter.size !== undefined) query.set('size', String(filter.size))
    }
    if (extra) {
      for (const [k, v] of Object.entries(extra)) {
        query.set(k, v)
      }
    }
    return query.toString()
  }

  async function list(filter: WorkPackageListFilter = {}) {
    const qs = buildFilterQuery(filter)
    const url = qs ? `${base}?${qs}` : base
    const res = await api<{
      data: PropertyWorkPackageSummaryResponse[]
      meta: PropertyWorkPackageListMeta
    }>(url)
    return res
  }

  async function getTimeline(
    from?: string | null,
    to?: string | null,
  ): Promise<PropertyWorkPackageSummaryResponse[]> {
    const query = new URLSearchParams()
    if (from) query.set('from', from)
    if (to) query.set('to', to)
    const qs = query.toString()
    const url = qs ? `${base}/timeline?${qs}` : `${base}/timeline`
    const res = await api<{ data: PropertyWorkPackageSummaryResponse[] }>(url)
    return res.data
  }

  async function getGantt(
    from: string,
    to: string,
  ): Promise<PropertyWorkPackageSummaryResponse[]> {
    const query = new URLSearchParams({ from, to })
    const res = await api<{ data: PropertyWorkPackageSummaryResponse[] }>(
      `${base}/gantt?${query.toString()}`,
    )
    return res.data
  }

  async function get(id: number): Promise<PropertyWorkPackageResponse> {
    const res = await api<{ data: PropertyWorkPackageResponse }>(`${base}/${id}`)
    return res.data
  }

  async function create(req: PropertyWorkPackageRequest): Promise<PropertyWorkPackageResponse> {
    const res = await api<{ data: PropertyWorkPackageResponse }>(base, {
      method: 'POST',
      body: req,
    })
    return res.data
  }

  async function update(
    id: number,
    req: PropertyWorkPackageRequest,
  ): Promise<PropertyWorkPackageResponse> {
    const res = await api<{ data: PropertyWorkPackageResponse }>(`${base}/${id}`, {
      method: 'PUT',
      body: req,
    })
    return res.data
  }

  async function changeStatus(
    id: number,
    req: ChangeWorkPackageStatusRequest,
  ): Promise<PropertyWorkPackageResponse> {
    const res = await api<{ data: PropertyWorkPackageResponse }>(`${base}/${id}/status`, {
      method: 'PATCH',
      body: req,
    })
    return res.data
  }

  async function remove(id: number): Promise<void> {
    await api(`${base}/${id}`, { method: 'DELETE' })
  }

  async function attachDocument(
    id: number,
    req: PropertyWorkDocumentRequest,
  ): Promise<PropertyWorkDocumentResponse> {
    const res = await api<{ data: PropertyWorkDocumentResponse }>(
      `${base}/${id}/documents`,
      { method: 'POST', body: req },
    )
    return res.data
  }

  async function detachDocument(packageId: number, documentId: number): Promise<void> {
    await api(`${base}/${packageId}/documents/${documentId}`, { method: 'DELETE' })
  }

  /**
   * 単独パッケージのエクスポート（PDF/Excel）を Blob で取得する。
   *
   * バックエンドが Content-Disposition で日本語ファイル名（RFC 5987）を返すため、
   * ファイル名抽出は呼び出し側のダウンロードヘルパーで行う。
   */
  async function exportSingle(
    id: number,
    format: WorkPackageExportFormat,
  ): Promise<Blob> {
    const config = useRuntimeConfig()
    const { accessToken } = useAuthStore()
    const url = `${config.public.apiBase}${base}/${id}/export?format=${format}`
    return $fetch<Blob>(url, {
      method: 'POST',
      responseType: 'blob',
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    })
  }

  /**
   * 一覧エクスポート（PDF/Excel）を Blob で取得する（フィルタ可）。
   */
  async function exportList(
    format: WorkPackageExportFormat,
    filter: WorkPackageListFilter = {},
  ): Promise<Blob> {
    const config = useRuntimeConfig()
    const { accessToken } = useAuthStore()
    const qs = buildFilterQuery(filter, { format })
    const url = `${config.public.apiBase}${base}/export?${qs}`
    return $fetch<Blob>(url, {
      method: 'POST',
      responseType: 'blob',
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    })
  }

  async function categorySuggestions(
    sinceMonths?: number,
  ): Promise<CategorySuggestionResponse[]> {
    const query = new URLSearchParams()
    if (sinceMonths !== undefined) query.set('since', String(sinceMonths))
    const qs = query.toString()
    const url = qs
      ? `${base}/categories/suggestions?${qs}`
      : `${base}/categories/suggestions`
    const res = await api<{ data: CategorySuggestionResponse[] }>(url)
    return res.data
  }

  return {
    list,
    getTimeline,
    getGantt,
    get,
    create,
    update,
    changeStatus,
    remove,
    attachDocument,
    detachDocument,
    exportSingle,
    exportList,
    categorySuggestions,
  }
}
