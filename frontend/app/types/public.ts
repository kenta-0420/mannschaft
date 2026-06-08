/**
 * F19.1 公開ページ用フロントエンド型定義（手動定義）。
 *
 * バックエンド DTO（{@code com.mannschaft.app.publicview.dto}）と完全一致させる。
 * 将来 openapi-typescript の再生成で
 * {@code frontend/app/types/generated/index.ts} に統合する予定。
 *
 * Defense in Depth 原則:
 * - 認証済み API の型と完全分離（共用しない）
 * - PII 系フィールド（email/phone/firstName/lastName 等）は型レベルで存在しない
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.3
 */

/** Spring の {@code Page<T>} 形式レスポンス（Phase 1 ではページング方式採用）。 */
export interface SpringPage<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  first: boolean
  last: boolean
  empty: boolean
  numberOfElements: number
}

/** 公開 DTO 用のスコープ参照（チーム / 組織）。 */
export interface PublicScopeRef {
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  scopeName: string
}

/** 段階開示済みの投稿者識別情報（公開 DTO）。 */
export interface PublicAuthorIdentity {
  displayLabel: string
  avatarUrl: string | null
  teamAffiliationVisible: boolean
  isAnonymized: boolean
}

/** F19.1 公開チームページ用の抑制版レスポンス。 */
export interface PublicTeamResponse {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: string | null
  prefecture: string | null
  city: string | null
  iconUrl: string | null
  bannerUrl: string | null
  homepageUrl: string | null
  establishedDate: string | null
  establishedDatePrecision: string | null
  philosophy: string | null
  memberCount: number | null
  mapEmbedUrl: string | null
  /** F19.1 Phase 7: タイムライン投稿の公開設定 */
  timelinePostsPublic: boolean
}

/** F19.1 公開組織ページ用の抑制版レスポンス。 */
export interface PublicOrganizationResponse {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  orgType: string | null
  prefecture: string | null
  city: string | null
  iconUrl: string | null
  bannerUrl: string | null
  homepageUrl: string | null
  establishedDate: string | null
  establishedDatePrecision: string | null
  philosophy: string | null
  mapEmbedUrl: string | null
  /** F19.1 Phase 7: タイムライン投稿の公開設定 */
  timelinePostsPublic: boolean
  /** F19.1 Phase 7: イベントの公開設定 */
  publicEventsEnabled: boolean
}

// ─── F19.1 Phase 7: タイムライン投稿・イベント 公開 API 型 ───

/**
 * F19.1 Phase 7: 公開タイムライン投稿サマリー DTO。
 *
 * エンドポイント: GET /api/v1/public/teams/{teamId}/timeline-posts
 *                GET /api/v1/public/organizations/{orgId}/timeline-posts
 */
export interface PublicTimelinePostResponse {
  id: string
  content: string
  authorDisplayName: string
  authorIconUrl: string | null
  createdAt: string
}

/**
 * F19.1 Phase 7: 公開イベント DTO。
 *
 * エンドポイント: GET /api/v1/public/teams/{teamId}/events
 *                GET /api/v1/public/organizations/{orgId}/events
 */
export interface PublicEventResponse {
  id: string
  title: string
  startDate: string
  endDate: string | null
  location: string | null
  description: string | null
  visibility: string
  createdAt: string
}

/**
 * F19.1 Phase 7: 管理者用 公開設定更新リクエスト。
 *
 * エンドポイント: PATCH /api/v1/admin/teams/{teamId}/public-settings
 *                PATCH /api/v1/admin/organizations/{orgId}/public-settings
 */
export interface UpdatePublicSettingsRequest {
  timelinePostsPublic?: boolean
  publicEventsEnabled?: boolean
}

/** F19.1 公開投稿一覧用の summary DTO。 */
export interface PublicPostSummary {
  sourceType: 'BLOG_POST' | string
  sourceId: number
  title: string
  excerpt: string | null
  author: PublicAuthorIdentity
  scope: PublicScopeRef
  publishedAt: string
}

