/**
 * F03.16 予定コメントスレッド — FE 型定義。
 *
 * 設計書: docs/features/F03.16_schedule_comment_thread.md §4
 *
 * ⚠️ **手書き型である理由（重要）**: `CommentResponse` / `CreateCommentRequest` /
 * `UpdateCommentRequest` は BE の複数ドメイン（todo・schedule 等）で同名クラスが使われており、
 * springdoc の OpenAPI スキーマ名はデフォルトで単純クラス名を使うため、`docs/openapi.json` の
 * `components.schemas.CommentResponse` 等は**別ドメイン（todo）の定義で上書きされてしまっている**
 * （実測: `CommentResponse` は `todoId`/`user` を持つ todo 側の形になっており、`id`(uuid)/`scheduleId`/
 * `parentId`/`author`/`canEdit`/`canDelete` 等を持つ schedule 側の実際のレスポンスとは一致しない。
 * `CreateCommentRequest`/`UpdateCommentRequest` も同様に `parentId`/`mentionedUserIds` が欠落した
 * 別クラスの定義で上書きされている）。
 *
 * これは BE 側の OpenAPI スキーマ名衝突バグであり、本 PR のスコープ外（BE 修正禁止の指示のため）。
 * 実際の BE DTO（`backend/src/main/java/com/mannschaft/app/schedule/dto/*.java`）を直接読んで
 * 手書きした契約型をここに置く。BE 側で `@Schema(name = "ScheduleCommentResponse")` 等の明示的な
 * スキーマ名を振って衝突を解消したら、generate:types 後にこのファイルを削除し生成型へ移行すること。
 *
 * 衝突していない `ThreadMetaResponse` / `ThreadSettingsRequest` / `ThreadSettingsResponse` /
 * `MentionCandidateResponse` は生成型（`~/types/generated`）をそのまま再輸出する。
 */
import type { components } from '~/types/generated'

/** コメント投稿者。退会・匿名化済みは `author` 自体が null（BE 側でインスタンスが作られない）。 */
export interface ScheduleCommentAuthor {
  userId: number
  displayName: string
  avatarUrl: string | null
}

/**
 * 予定コメント1件のレスポンス表現（設計書 §4.2 / §4.3）。
 * `parentId` / `rootId` / `body` / `author` / `replies` は null 許容。
 * トゥームストーン（`isDeleted=true`）は `body` / `author` が必ず null（§5.3）。
 */
export interface ScheduleCommentResponse {
  id: string
  scheduleId: number
  parentId: string | null
  rootId: string | null
  depth: number
  body: string | null
  isEdited: boolean
  isDeleted: boolean
  replyCount: number
  author: ScheduleCommentAuthor | null
  canEdit: boolean
  canDelete: boolean
  createdAt: string
  updatedAt: string
  /** 返信配列（最大3件同梱）。返信行（depth=1）では常に null（無限ネスト禁止）。 */
  replies: ScheduleCommentResponse[] | null
}

/** POST /api/v1/schedules/{scheduleId}/comments のリクエストボディ（設計書 §4.2）。 */
export interface CreateScheduleCommentRequest {
  body: string
  parentId?: string | null
  mentionedUserIds?: number[]
}

/** PATCH /api/v1/schedules/{scheduleId}/comments/{commentId} のリクエストボディ（設計書 §4.4）。 */
export interface UpdateScheduleCommentRequest {
  body: string
}

/** `PagedResponse.PageMeta`（total/page/size/totalPages の4フィールド固定・設計書 §4.3）。 */
export interface SchedulePageMeta {
  total: number
  page: number
  size: number
  totalPages: number
}

/** GET .../comments・GET .../{commentId}/replies の応答エンベロープ。 */
export interface ScheduleCommentPagedResponse {
  data: ScheduleCommentResponse[]
  meta: SchedulePageMeta
}

// 衝突していないスキーマは生成型をそのまま再輸出する。
export type ScheduleCommentThreadMeta = components['schemas']['ThreadMetaResponse']
export type ScheduleCommentThreadSettingsRequest = components['schemas']['ThreadSettingsRequest']
export type ScheduleCommentThreadSettingsResponse = components['schemas']['ThreadSettingsResponse']
export type ScheduleCommentMentionCandidate = components['schemas']['MentionCandidateResponse']
