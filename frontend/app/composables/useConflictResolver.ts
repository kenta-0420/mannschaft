import type {
  SyncConflictListItem,
  SyncConflictDetail,
  ResolveConflictPayload,
} from '~/types/sync'
import type { PagedResponse } from '~/types/api'

/**
 * F11.1 Phase 5: コンフリクト解決 API クライアント。
 *
 * Backend の同期コンフリクト管理エンドポイントを呼び出す。
 * useApi() を使って認証付きリクエストを送る。
 */

/** Backend はスネークケースで返すため、一覧用の raw 型を定義 */
type RawConflictListItem = {
  id: number
  resource_type: string
  resource_id: number
  client_version: number | null
  server_version: number | null
  resolution: string | null
  resolved_at: string | null
  created_at: string
}

/** Backend のコンフリクト詳細 raw 型 */
type RawConflictDetail = {
  id: number
  user_id: number
  resource_type: string
  resource_id: number
  client_data: string
  server_data: string
  client_version: number | null
  server_version: number | null
  resolution: string | null
  resolved_at: string | null
  created_at: string
  updated_at: string
}

type RawPagedResponse = {
  data: RawConflictListItem[]
  meta: {
    totalElements: number
    page: number
    size: number
    totalPages: number
  }
}

function normalizeListItem(raw: RawConflictListItem): SyncConflictListItem {
  return {
    id: raw.id,
    resourceType: raw.resource_type,
    resourceId: raw.resource_id,
    clientVersion: raw.client_version,
    serverVersion: raw.server_version,
    resolution: raw.resolution,
    resolvedAt: raw.resolved_at,
    createdAt: raw.created_at,
  }
}

function parseJsonSafe(value: string): Record<string, unknown> {
  try {
    return JSON.parse(value) as Record<string, unknown>
  } catch {
    return {}
  }
}

function normalizeDetail(raw: RawConflictDetail): SyncConflictDetail {
  return {
    id: raw.id,
    userId: raw.user_id,
    resourceType: raw.resource_type,
    resourceId: raw.resource_id,
    clientData: typeof raw.client_data === 'string'
      ? parseJsonSafe(raw.client_data)
      : (raw.client_data as Record<string, unknown>),
    serverData: typeof raw.server_data === 'string'
      ? parseJsonSafe(raw.server_data)
      : (raw.server_data as Record<string, unknown>),
    clientVersion: raw.client_version,
    serverVersion: raw.server_version,
    resolution: raw.resolution,
    resolvedAt: raw.resolved_at,
    createdAt: raw.created_at,
    updatedAt: raw.updated_at,
  }
}

export function useConflictResolver() {
  const api = useApi()
  const BASE = '/api/v1/sync/conflicts'

  /**
   * 自分の未解決コンフリクト一覧を取得する。
   */
  async function getMyConflicts(
    page = 0,
    size = 20,
  ): Promise<PagedResponse<SyncConflictListItem>> {
    const res = await api<RawPagedResponse>(
      `${BASE}/me?page=${page}&size=${size}`,
    )
    return {
      data: (res.data ?? []).map(normalizeListItem),
      meta: {
        page: res.meta.page,
        size: res.meta.size,
        totalElements: res.meta.totalElements,
        totalPages: res.meta.totalPages,
      },
    }
  }

  /**
   * コンフリクト詳細を取得する。
   */
  async function getConflictDetail(id: number): Promise<SyncConflictDetail> {
    const res = await api<{ data: RawConflictDetail }>(`${BASE}/${id}`)
    return normalizeDetail(res.data)
  }

  /**
   * コンフリクトを解決する。
   */
  async function resolveConflict(
    id: number,
    payload: ResolveConflictPayload,
  ): Promise<SyncConflictDetail> {
    const body: Record<string, unknown> = {
      resolution: payload.resolution,
    }
    if (payload.mergedData) {
      body.merged_data = JSON.stringify(payload.mergedData)
    }
    const res = await api<{ data: RawConflictDetail }>(
      `${BASE}/${id}/resolve`,
      {
        method: 'PATCH',
        body,
      },
    )
    return normalizeDetail(res.data)
  }

  /**
   * コンフリクトを破棄する。
   */
  async function discardConflict(id: number): Promise<void> {
    await api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  return {
    getMyConflicts,
    getConflictDetail,
    resolveConflict,
    discardConflict,
  }
}
