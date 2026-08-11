/**
 * F03.16 予定コメントスレッド — FE 型定義。
 *
 * 設計書: docs/features/F03.16_schedule_comment_thread.md §4
 *
 * 第六隊（契約是正）にて、BE 側の OpenAPI スキーマ名衝突（`CommentResponse` 等が todo ドメインの
 * 定義に上書きされる問題）を `@Schema(name = "ScheduleCommentResponse")` 等の明示付与で解消した
 * （`backend/.../schedule/dto/ScheduleCommentResponse.java` 等）。これにより `docs/openapi.json` に
 * 予定コメント専用のスキーマが正しく載るようになったため、本ファイルは手書き型を廃し、
 * 生成型（`~/types/generated`）を再輸出するだけの薄いエイリアス集にした（真実のソースは生成型）。
 */
import type { components } from '~/types/generated'

/** コメント投稿者。退会・匿名化済みは `author` 自体が null（BE 側でインスタンスが作られない）。 */
export type ScheduleCommentAuthor = NonNullable<RawScheduleCommentResponse['author']>

type RawScheduleCommentResponse = components['schemas']['ScheduleCommentResponse']

/** `parentId` / `rootId` / `body` / `author` / `replies` は null 許容のまま任意扱いにする5フィールド。 */
type NullableCommentFields = 'parentId' | 'rootId' | 'body' | 'author' | 'replies'

/**
 * 予定コメント1件のレスポンス表現（設計書 §4.2 / §4.3）。
 *
 * 生成型（`components['schemas']['ScheduleCommentResponse']`）は springdoc の既定に従い全フィールドが
 * optional になるが、BE は `@Builder` で毎回全フィールドを埋めて返すため、`parentId` / `rootId` /
 * `body` / `author` / `replies`（= null 許容フィールド。トゥームストーン等で意図的に null になりうる）
 * 以外は常に存在する契約として扱ってよい。既存コンポーネントの `comment.id` 等の非 null 前提の参照を
 * 壊さないよう、その5フィールド以外を `Required` にする。
 *
 * `parentId` / `rootId` / `body` / `author` / `replies` は null 許容。
 * トゥームストーン（`isDeleted=true`）は `body` / `author` が必ず null（§5.3）。
 */
export type ScheduleCommentResponse =
  Required<Omit<RawScheduleCommentResponse, NullableCommentFields>>
  & Pick<RawScheduleCommentResponse, NullableCommentFields>

/** POST /api/v1/schedules/{scheduleId}/comments のリクエストボディ（設計書 §4.2）。 */
export type CreateScheduleCommentRequest = components['schemas']['CreateScheduleCommentRequest']

/** PATCH /api/v1/schedules/{scheduleId}/comments/{commentId} のリクエストボディ（設計書 §4.4）。 */
export type UpdateScheduleCommentRequest = components['schemas']['UpdateScheduleCommentRequest']

/** `PagedResponse.PageMeta`（total/page/size/totalPages の4フィールド固定・設計書 §4.3）。 */
export type SchedulePageMeta = components['schemas']['PageMeta']

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
