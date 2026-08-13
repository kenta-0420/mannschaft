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
 *
 * ## null 許容フィールドを手で補っている理由（是正隊 2026-08-13）
 *
 * BE 側は `@Schema(nullable = true)` で該当フィールドの null 許容性を明示している
 * （`ScheduleCommentResponse`/`CommentAuthorResponse`/`MentionCandidateResponse`/
 * `ThreadMetaResponse` 各 DTO）が、本プロジェクトの springdoc/swagger-core（OpenAPI 3.1 出力）
 * パイプラインには、`@Schema(nullable = true)` を生成物（`docs/openapi.json`）へ一切反映しない
 * 既存の欠陥がある（`nullable`・`type` 配列のいずれの表現でも出力されないことを、
 * カスタムスキーマ変換の直接検証・`springdoc.api-docs.version=openapi_3_1` の明示指定を含め
 * 複数の手段で実測確認済み。既存の `FeatureUpsertRequest#addonPriceJpy` 等、他ドメインの
 * `@Schema(nullable = true)` も同様に無効化されているプロジェクト全体の既知の穴であり、
 * 本戦役の範囲を超えるため別課題として切り出す）。
 *
 * そのため生成型（`components['schemas']['...']`）は該当フィールドが単に
 * `T | undefined`（optional・null 非許容）のままになる。BE の実装
 * （`ScheduleCommentService#toResponse`・`loadAuthors` 等）を読むと、これらのフィールドは
 * 実際に `null` を返しうる（トップレベルコメントの `parentId`/`rootId`・削除済みコメントの
 * `body`/`author`・アバター未設定ユーザーの `avatarUrl`・`canPost=true` 時の `canPostReason` 等）。
 * BE の実装が語る実態を偽らないよう、本ファイルで `| null` を明示的に補う
 * （生成型パイプラインの欠陥が解消され次第、この手当ては不要になり削除できる）。
 */
import type { components } from '~/types/generated'

type RawScheduleCommentResponse = components['schemas']['ScheduleCommentResponse']
type RawCommentAuthorResponse = components['schemas']['CommentAuthorResponse']
type RawMentionCandidateResponse = components['schemas']['MentionCandidateResponse']
type RawThreadMetaResponse = components['schemas']['ThreadMetaResponse']

/**
 * コメント投稿者。退会・匿名化済みは `author` 自体が null（BE 側でインスタンスが作られない）。
 *
 * `avatarUrl` は未設定のユーザーで null になる（`NameResolverService#resolveUserAvatarUrls` は
 * 解決不能なユーザーを結果 Map から除外するため、`ScheduleCommentService#loadAuthors` の結果が
 * null になる）。
 */
export type ScheduleCommentAuthor = Omit<RawCommentAuthorResponse, 'avatarUrl'>
  & { avatarUrl: RawCommentAuthorResponse['avatarUrl'] | null }

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
 * `parentId` / `rootId` / `body` / `author` / `replies` は null 許容（`| null` を明示付与。
 * 生成型パイプラインの既知の欠陥については本ファイル冒頭の説明を参照）。
 * トゥームストーン（`isDeleted=true`）は `body` / `author` が必ず null（§5.3）。
 */
export type ScheduleCommentResponse =
  Required<Omit<RawScheduleCommentResponse, NullableCommentFields>>
  & {
    parentId: RawScheduleCommentResponse['parentId'] | null
    rootId: RawScheduleCommentResponse['rootId'] | null
    body: RawScheduleCommentResponse['body'] | null
    author: ScheduleCommentAuthor | null
    replies: ScheduleCommentResponse[] | null
  }

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
/**
 * スレッド状態レスポンス（設計書 §4.4）。`canPostReason` は `canPost=false` のときのみ非 null
 * （`CLOSED`/`CANCELLED`/`ROLE`）。`canPost=true` のときは応答から省略される（null 許容）。
 */
export type ScheduleCommentThreadMeta = Omit<RawThreadMetaResponse, 'canPostReason'>
  & { canPostReason: RawThreadMetaResponse['canPostReason'] | null }

export type ScheduleCommentThreadSettingsRequest = components['schemas']['ThreadSettingsRequest']
export type ScheduleCommentThreadSettingsResponse = components['schemas']['ThreadSettingsResponse']

/** メンション候補ユーザー（設計書 §4.4）。`avatarUrl` は `ScheduleCommentAuthor` と同様 null 許容。 */
export type ScheduleCommentMentionCandidate = Omit<RawMentionCandidateResponse, 'avatarUrl'>
  & { avatarUrl: RawMentionCandidateResponse['avatarUrl'] | null }