/** F19.1 公開投稿詳細用の DTO。 */
export interface PublicPostDetail {
  sourceType: 'BLOG_POST' | string
  sourceId: number
  title: string
  bodyHtml: string
  author: PublicAuthorIdentity
  scope: PublicScopeRef
  publishedAt: string
}

// ─── F19.1 Phase 6-B: 公開投稿コメント API 型 ───

/**
 * F19.1 Phase 6-B: 公開投稿コメント DTO。
 *
 * エンドポイント: GET /api/v1/public/blog-posts/{postId}/comments
 * 未ログインでも閲覧可能。
 */
export interface PublicPostComment {
  commentId: string
  authorId: number
  authorDisplayName: string
  content: string
  createdAt: string
}

// ─── F19.1 Phase 4: 公開チーム・組織検索 API 型 ───

/** F19.1 公開チーム検索結果 1 件の DTO。 */
export interface PublicTeamSearchResult {
  id: number
  /** チーム UUID（/public/teams/{uuid} ルートに使用） */
  publicId: string
  name: string
  iconUrl: string | null
  memberCount: number
  lastPostDate: string | null
}

/** F19.1 公開組織検索結果 1 件の DTO。 */
export interface PublicOrganizationSearchResult {
  id: number
  name: string
  iconUrl: string | null
  memberCount: number
  lastPostDate: string | null
}

// ─── F19.1 Phase 2: Admin 向け supporter_name_disclosure 切替 API 型 ───

/** supporter_name_disclosure の値。 */
export type NameDisclosureMode = 'DISPLAY_NAME' | 'REAL_NAME'

/** Admin PATCH リクエスト DTO。 */
export interface SupporterNameDisclosurePatchRequest {
  mode: NameDisclosureMode
  confirmed: boolean
}

/** Admin PATCH レスポンス DTO。 */
export interface SupporterNameDisclosureResponse {
  currentMode: NameDisclosureMode
  /** 同値更新の場合は null。 */
  changedAt: string | null
}

/** 変更履歴 1 件の DTO。 */
export interface NameDisclosureChangeLogResponse {
  id: string
  oldMode: NameDisclosureMode
  newMode: NameDisclosureMode
  confirmed: boolean
  changedBy: number
  changedAt: string
}

// ─── F19.1 Phase 2: public_visible トグル UI 用型 ───

/**
 * 個別投稿の public_visible を切り替える PATCH リクエスト DTO。
 *
 * TODO: バックエンド API は未実装（Phase 3 以降で実装予定）。
 * エンドポイント: PATCH /api/v1/admin/posts/{postId}/public-visible
 */
export interface PublicVisiblePatchRequest {
  /** true: 公開 / false: 非公開 */
  publicVisible: boolean
}

// ─── F19.1 Phase 6: 個人プロフィール公開 API 型 ───

/**
 * F19.1 Phase 6: 公開ユーザープロフィール取得レスポンス。
 *
 * エンドポイント: GET /api/v1/public/users/{userId}
 * {@code public_profile_enabled = true} のユーザーのみ返却される。
 *
 * Defense in Depth 原則: PII（氏名・メール等）は含まない。
 */
export interface PublicUserProfile {
  userId: number
  displayName: string
  avatarUrl: string | null
  /** ISO date string "YYYY-MM-DD" — バックエンドの LocalDate に対応 */
  memberSince: string
}

/**
 * F19.1 Phase 6: 公開ユーザーの投稿サマリー。
 *
 * エンドポイント: GET /api/v1/public/users/{userId}/posts
 * visibility=PUBLIC かつ status=PUBLISHED かつ public_visible=true の投稿のみ。
 */
export interface PublicUserPostSummary {
  postId: number
  title: string
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeName: string
  /** チーム ID / 組織 ID の文字列表現（リンク生成用） */
  scopeId: string
  /** ISO datetime string — バックエンドの LocalDateTime に対応 */
  createdAt: string
}
