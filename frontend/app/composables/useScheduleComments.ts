/**
 * F03.16 予定コメントスレッド API composable。
 *
 * 設計書: docs/features/F03.16_schedule_comment_thread.md §4
 *
 * 全 8 エンドポイントが認証必須（未ログイン向けの分岐は持たない・§4.1 の再訂正）。
 * `body` は BE 側で `ApiResponse<T>` / `PagedResponse<T>` エンベロープに包まれて返るため、
 * 呼び出し側は `.data` / `.meta` を参照する。
 *
 * ## 生成型（undefined）↔ 手書き型（null）の境界【必読】
 *
 * 本 composable は `~/types/scheduleComment` が定義する境界そのものである
 * （詳細は同ファイル冒頭のコメント参照）。API から返る生の応答（`Raw*` 型・`undefined` の世界）を
 * 扱うのは、この関数内部・フェッチ直後の `to*` 正規化呼び出しまでに限定する。
 * 呼び出し元（コンポーネント）へ返す値・呼び出し元から受け取る値は、常に手書き型
 * （`null` の世界）である。新しいメソッドを足す場合もこの境界を崩さないこと。
 */
import type {
  CreateScheduleCommentRequest,
  RawMentionCandidateResponse,
  RawPageMeta,
  RawScheduleCommentResponse,
  RawThreadMetaResponse,
  ScheduleCommentMentionCandidate,
  ScheduleCommentPagedResponse,
  ScheduleCommentResponse,
  ScheduleCommentThreadMeta,
  ScheduleCommentThreadSettingsRequest,
  ScheduleCommentThreadSettingsResponse,
  UpdateScheduleCommentRequest,
} from '~/types/scheduleComment'
import {
  toScheduleCommentMentionCandidate,
  toScheduleCommentPagedResponse,
  toScheduleCommentResponse,
  toScheduleCommentThreadMeta,
} from '~/types/scheduleComment'

/** `createComment` のアプリ内入力形。`parentId` は未指定/返信対象なしを `null` で表す（手書き型の世界）。 */
export interface CreateScheduleCommentInput {
  body: string
  parentId?: string | null
  mentionedUserIds?: number[]
}

export function useScheduleComments() {
  const api = useApi()

  function base(scheduleId: number) {
    return `/api/v1/schedules/${scheduleId}/comments`
  }

  /** GET .../comments（トップレベル一覧＋最新3件までの返信同梱・設計書 §4.3） */
  async function listComments(
    scheduleId: number,
    params?: { page?: number; size?: number; sort?: string },
  ): Promise<ScheduleCommentPagedResponse> {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.sort) query.set('sort', params.sort)
    const raw = await api<{ data?: RawScheduleCommentResponse[]; meta?: RawPageMeta }>(
      `${base(scheduleId)}?${query}`,
    )
    return toScheduleCommentPagedResponse(raw)
  }

  /** GET .../comments/meta（スレッド開閉・投稿可否・設計書 §4.4） */
  async function getMeta(scheduleId: number): Promise<{ data: ScheduleCommentThreadMeta }> {
    const raw = await api<{ data: RawThreadMetaResponse }>(`${base(scheduleId)}/meta`)
    return { data: toScheduleCommentThreadMeta(raw.data) }
  }

  /** GET .../comments/{commentId}/replies（指定トップレベルの全返信・設計書 §4.4） */
  async function listReplies(
    scheduleId: number,
    commentId: string,
    params?: { page?: number; size?: number; sort?: string },
  ): Promise<ScheduleCommentPagedResponse> {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.sort) query.set('sort', params.sort)
    const raw = await api<{ data?: RawScheduleCommentResponse[]; meta?: RawPageMeta }>(
      `${base(scheduleId)}/${commentId}/replies?${query}`,
    )
    return toScheduleCommentPagedResponse(raw)
  }

  /** GET .../comments/mention-candidates（メンション候補・可視性フィルタ済み・設計書 §4.4） */
  async function mentionCandidates(
    scheduleId: number,
    q?: string,
    size = 20,
  ): Promise<{ data: ScheduleCommentMentionCandidate[] }> {
    const query = new URLSearchParams()
    if (q) query.set('q', q)
    query.set('size', String(size))
    const raw = await api<{ data?: RawMentionCandidateResponse[] }>(
      `${base(scheduleId)}/mention-candidates?${query}`,
    )
    return { data: (raw.data ?? []).map(toScheduleCommentMentionCandidate) }
  }

  /**
   * POST .../comments（投稿／返信・設計書 §4.2）。
   *
   * `input.parentId` は `null`（トップレベル投稿・返信対象なし）を許容し、ここで BE 契約どおりの
   * `undefined`（キー省略）へ変換してから送信する（生成型↔手書き型の境界はこの関数の内部で閉じる）。
   */
  async function createComment(
    scheduleId: number,
    input: CreateScheduleCommentInput,
  ): Promise<{ data: ScheduleCommentResponse }> {
    const request: CreateScheduleCommentRequest = {
      body: input.body,
      parentId: input.parentId ?? undefined,
      mentionedUserIds: input.mentionedUserIds,
    }
    const raw = await api<{ data: RawScheduleCommentResponse }>(base(scheduleId), {
      method: 'POST',
      body: request,
    })
    return { data: toScheduleCommentResponse(raw.data) }
  }

  /** PATCH .../comments/{commentId}（本文編集・自分のコメントのみ・設計書 §4.4） */
  async function updateComment(
    scheduleId: number,
    commentId: string,
    request: UpdateScheduleCommentRequest,
  ): Promise<{ data: ScheduleCommentResponse }> {
    const raw = await api<{ data: RawScheduleCommentResponse }>(`${base(scheduleId)}/${commentId}`, {
      method: 'PATCH',
      body: request,
    })
    return { data: toScheduleCommentResponse(raw.data) }
  }

  /** DELETE .../comments/{commentId}（論理削除・設計書 §4.4） */
  async function deleteComment(scheduleId: number, commentId: string) {
    return api(`${base(scheduleId)}/${commentId}`, { method: 'DELETE' })
  }

  /** PATCH .../comments/settings（スレッド開閉・SYSTEM_ADMIN/ADMIN/予定作成者限定・設計書 §4.4） */
  async function updateSettings(
    scheduleId: number,
    commentsEnabled: boolean,
  ): Promise<{ data: ScheduleCommentThreadSettingsResponse }> {
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
