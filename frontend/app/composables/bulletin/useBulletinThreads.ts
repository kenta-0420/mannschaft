import type {
  BulletinThreadResponse,
  BulletinReader,
  BulletinReadStatus,
  BulletinThreadSearchParams,
  BulletinAttachment,
} from '~/types/bulletin'
import { useBulletinAttachments } from './useBulletinAttachments'

interface ThreadListParams {
  scopeType: string
  /** TEAM/ORGANIZATION は数値ID、VILLAGE は UUID 文字列 */
  scopeId: string | number
  categoryId?: number
  priority?: string
  isArchived?: boolean
  search?: string
  page?: number
  size?: number
}

/**
 * 掲示板スレッド関連 API（グローバル / スコープ別 / 既読状態）。
 *
 * - グローバル: `/api/v1/bulletin/threads`
 * - スコープ別: `/api/v1/{scopeType}/{scopeId}/bulletin/threads`
 * - 既読状態: `/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status`
 */
export function useBulletinThreads() {
  const api = useApi()
  const { uploadFile: uploadAttachment } = useBulletinAttachments()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        query.set(key, String(value))
      }
    }
    return query.toString()
  }

  // === Threads ===
  async function getThreads(params: ThreadListParams) {
    // VILLAGE スコープ: scope_id=0 + scope_village_id=UUID（設計書 §3.12.1）
    const isVillage = params.scopeType === 'VILLAGE'
    const qs = buildQuery({
      scope_type: params.scopeType,
      scope_id: isVillage ? 0 : params.scopeId,
      ...(isVillage ? { scope_village_id: params.scopeId } : {}),
      category_id: params.categoryId,
      priority: params.priority,
      is_archived: params.isArchived,
      search: params.search,
      page: params.page ?? 0,
      size: params.size ?? 20,
    })
    return api<{
      data: BulletinThreadResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/bulletin/threads?${qs}`)
  }

  async function getThread(threadId: number) {
    return api<{ data: BulletinThreadResponse }>(
      `/api/v1/bulletin/threads/${threadId}`,
    )
  }

  /**
   * スレッドを作成し、ファイルが指定された場合は presign→R2直PUT→確定 の順でアップロードする。
   *
   * フロー変更（F05.1 §6 presigned URL 方式 A）:
   *   (1) スレッド本文を POST して threadId を取得
   *   (2) 各ファイルを presign → R2 直 PUT → 確定（1件失敗しても他を継続）
   *   (3) アップロード結果（成功/失敗一覧）を返す
   *
   * @returns { thread, attachments, uploadErrors }
   *   - thread: 作成されたスレッド
   *   - attachments: 確定に成功した添付ファイル
   *   - uploadErrors: アップロードに失敗したファイル名一覧（空なら全成功）
   */
  async function createThread(
    scopeType: string,
    /** TEAM/ORGANIZATION は数値ID、VILLAGE は UUID 文字列 */
    scopeId: string | number,
    body: Record<string, unknown>,
    files?: File[],
  ): Promise<{
    thread: BulletinThreadResponse
    attachments: BulletinAttachment[]
    uploadErrors: string[]
  }> {
    // VILLAGE スコープ: scope_id=0 + scopeVillageId=UUID（設計書 §3.12.1）
    const isVillage = scopeType === 'VILLAGE'
    const resolvedScopeId = isVillage ? 0 : scopeId
    const extraFields = isVillage ? { scopeVillageId: scopeId } : {}

    // (1) スレッド本文を作成して threadId を取得
    const threadRes = await api<{ data: BulletinThreadResponse }>('/api/v1/bulletin/threads', {
      method: 'POST',
      body: { ...body, scopeType, scopeId: resolvedScopeId, ...extraFields },
    })
    const thread = threadRes.data
    const threadId = thread.id

    if (!files || files.length === 0) {
      return { thread, attachments: [], uploadErrors: [] }
    }

    // (2) 各ファイルを presign → R2 直 PUT → 確定（1件失敗しても他を継続）
    const attachments: BulletinAttachment[] = []
    const uploadErrors: string[] = []

    await Promise.all(
      files.map(async (file) => {
        try {
          const attachment = await uploadAttachment('THREAD', threadId, file)
          attachments.push(attachment)
        } catch {
          uploadErrors.push(file.name)
        }
      }),
    )

    return { thread, attachments, uploadErrors }
  }

  async function updateThread(threadId: number, body: Record<string, unknown>) {
    return api<{ data: BulletinThreadResponse }>(`/api/v1/bulletin/threads/${threadId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteThread(threadId: number) {
    return api(`/api/v1/bulletin/threads/${threadId}`, { method: 'DELETE' })
  }

  async function changePriority(threadId: number, priority: string) {
    return api(`/api/v1/bulletin/threads/${threadId}/priority`, {
      method: 'PATCH',
      body: { priority },
    })
  }

  async function markRead(threadId: number) {
    return api(`/api/v1/bulletin/threads/${threadId}/read`, { method: 'POST' })
  }

  async function getReaders(threadId: number, filter?: 'unread') {
    const qs = filter ? `?filter=${filter}` : ''
    return api<{ data: BulletinReader[] }>(`/api/v1/bulletin/threads/${threadId}/readers${qs}`)
  }

  async function togglePin(threadId: number, pinned: boolean) {
    return api(`/api/v1/bulletin/threads/${threadId}/pin`, { method: 'PATCH', body: { pinned } })
  }

  async function toggleLock(threadId: number, locked: boolean) {
    return api(`/api/v1/bulletin/threads/${threadId}/lock`, { method: 'PATCH', body: { locked } })
  }

  async function toggleArchive(threadId: number, archived: boolean) {
    // BE は POST + body { is_archived: boolean } の双方向 API（設計書 F05.1 §4）
    return api(`/api/v1/bulletin/threads/${threadId}/archive`, {
      method: 'POST',
      body: { is_archived: archived },
    })
  }

  async function readAll(scopeType: string, scopeId: string | number) {
    const isVillage = scopeType === 'VILLAGE'
    return api('/api/v1/bulletin/threads/read-all', {
      method: 'POST',
      body: {
        scopeType,
        scopeId: isVillage ? 0 : scopeId,
        ...(isVillage ? { scopeVillageId: scopeId } : {}),
      },
    })
  }

  // === Scoped Threads ===
  async function getScopedThreads(
    scopeType: string,
    scopeId: string,
    params?: { categoryId?: number; page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.categoryId) query.set('categoryId', String(params.categoryId))
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: BulletinThreadResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/${scopeType}/${scopeId}/bulletin/threads?${query}`)
  }

  async function searchScopedThreads(
    scopeType: string,
    scopeId: string,
    params: BulletinThreadSearchParams,
  ) {
    const query = new URLSearchParams()
    query.set('keyword', params.keyword)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    return api<{
      data: BulletinThreadResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/search?${query}`)
  }

  async function createScopedThread(
    scopeType: string,
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: BulletinThreadResponse }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads`,
      { method: 'POST', body },
    )
  }

  async function getScopedThread(scopeType: string, scopeId: string, threadId: number) {
    return api<{ data: BulletinThreadResponse }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}`,
    )
  }

  async function updateScopedThread(
    scopeType: string,
    scopeId: string,
    threadId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: BulletinThreadResponse }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteScopedThread(scopeType: string, scopeId: string, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}`, { method: 'DELETE' })
  }

  async function archiveScopedThread(
    scopeType: string,
    scopeId: string | number,
    threadId: number,
    isArchived = true,
    archiveFolderId?: string | null,
  ) {
    // BE は POST + body { is_archived: boolean, archive_folder_id?: string|null } の双方向 API
    //（設計書 F05.1 §4）。後方互換のため未指定時は true（アーカイブ）。
    // archiveFolderId は is_archived=true 時のみ任意で振り分け先を指定（省略=保管庫直下）。
    // is_archived=false（解除）時はサーバー側で自動 NULL リセットされる。
    const body: Record<string, unknown> = { is_archived: isArchived }
    if (isArchived && archiveFolderId !== undefined) {
      body.archive_folder_id = archiveFolderId
    }
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/archive`, {
      method: 'POST',
      body,
    })
  }

  async function lockScopedThread(scopeType: string, scopeId: string, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/lock`, {
      method: 'POST',
    })
  }

  async function pinScopedThread(scopeType: string, scopeId: string, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/pin`, {
      method: 'POST',
    })
  }

  // === Scoped Read Status ===
  async function getReadStatus(scopeType: string, scopeId: string, threadId: number) {
    return api<{ data: BulletinReadStatus }>(
      `/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/read-status`,
    )
  }

  async function markReadStatus(scopeType: string, scopeId: string, threadId: number) {
    return api(`/api/v1/${scopeType}/${scopeId}/bulletin/threads/${threadId}/read-status`, {
      method: 'POST',
    })
  }

  return {
    getThreads,
    getThread,
    createThread,
    updateThread,
    deleteThread,
    changePriority,
    markRead,
    getReaders,
    togglePin,
    toggleLock,
    toggleArchive,
    readAll,
    getScopedThreads,
    searchScopedThreads,
    createScopedThread,
    getScopedThread,
    updateScopedThread,
    deleteScopedThread,
    archiveScopedThread,
    lockScopedThread,
    pinScopedThread,
    getReadStatus,
    markReadStatus,
  }
}
