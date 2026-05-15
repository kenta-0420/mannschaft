/**
 * F17.1 村機能 — 型定義
 *
 * Backend DTO (com.mannschaft.app.village.dto / .entity.enums) を一次ソースとする。
 * 設計書: docs/features/F17.1_village_community.md §4
 *
 * 注意:
 *   - 個人特定情報（reporterUserId 等）はバックエンドが返さない設計のため、
 *     フロント型でも一切定義しない。
 *   - 日時はすべて ISO 8601 文字列。
 *   - UUIDv7 は string で扱う（Java 側は java.util.UUID）。
 */

// =============================================================================
// Enum 型 — Backend (entity.enums.*) と完全一致
// =============================================================================

export type VillageType = 'OFFICIAL' | 'COMMUNITY'

export type VillageJoinPolicy = 'FREE' | 'APPROVAL'

/** Backend: PUBLIC / UNLISTED の 2 値。設計書 §3.5 の PRIVATE は採用されていない */
export type VillageVisibility = 'PUBLIC' | 'UNLISTED'

export type VillageSubjectType = 'USER' | 'TEAM' | 'ORGANIZATION'

export type VillageRole = 'HEADMAN' | 'ELDER' | 'VILLAGER' | 'VISITOR'

export type VillageRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'

export type VillageReportTargetType = 'POST' | 'MESSAGE' | 'MEMBERSHIP' | 'VILLAGE'

/** Backend: PENDING / REVIEWING / RESOLVED / DISMISSED */
export type VillageReportStatus = 'PENDING' | 'REVIEWING' | 'RESOLVED' | 'DISMISSED'

/** 村横断フィードの種別（VillageFeedItemResponse.type） */
export type VillageFeedItemType = 'TIMELINE' | 'LOBBY'

/** 村内検索結果アイテムの種別 */
export type VillageInternalSearchItemType = 'POST' | 'MESSAGE' | 'MEMBER'

/** 村内検索 POST 種別 */
export type VillageInternalSearchPostKind = 'BULLETIN_THREAD' | 'TIMELINE_POST'

// =============================================================================
// Response 型
// =============================================================================

/** §4.1.2 / §4.2 村レスポンス */
export interface VillageResponse {
  id: string
  slug: string
  name: string
  description: string | null
  type: VillageType
  joinPolicy: VillageJoinPolicy
  visibility: VillageVisibility
  category: string | null
  iconR2Key: string | null
  coverR2Key: string | null
  guidelineMd: string | null
  memberCount: number
  isOfficial: boolean
  isMember: boolean
  isPinned: boolean
  myRole: VillageRole | null
  archivedAt: string | null
  createdAt: string
  updatedAt: string
  version: number | null
}

/** §4.2 検索結果 */
export interface VillageSearchResponse {
  content: VillageResponse[]
  totalElements: number
  page: number
  size: number
}

/** §4.4 メンバーシップ */
export interface MembershipResponse {
  id: string
  subjectType: VillageSubjectType
  subjectId: number
  displayName: string | null
  role: VillageRole
  joinedAt: string
  isBanned: boolean
  /** 参加時に 30 村以上で立つソフト警告フラグ */
  participationWarn: boolean
}

