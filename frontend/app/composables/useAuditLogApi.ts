import type { CursorMeta, PageMeta } from '~/types/api'
import type { AuditLog, AuditLogCursorParams, AuditLogParams } from '~/types/audit-log'

/**
 * 監査ログ取得 API。
 *
 * BE のページング方式がエンドポイントごとに異なるため、戻り値の `meta` も異なる（CMP-260912-1823）。
 *  - `/api/v1/admin/audit-logs`（シスアド）… `PagedResponse` → `meta` は {@link PageMeta}（オフセット・総件数あり）
 *  - `/api/v1/teams/{teamId}/audit-logs`、`/api/v1/organizations/{orgId}/audit-logs`
 *    … `CursorPagedResponse` → `meta` は {@link CursorMeta}（カーソル・**総件数は存在しない**）
 *
 * かつては3本とも同じオフセット型（しかも BE が送らない `totalElements`）で型付けしており、
 * `page` / `size` を送っても BE は `cursor` / `limit` しか読まないため、
 * 2ページ目以降が常に1ページ目と同じ内容になっていた。
 */
export function useAuditLogApi() {
  const api = useApi()

  /** 共通の絞り込み条件をクエリへ積む。 */
  function appendFilters(query: URLSearchParams, params?: AuditLogParams | AuditLogCursorParams) {
    if (params?.userId) query.set('userId', String(params.userId))
    if (params?.targetUserId) query.set('targetUserId', String(params.targetUserId))
    if (params?.eventType) query.set('eventType', params.eventType)
    if (params?.eventCategory) query.set('eventCategory', params.eventCategory)
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
  }

  /** オフセットページング（シスアド監査ログ）用のクエリを組み立てる。 */
  function buildOffsetQuery(params?: AuditLogParams) {
    const query = new URLSearchParams()
    appendFilters(query, params)
    if (params?.page != null) query.set('page', String(params.page))
    if (params?.size != null) query.set('size', String(params.size))
    return query.toString()
  }

  /** カーソルページング（チーム・組織監査ログ）用のクエリを組み立てる。 */
  function buildCursorQuery(params?: AuditLogCursorParams) {
    const query = new URLSearchParams()
    appendFilters(query, params)
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.limit != null) query.set('limit', String(params.limit))
    return query.toString()
  }

  /** シスアド向け全体監査ログ（オフセットページング・総件数あり）。 */
  async function listAll(params?: AuditLogParams) {
    const qs = buildOffsetQuery(params)
    return await api<{ data: AuditLog[]; meta: PageMeta }>(
      `/api/v1/admin/audit-logs${qs ? `?${qs}` : ''}`,
    )
  }

  /** チーム監査ログ（カーソルページング・総件数なし）。 */
  async function listByTeam(teamId: string, params?: AuditLogCursorParams) {
    const qs = buildCursorQuery(params)
    return await api<{ data: AuditLog[]; meta: CursorMeta }>(
      `/api/v1/teams/${teamId}/audit-logs${qs ? `?${qs}` : ''}`,
    )
  }

  /** 組織監査ログ（カーソルページング・総件数なし）。 */
  async function listByOrg(orgId: string, params?: AuditLogCursorParams) {
    const qs = buildCursorQuery(params)
    return await api<{ data: AuditLog[]; meta: CursorMeta }>(
      `/api/v1/organizations/${orgId}/audit-logs${qs ? `?${qs}` : ''}`,
    )
  }

  return { listAll, listByTeam, listByOrg }
}
