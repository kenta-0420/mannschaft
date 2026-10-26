import type {
  QuickMemoResponse,
  PagedQuickMemos,
  CreateQuickMemoRequest,
  UpdateQuickMemoRequest,
  ConvertToTodoRequest,
  ConvertToTodoResponse,
  PresignRequest,
  PresignResponse,
  AttachmentSummary,
} from '~/types/quickMemo'

export function useQuickMemoApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  // ─── メモ一覧 ───────────────────────────────────────────────────────────────

  async function listMemos(params: { status?: string; page?: number; size?: number } = {}) {
    const qs = buildQuery({ status: 'UNSORTED', page: 1, size: 20, ...params })
    return api<PagedQuickMemos>(`/api/v1/quick-memos?${qs}`)
  }

  async function listTrash(params: { page?: number; size?: number } = {}) {
    const qs = buildQuery({ page: 1, size: 20, ...params })
    return api<PagedQuickMemos>(`/api/v1/quick-memos/trash?${qs}`)
  }

  async function searchMemos(q: string) {
    const qs = buildQuery({ q })
    return api<{ data: QuickMemoResponse[] }>(`/api/v1/quick-memos/search?${qs}`)
  }

  // ─── メモ単体 ───────────────────────────────────────────────────────────────

  async function getMemo(id: number) {
    return api<{ data: QuickMemoResponse }>(`/api/v1/quick-memos/${id}`)
  }

  async function createMemo(body: CreateQuickMemoRequest) {
    return api<{ data: QuickMemoResponse }>('/api/v1/quick-memos', { method: 'POST', body })
  }

  async function updateMemo(id: number, body: UpdateQuickMemoRequest) {
    return api<{ data: QuickMemoResponse }>(`/api/v1/quick-memos/${id}`, { method: 'PUT', body })
  }

  async function deleteMemo(id: number) {
    return api(`/api/v1/quick-memos/${id}`, { method: 'DELETE' })
  }

  async function archiveMemo(id: number) {
    return api<{ data: QuickMemoResponse }>(`/api/v1/quick-memos/${id}/archive`, {
      method: 'PATCH',
    })
  }

  async function restoreMemo(id: number) {
    return api<{ data: QuickMemoResponse }>(`/api/v1/quick-memos/${id}/restore`, {
      method: 'PATCH',
    })
  }

  async function undeleteMemo(id: number) {
    return api<{ data: QuickMemoResponse }>(`/api/v1/quick-memos/${id}/undelete`, {
      method: 'POST',
    })
  }

  async function convertToTodo(id: number, body: ConvertToTodoRequest) {
    return api<{ data: ConvertToTodoResponse }>(`/api/v1/quick-memos/${id}/convert-to-todo`, {
      method: 'POST',
      body,
    })
  }

  // ─── 添付ファイル ────────────────────────────────────────────────────────────

  async function presignUrl(memoId: number, body: PresignRequest) {
    return api<{ data: PresignResponse }>(`/api/v1/quick-memos/${memoId}/attachments/presign`, {
      method: 'POST',
      body,
    })
  }

  async function confirmUpload(memoId: number, s3Key: string, originalFilename?: string | null) {
    return api<{ data: AttachmentSummary }>(
      `/api/v1/quick-memos/${memoId}/attachments/confirm`,
      { method: 'POST', body: { s3Key, originalFilename: originalFilename ?? null } },
    )
  }

  async function deleteAttachment(memoId: number, attachmentId: number) {
    return api(`/api/v1/quick-memos/${memoId}/attachments/${attachmentId}`, { method: 'DELETE' })
  }

  return {
    listMemos,
    listTrash,
    searchMemos,
    getMemo,
    createMemo,
    updateMemo,
    deleteMemo,
    archiveMemo,
    restoreMemo,
    undeleteMemo,
    convertToTodo,
    presignUrl,
    confirmUpload,
    deleteAttachment,
  }
}
