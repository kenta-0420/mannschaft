/**
 * 業者マスタ API クライアント（F09.13 Phase 1-ε）。
 *
 * バックエンド: VendorController
 *  パス: /api/v1/{scope}/{scopeId}/vendors
 *
 * レスポンス契約:
 *  - 単体: ApiResponse<T> = { data: T }
 *  - 一覧: PagedResponse<T> = { data: T[], meta: { total, page, size, totalPages } }
 *  - サジェスト: ApiResponse<T[]>
 */
import type {
  VendorRequest,
  VendorResponse,
  VendorSuggestionResponse,
} from '~/types/vendor'
import type { ScopeName } from '~/types/property'

export interface VendorListParams {
  q?: string
  category?: string
  isActive?: boolean
  page?: number
  size?: number
}

export interface VendorListMeta {
  total: number
  page: number
  size: number
  totalPages: number
}

export function useVendorApi(scope: ScopeName, scopeId: string) {
  const api = useApi()
  const base = `/api/v1/${scope}/${scopeId}/vendors`

  async function list(params: VendorListParams = {}) {
    const query = new URLSearchParams()
    if (params.q !== undefined && params.q !== '') query.set('q', params.q)
    if (params.category) query.set('category', params.category)
    if (params.isActive !== undefined) query.set('isActive', String(params.isActive))
    if (params.page !== undefined) query.set('page', String(params.page))
    if (params.size !== undefined) query.set('size', String(params.size))
    const qs = query.toString()
    const url = qs ? `${base}?${qs}` : base
    const res = await api<{ data: VendorResponse[]; meta: VendorListMeta }>(url)
    return res
  }

  async function get(id: number): Promise<VendorResponse> {
    const res = await api<{ data: VendorResponse }>(`${base}/${id}`)
    return res.data
  }

  async function create(req: VendorRequest): Promise<VendorResponse> {
    const res = await api<{ data: VendorResponse }>(base, {
      method: 'POST',
      body: req,
    })
    return res.data
  }

  async function update(id: number, req: VendorRequest): Promise<VendorResponse> {
    const res = await api<{ data: VendorResponse }>(`${base}/${id}`, {
      method: 'PUT',
      body: req,
    })
    return res.data
  }

  async function remove(id: number): Promise<void> {
    await api(`${base}/${id}`, { method: 'DELETE' })
  }

  async function suggest(q: string): Promise<VendorSuggestionResponse[]> {
    const query = new URLSearchParams({ q })
    const res = await api<{ data: VendorSuggestionResponse[] }>(
      `${base}/search?${query.toString()}`,
    )
    return res.data
  }

  return {
    list,
    get,
    create,
    update,
    remove,
    suggest,
  }
}
