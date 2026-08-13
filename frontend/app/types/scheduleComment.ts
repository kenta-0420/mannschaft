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
 * ## なぜこの層が要るのか（CMP-034: `@Schema(nullable = true)` が効かない）
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
 * ## 生成型と手書き型の境界はここ【必読・再発防止】
 *
 * BE は実際には該当フィールドを `@JsonInclude(NON_NULL)` で「省略」して返す（= 実行時の生の値は
 * `undefined`）。一方で本プロジェクトの慣習・既存テストフィクスチャは、値が無いことを
 * `null`（`undefined` ではなく）で表現する（Vue の一般的な流儀に合わせている）。
 * この2つの型の世界を **1箇所だけ** で変換する: {@link useScheduleComments}
 * （`~/composables/useScheduleComments.ts`）が API から生の応答（本ファイルの `Raw*` 型
 * ＝ `undefined` の世界）を受け取った直後に、本ファイルの `to*` 正規化関数へ通し、
 * 手書き型（`| null` の世界）へ変換してから呼び出し元へ返す。
 *
 * **この境界より内側（composable 実装）だけが `Raw*` 型・`undefined` を扱ってよい。
 * それより外側（コンポーネント・テスト）は本ファイルの手書き型・`null` だけを扱う。**
 * 逆向きに崩す（コンポーネント側で `undefined` を使う・composable の外へ `Raw*` を漏らす）と、
 * 型の境界が散り、片方を直すたびに反対側が赤くなる事故を再発する
 * （2026-08-13 是正時に実際に発生した事故: 手書き型を `| null` に寄せたところ、
 * 生成型（`| undefined`）をそのまま受け渡していた境界のない箇所が新たに衝突した）。
 *
 * リクエスト（`Create.../Update...Request`）側は BE 契約どおり「未指定＝キー省略
 * （`undefined`）」が正しいため、本ファイルではリクエスト型を書き換えない。
 * `null`（`parentId: null` 等、コンポーネント側の自然な書き方）を受け取って `undefined` へ
 * 変換するのも同じ境界（composable）の責務とする。
 *
 * （生成型パイプラインの欠陥が解消され次第、この手当ては不要になり削除できる。）
 */
import type { components } from '~/types/generated'

// ─────────────────────────────────────────────────────────────────
// Raw*: 生成型（openapi-typescript）そのもの。undefined の世界。
// composable の実装内部（API 呼び出し直後）以外から参照してはならない。
// ─────────────────────────────────────────────────────────────────
export type RawScheduleCommentResponse = components['schemas']['ScheduleCommentResponse']
export type RawCommentAuthorResponse = components['schemas']['CommentAuthorResponse']
export type RawMentionCandidateResponse = components['schemas']['MentionCandidateResponse']
export type RawThreadMetaResponse = components['schemas']['ThreadMetaResponse']
export type RawPageMeta = components['schemas']['PageMeta']

// ─────────────────────────────────────────────────────────────────
// 手書き型（アプリ内の正準）。null の世界。composable の外側は常にこちらを扱う。
// ─────────────────────────────────────────────────────────────────

/**
 * コメント投稿者。退会・匿名化済みは `author` 自体が null（BE 側でインスタンスが作られない）。
 *
 * `avatarUrl` は未設定のユーザーで null になる（`NameResolverService#resolveUserAvatarUrls` は
 * 解決不能なユーザーを結果 Map から除外するため、`ScheduleCommentService#loadAuthors` の結果が
 * null になる）。`userId`/`displayName` は BE が必ず埋めるため必須項目として扱う。
 */
export interface ScheduleCommentAuthor {
  userId: number
  displayName: string
  avatarUrl: string | null
}

/**
 * 予定コメント1件のレスポンス表現（設計書 §4.2 / §4.3）。
 *
 * BE は `@Builder` で毎回全フィールドを埋めて返すため、`parentId` / `rootId` / `body` /
 * `author` / `replies`（= null 許容フィールド。トゥームストーン等で意図的に null になりうる）
 * 以外は常に存在する契約として扱ってよい。
 *
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

/**
 * `PagedResponse.PageMeta`（total/page/size/totalPages の4フィールド固定・設計書 §4.3）。
 * BE は必ず4フィールドとも埋めて返すため（生成型が springdoc の既定で optional になっているだけ）、
 * 手書き型では必須項目として扱う。
 */
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

