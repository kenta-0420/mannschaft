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
  scopeId: number
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
