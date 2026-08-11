/**
 * F03.16 予定コメントスレッド API composable。
 *
 * 設計書: docs/features/F03.16_schedule_comment_thread.md §4
 *
 * 全 8 エンドポイントが認証必須（未ログイン向けの分岐は持たない・§4.1 の再訂正）。
 * `body` は BE 側で `ApiResponse<T>` / `PagedResponse<T>` エンベロープに包まれて返るため、
 * 呼び出し側は `.data` / `.meta` を参照する。
 */
import type {
  CreateScheduleCommentRequest,
  ScheduleCommentMentionCandidate,
  ScheduleCommentPagedResponse,
  ScheduleCommentResponse,
  ScheduleCommentThreadMeta,
  ScheduleCommentThreadSettingsRequest,
  ScheduleCommentThreadSettingsResponse,
  UpdateScheduleCommentRequest,
} from '~/types/scheduleComment'

export function useScheduleComments() {
  const api = useApi()

  function base(scheduleId: number) {
    return `/api/v1/schedules/${scheduleId}/comments`
  }

  /** GET .../comments（トップレベル一覧＋最新3件までの返信同梱・設計書 §4.3） */
  async function listComments(
    scheduleId: number,
    params?: { page?: number; size?: number; sort?: string },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.sort) query.set('sort', params.sort)
    return api<ScheduleCommentPagedResponse>(`${base(scheduleId)}?${query}`)
  }

  /** GET .../comments/meta（スレッド開閉・投稿可否・設計書 §4.4） */
  async function getMeta(scheduleId: number) {
    return api<{ data: ScheduleCommentThreadMeta }>(`${base(scheduleId)}/meta`)
  }

  /** GET .../comments/{commentId}/replies（指定トップレベルの全返信・設計書 §4.4） */
  async function listReplies(
    scheduleId: number,
    commentId: string,
    params?: { page?: number; size?: number; sort?: string },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.sort) query.set('sort', params.sort)
    return api<ScheduleCommentPagedResponse>(
      `${base(scheduleId)}/${commentId}/replies?${query}`,
    )
  }

  /** GET .../comments/mention-candidates（メンション候補・可視性フィルタ済み・設計書 §4.4） */
  async function mentionCandidates(scheduleId: number, q?: string, size = 20) {
    const query = new URLSearchParams()
    if (q) query.set('q', q)
    query.set('size', String(size))
    return api<{ data: ScheduleCommentMentionCandidate[] }>(
      `${base(scheduleId)}/mention-candidates?${query}`,
    )
  }

  /** POST .../comments（投稿／返信・設計書 §4.2） */
  async function createComment(scheduleId: number, request: CreateScheduleCommentRequest) {
    return api<{ data: ScheduleCommentResponse }>(base(scheduleId), {
      method: 'POST',
      body: request,
    })
  }

  /** PATCH .../comments/{commentId}（本文編集・自分のコメントのみ・設計書 §4.4） */
  async function updateComment(
    scheduleId: number,
    commentId: string,
    request: UpdateScheduleCommentRequest,
  ) {
    return api<{ data: ScheduleCommentResponse }>(`${base(scheduleId)}/${commentId}`, {
      method: 'PATCH',
      body: request,
    })
  }

  /** DELETE .../comments/{commentId}（論理削除・設計書 §4.4） */
  async function deleteComment(scheduleId: number, commentId: string) {
    return api(`${base(scheduleId)}/${commentId}`, { method: 'DELETE' })
  }

  /** PATCH .../comments/settings（スレッド開閉・SYSTEM_ADMIN/ADMIN/予定作成者限定・設計書 §4.4） */
  async function updateSettings(scheduleId: number, commentsEnabled: boolean) {
    const request: ScheduleCommentThreadSettingsRequest = { commentsEnabled }
    return api<{ data: ScheduleCommentThreadSettingsResponse }>(`${base(scheduleId)}/settings`, {
      method: 'PATCH',
      body: request,
    })
  }

  return {
    listComments,
    getMeta,
    listReplies,
    mentionCandidates,
    createComment,
    updateComment,
    deleteComment,
    updateSettings,
  }
}