/**
 * スレッド状態レスポンス（設計書 §4.4）。`canPostReason` は `canPost=false` のときのみ非 null
 * （`CLOSED`/`CANCELLED`/`ROLE`）。`canPost=true` のときは応答から省略される（null 許容）。
 */
export interface ScheduleCommentThreadMeta {
  scheduleId: number
  commentsEnabled: boolean
  canPost: boolean
  canPostReason: string | null
}

/** メンション候補ユーザー（設計書 §4.4）。`avatarUrl` は `ScheduleCommentAuthor` と同様 null 許容。 */
export interface ScheduleCommentMentionCandidate {
  userId: number
  displayName: string
  avatarUrl: string | null
}

// ─────────────────────────────────────────────────────────────────
// 正規化関数（Raw* → 手書き型）。composable がフェッチ直後にのみ呼ぶ。
// ─────────────────────────────────────────────────────────────────

export function toScheduleCommentAuthor(raw: RawCommentAuthorResponse): ScheduleCommentAuthor {
  return {
    userId: raw.userId ?? 0,
    displayName: raw.displayName ?? '',
    avatarUrl: raw.avatarUrl ?? null,
  }
}

export function toScheduleCommentResponse(raw: RawScheduleCommentResponse): ScheduleCommentResponse {
  return {
    id: raw.id ?? '',
    scheduleId: raw.scheduleId ?? 0,
    parentId: raw.parentId ?? null,
    rootId: raw.rootId ?? null,
    depth: raw.depth ?? 0,
    body: raw.body ?? null,
    isEdited: raw.isEdited ?? false,
    isDeleted: raw.isDeleted ?? false,
    replyCount: raw.replyCount ?? 0,
    author: raw.author ? toScheduleCommentAuthor(raw.author) : null,
    canEdit: raw.canEdit ?? false,
    canDelete: raw.canDelete ?? false,
    createdAt: raw.createdAt ?? '',
    updatedAt: raw.updatedAt ?? '',
    replies: raw.replies ? raw.replies.map(toScheduleCommentResponse) : null,
  }
}

export function toPageMeta(raw: RawPageMeta): SchedulePageMeta {
  return {
    total: raw.total ?? 0,
    page: raw.page ?? 0,
    size: raw.size ?? 0,
    totalPages: raw.totalPages ?? 0,
  }
}

export function toScheduleCommentPagedResponse(raw: {
  data?: RawScheduleCommentResponse[]
  meta?: RawPageMeta
}): ScheduleCommentPagedResponse {
  return {
    data: (raw.data ?? []).map(toScheduleCommentResponse),
    meta: toPageMeta(raw.meta ?? {}),
  }
}

export function toScheduleCommentThreadMeta(raw: RawThreadMetaResponse): ScheduleCommentThreadMeta {
  return {
    scheduleId: raw.scheduleId ?? 0,
    commentsEnabled: raw.commentsEnabled ?? false,
    canPost: raw.canPost ?? false,
    canPostReason: raw.canPostReason ?? null,
  }
}

export function toScheduleCommentMentionCandidate(
  raw: RawMentionCandidateResponse,
): ScheduleCommentMentionCandidate {
  return {
    userId: raw.userId ?? 0,
    displayName: raw.displayName ?? '',
    avatarUrl: raw.avatarUrl ?? null,
  }
}

// ─────────────────────────────────────────────────────────────────
// リクエスト型・衝突のない応答型は生成型をそのまま再輸出する（null 補正は不要）。
// ─────────────────────────────────────────────────────────────────

/** POST /api/v1/schedules/{scheduleId}/comments のリクエストボディ（設計書 §4.2）。 */
export type CreateScheduleCommentRequest = components['schemas']['CreateScheduleCommentRequest']

/** PATCH /api/v1/schedules/{scheduleId}/comments/{commentId} のリクエストボディ（設計書 §4.4）。 */
export type UpdateScheduleCommentRequest = components['schemas']['UpdateScheduleCommentRequest']

export type ScheduleCommentThreadSettingsRequest = components['schemas']['ThreadSettingsRequest']
export type ScheduleCommentThreadSettingsResponse = components['schemas']['ThreadSettingsResponse']