export interface MembershipListResponse {
  content: MembershipResponse[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** §4.5 村参加申請 */
export interface JoinRequestResponse {
  id: string
  villageId: string
  subjectType: VillageSubjectType
  subjectId: number
  message: string | null
  status: VillageRequestStatus
  /** 審査を行った村長/長老のメンバーシップ ID（UUIDv7） */
  reviewedBy: string | null
  reviewedAt: string | null
  reviewComment: string | null
  createdAt: string
}

/** §4.6 村作成申請 */
export interface VillageCreationRequestResponse {
  id: string
  requesterUserId: number
  name: string
  slug: string
  category: string | null
  purpose: string | null
  status: VillageRequestStatus
  reviewedBy: number | null
  reviewedAt: string | null
  reviewComment: string | null
  createdVillageId: string | null
  createdAt: string
}

/** §4.7 村ニックネーム */
export interface VillageNicknameResponse {
  nickname: string
  avatarR2Key: string | null
  bio: string | null
  lastChangedAt: string | null
  changeCountThisMonth: number
  monthlyLimit: number
}

/** §4.8 投稿主体エントリ */
export interface PostingIdentityResponse {
  subjectType: VillageSubjectType
  subjectId: number
  displayName: string
  canPostAs: boolean
}

export interface PostingIdentityListResponse {
  identities: PostingIdentityResponse[]
}

/** §4.9 ピン */
export interface PinResponse {
  id: string
  villageId: string
  villageName: string
  villageIconUrl: string | null
  sortOrder: number
  pinnedAt: string
}

export interface PinListResponse {
  items: PinResponse[]
  count: number
  maxLimit: number
}

/** §4.10 ロビー */
export interface LobbyChannelResponse {
  chatChannelId: number
  channelType: string
  villageId: string
  todayThreadId: string | null
}

export interface DailyThreadResponse {
  id: string
  villageId: string
  chatChannelId: number
  messageCount: number
  summary: string | null
  createdAt: string
}

export interface DailyThreadListResponse {
  threads: DailyThreadResponse[]
}

/** §4.11 通報（reporterUserId は意図的に含めない） */
export interface ReportResponse {
  id: string
  targetType: VillageReportTargetType
  targetRefId: string
  reasonCode: string
  status: VillageReportStatus
  /** 常に "ANONYMOUS_VILLAGER" */
  reporterDisplayName: string
  reportedAt: string
  handlerAction: string | null
  handledAt: string | null
}

/** §4.12 横断フィードのピン村サマリ */
export interface VillagePinnedSummaryResponse {
  id: string
  name: string
  iconR2Key: string | null
  unreadCount: number
}

export interface VillageFeedItemResponse {
  type: VillageFeedItemType
  villageId: string
  villageName: string
  postId: number | null
  messageId: number | null
  snippet: string
  createdAt: string
}

export interface VillageFeedResponse {
  feed: VillageFeedItemResponse[]
  pinnedVillages: VillagePinnedSummaryResponse[]
}

/** §4.13 村内検索 */
export interface VillageInternalSearchItemResponse {
  type: VillageInternalSearchItemType
  id: string
  postKind: VillageInternalSearchPostKind | null
  title: string | null
  snippet: string | null
  nickname: string | null
  avatarR2Key: string | null
  channelId: number | null
  createdAt: string | null
}

export interface VillageInternalSearchResponse {
  items: VillageInternalSearchItemResponse[]
  page: number
  size: number
  total: number
}

// =============================================================================
// Request 型
// =============================================================================

export interface VillageCreateRequest {
  slug: string
  name: string
  description?: string | null
  type: VillageType
  joinPolicy: VillageJoinPolicy
  visibility: VillageVisibility
  category?: string | null
  guidelineMd?: string | null
}

export interface VillageUpdateRequest {
  name?: string | null
  description?: string | null
  joinPolicy?: VillageJoinPolicy | null
  visibility?: VillageVisibility | null
  category?: string | null
  iconR2Key?: string | null
  coverR2Key?: string | null
  guidelineMd?: string | null
}

export interface MembershipJoinRequest {
  subjectType: VillageSubjectType
  subjectId: number
}

export interface RoleChangeRequest {
  role: VillageRole
}

export interface MembershipBanRequest {
  reason?: string | null
}

export interface JoinRequestCreateRequest {
  subjectType: VillageSubjectType
  subjectId: number
  message?: string | null
}

export interface JoinRequestReviewRequest {
  reviewComment?: string | null
}

export interface VillageCreationRequestCreateRequest {
  name: string
  slug: string
  category?: string | null
  purpose?: string | null
  joinPolicy: VillageJoinPolicy
  visibility: VillageVisibility
  type: VillageType
  guidelineMd?: string | null
}

export interface VillageCreationRequestReviewRequest {
  reviewComment?: string | null
}

export interface ReportCreateRequest {
  targetType: VillageReportTargetType
  targetRefId: string
  reasonCode: string
  detail?: string | null
}

export interface ReportResolveRequest {
  note?: string | null
}

export interface PinOrderUpdateRequest {
  orderedVillageIds: string[]
}

export interface VillageNicknameUpdateRequest {
  nickname?: string | null
  avatarR2Key?: string | null
  bio?: string | null
}

// =============================================================================
// クエリパラメータ型
// =============================================================================

/** §4.2 村検索クエリ */
export interface VillageSearchParams {
  q?: string
  type?: VillageType
  category?: string
  joinPolicy?: VillageJoinPolicy
  page?: number
  size?: number
  sort?: string
}

/** §4.4 メンバー一覧クエリ */
export interface MembershipListParams {
  role?: VillageRole
  page?: number
  size?: number
}

/** §4.6 管理者向け村作成申請一覧クエリ */
export interface CreationRequestListParams {
  status?: VillageRequestStatus
}

/** §4.11 通報一覧クエリ */
export interface ReportListParams {
  status?: VillageReportStatus
}

/** §4.13 村内検索クエリ */
export interface VillageInternalSearchParams {
  q: string
  type?: VillageInternalSearchItemType
  page?: number
  size?: number
}
