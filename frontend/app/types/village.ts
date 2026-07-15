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

/**
 * 村掲示板の公開範囲（F17.1 §3.12.1）。
 * 村本体の VillageVisibility（検索可否）とは独立した概念。
 * - PUBLIC: 非メンバー（ログイン済みユーザー）でも掲示板を閲覧可
 * - MEMBERS_ONLY: 村メンバーのみ閲覧可（デフォルト）
 */
export type VillageBulletinVisibility = 'PUBLIC' | 'MEMBERS_ONLY'

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
  /** 掲示板公開範囲（F17.1 §3.12.1）。未指定時は MEMBERS_ONLY */
  bulletinVisibility: VillageBulletinVisibility
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

/** §4.10.5 ロビー在席メンバー */
export interface PresenceMember {
  userId: number
  displayName: string
  avatarR2Key: string | null
  joinedAt: string
}

/** §4.10.5 ロビー在席状況レスポンス */
export interface LobbyPresenceResponse {
  count: number
  members: PresenceMember[]
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
  /** 掲示板公開範囲（F17.1 §3.12.1）。PUBLIC / MEMBERS_ONLY */
  bulletinVisibility?: VillageBulletinVisibility | null
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
  type: VillageType
  guidelineMd?: string | null
  guidelineAgreedAt: string
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

// =============================================================================
// Phase 2 拡張型 — 紋・代表委任・歳時記カレンダー・お祭り・練習試合募集
// =============================================================================
//
// 設計書: docs/features/F17.1_village_community.md §2.2 / §3.11 / §13.2
//
// 重要事項:
//   - Phase 1 既存 type は変更せず、Phase 2 では本ブロックに追記する。
//   - 主キーはバックエンド側で UUIDv7（BINARY(16)）採用のため string 表現。
//   - 実 API パスは Backend Controller 完成時に微調整の可能性あり。
//   - `villages.monsho_r2_key` は VillageResponse の拡張で扱うが、
//     既存 VillageResponse は変更禁止のため、別途 VillageMonshoResponse 等で扱う
//     設計とする（VillageHeader 表示用に `village.iconR2Key` を流用しないこと）。
//

// -----------------------------------------------------------------------------
// 代表委任 (village_representatives) — §3.11
// -----------------------------------------------------------------------------

/** 村代表委任レスポンス */
export interface VillageRepresentativeResponse {
  id: string
  villageId: string
  /** 対象メンバーシップ ID（TEAM/ORGANIZATION の村加入） */
  membershipId: string
  representativeUserId: number
  /** 代表として表示する名前（Backend 解決後のスナップショット） */
  representativeDisplayName: string | null
  /** 委任を発行したチーム/組織 ADMIN の user_id */
  grantedByUserId: number
  grantedAt: string
  revokedAt: string | null
  /** 任意メモ */
  note: string | null
}

/** 代表委任発行リクエスト */
export interface VillageRepresentativeGrantRequest {
  membershipId: string
  representativeUserId: number
  note?: string | null
}

/** 代表委任取消リクエスト */
export interface VillageRepresentativeRevokeRequest {
  note?: string | null
}

// -----------------------------------------------------------------------------
// 歳時記カレンダー (village_calendar_events) — §13.2
// -----------------------------------------------------------------------------

/** 歳時記カレンダーイベント */
export interface VillageCalendarEventResponse {
  id: string
  villageId: string
  title: string
  description: string | null
  /** 開催日 (YYYY-MM-DD) */
  eventDate: string
  /** 終了日 (任意・複数日イベント時) */
  eventEndDate: string | null
  /** 年中行事（毎年繰り返し）フラグ */
  isAnnualRecurring: boolean
  /** 絵文字アイコン（例: 🎏 🌸 🎃） */
  iconEmoji: string | null
  /** 色（#RRGGBB） */
  colorHex: string | null
  createdByUserId: number
  createdAt: string
}

export interface VillageCalendarEventCreateRequest {
  title: string
  description?: string | null
  eventDate: string
  eventEndDate?: string | null
  isAnnualRecurring?: boolean
  iconEmoji?: string | null
  colorHex?: string | null
}

export interface VillageCalendarEventUpdateRequest {
  title?: string | null
  description?: string | null
  eventDate?: string | null
  eventEndDate?: string | null
  isAnnualRecurring?: boolean | null
  iconEmoji?: string | null
  colorHex?: string | null
}

/** 歳時記カレンダー一覧クエリ */
export interface VillageCalendarEventListParams {
  /** 期間絞り込み開始日 (YYYY-MM-DD) */
  from?: string
  /** 期間絞り込み終了日 (YYYY-MM-DD) */
  to?: string
  /** 年中行事のみフィルタ */
  annualOnly?: boolean
}

// -----------------------------------------------------------------------------
// お祭り (village_festivals) — §13.2
// -----------------------------------------------------------------------------

export type VillageFestivalStatus = 'SCHEDULED' | 'ACTIVE' | 'ENDED' | 'CANCELLED'

export interface VillageFestivalResponse {
  id: string
  villageId: string
  title: string
  description: string | null
  startsAt: string
  endsAt: string
  bannerR2Key: string | null
  /** テーマカラー (#RRGGBB) */
  themeColorHex: string | null
  status: VillageFestivalStatus
  createdByUserId: number
  createdAt: string
}

export interface VillageFestivalCreateRequest {
  title: string
  description?: string | null
  startsAt: string
  endsAt: string
  bannerR2Key?: string | null
  themeColorHex?: string | null
}

export interface VillageFestivalUpdateRequest {
  title?: string | null
  description?: string | null
  startsAt?: string | null
  endsAt?: string | null
  bannerR2Key?: string | null
  themeColorHex?: string | null
}

// -----------------------------------------------------------------------------
// 練習試合募集 (village_match_recruits) — §13.2
// -----------------------------------------------------------------------------

export type VillageMatchRecruitCategory =
  | 'PRACTICE_MATCH'
  | 'REFEREE'
  | 'VENUE'
  | 'OTHER'

export type VillageMatchRecruitStatus =
  | 'OPEN'
  | 'CLOSED'
  | 'FULFILLED'
  | 'CANCELLED'

export interface VillageMatchRecruitResponse {
  id: string
  villageId: string
  postedByUserId: number
  /** チーム代表として投稿した場合のチーム ID */
  postedByTeamId: number | null
  category: VillageMatchRecruitCategory
  title: string
  description: string | null
  /** 試合日 (YYYY-MM-DD) */
  matchDate: string | null
  /** 開始時刻 (HH:mm) */
  matchTimeStart: string | null
  /** 終了時刻 (HH:mm) */
  matchTimeEnd: string | null
  venue: string | null
  /** 必要人数 / 必要チーム数 */
  requiredCount: number | null
  /** 連絡方法（自由記述） */
  contactMethod: string | null
  /** 応募締切 */
  applicationDeadline: string | null
  status: VillageMatchRecruitStatus
  createdAt: string
}

export interface VillageMatchRecruitCreateRequest {
  category: VillageMatchRecruitCategory
  title: string
  description?: string | null
  matchDate?: string | null
  matchTimeStart?: string | null
  matchTimeEnd?: string | null
  venue?: string | null
  requiredCount?: number | null
  contactMethod?: string | null
  applicationDeadline?: string | null
  /** チームとして投稿する場合のチーム ID */
  postedByTeamId?: number | null
}

export interface VillageMatchRecruitUpdateRequest {
  category?: VillageMatchRecruitCategory | null
  title?: string | null
  description?: string | null
  matchDate?: string | null
  matchTimeStart?: string | null
  matchTimeEnd?: string | null
  venue?: string | null
  requiredCount?: number | null
  contactMethod?: string | null
  applicationDeadline?: string | null
}

/** 練習試合募集一覧クエリ */
export interface VillageMatchRecruitListParams {
  category?: VillageMatchRecruitCategory
  status?: VillageMatchRecruitStatus
  page?: number
  size?: number
}

// -----------------------------------------------------------------------------
// 練習試合応募 (village_match_applications)
// -----------------------------------------------------------------------------

export type VillageMatchApplicationStatus =
  | 'PENDING'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'WITHDRAWN'

export interface VillageMatchApplicationResponse {
  id: string
  villageId: string
  recruitId: string
  applicantUserId: number
  /** チームとして応募した場合のチーム ID */
  applicantTeamId: number | null
  message: string | null
  status: VillageMatchApplicationStatus
  reviewedByUserId: number | null
  reviewedAt: string | null
  reviewComment: string | null
  createdAt: string
}

export interface VillageMatchApplicationCreateRequest {
  message?: string | null
  /** チームとして応募する場合のチーム ID */
  applicantTeamId?: number | null
}

export interface VillageMatchApplicationReviewRequest {
  reviewComment?: string | null
}

// -----------------------------------------------------------------------------
// F17 Phase 3 — 寄合 (village_meetups)
// -----------------------------------------------------------------------------

export type VillageMeetupStatus =
  | 'DRAFT'
  | 'OPEN'
  | 'CONFIRMED'
  | 'CANCELLED'
  | 'CLOSED'

export type VillageMeetupVoteType =
  | 'YES'
  | 'NO'
  | 'MAYBE'

/** 寄合候補日 */
export interface VillageMeetupCandidateDateResponse {
  id: string
  meetupId: string
  candidateDate: string
  candidateTimeStart: string | null
  candidateTimeEnd: string | null
  voteCountYes: number
  voteCountNo: number
  voteCountMaybe: number
  isConfirmed: boolean
}

/** 寄合 */
export interface VillageMeetupResponse {
  id: string
  villageId: string
  organizerUserId: number
  title: string
  description: string | null
  venue: string | null
  status: VillageMeetupStatus
  confirmedDateId: string | null
  candidateDates: VillageMeetupCandidateDateResponse[]
  participantCount: number
  createdAt: string
  updatedAt: string
}

/** 寄合投票 */
export interface VillageMeetupVoteResponse {
  id: string
  meetupId: string
  candidateDateId: string
  voterUserId: number
  voteType: VillageMeetupVoteType
  comment: string | null
  votedAt: string
}

/** 寄合投票集計 */
export interface VillageMeetupVoteSummary {
  meetupId: string
  totalVoters: number
  candidateSummaries: VillageMeetupCandidateDateResponse[]
}

/** 寄合作成リクエスト */
export interface VillageMeetupCreateRequest {
  title: string
  description?: string | null
  venue?: string | null
  candidateDates: Array<{
    candidateDate: string
    candidateTimeStart?: string | null
    candidateTimeEnd?: string | null
  }>
}

/** 寄合更新リクエスト */
export interface VillageMeetupUpdateRequest {
  title?: string
  description?: string | null
  venue?: string | null
}

/** 寄合投票リクエスト */
export interface VillageMeetupVoteRequest {
  candidateDateId: string
  voteType: VillageMeetupVoteType
  comment?: string | null
}

/** 寄合候補日追加リクエスト */
export interface VillageMeetupCandidateDateAddRequest {
  candidateDate: string
  candidateTimeStart?: string | null
  candidateTimeEnd?: string | null
}

/** 寄合一覧クエリ */
export interface VillageMeetupListParams {
  status?: VillageMeetupStatus
  page?: number
  size?: number
}

// -----------------------------------------------------------------------------
// F17 Phase 3 — 村史 (village_chronicles)
// -----------------------------------------------------------------------------

/**
 * 村史の TOP トピック 1 件。
 *
 * BE: `ChronicleResponse.TopicItem`（`{ name, count }`）。
 */
export interface VillageChronicleTopicItem {
  name: string
  count: number
}

/** 村史エントリ */
export interface VillageChronicleResponse {
  id: string
  villageId: string
  /**
   * YYYY-MM-DD 形式（BE は `LocalDate` / OpenAPI `format: date`）。
   * 当該月の 1 日に正規化済みのため、常に `YYYY-MM-01` が返る。
   */
  yearMonth: string
  generatedAt: string
  postCount: number
  newMemberCount: number
  topics: VillageChronicleTopicItem[]
}

// -----------------------------------------------------------------------------
// F17 Phase 3 — ご縁スコア (village_serendipity_scores)
// -----------------------------------------------------------------------------

/** ご縁スコア */
export interface VillageSerendipityScoreResponse {
  villageId: string
  userId: number
  /** 0.0 〜 1.0 */
  score: number
  rank: number | null
  lastComputedAt: string
}

/** ご縁スコアランキング */
export interface VillageSerendipityRankingResponse {
  items: VillageSerendipityScoreResponse[]
  total: number
}

// -----------------------------------------------------------------------------
// F17 Phase 3 — 巡礼 (village_pilgrimage_*)
// -----------------------------------------------------------------------------

/** 巡礼推薦 */
export interface VillagePilgrimageRecommendationResponse {
  id: string
  userId: number
  recommendedVillageId: string
  recommendedAt: string
  /** 推薦根拠 */
  reason: string | null
  visited: boolean
  visitedAt: string | null
}

/** 訪問記録リクエスト（recommendationId はパス変数に移行済み） */
export interface VillagePilgrimageVisitRecordRequest {
  villageId: string
}

/** 訪問記録 */
export interface VillagePilgrimageVisitResponse {
  id: string
  userId: number
  villageId: string
  visitedAt: string
}

// -----------------------------------------------------------------------------
// F17 Phase 3 — ニュースレター (village_newsletter_*)
// -----------------------------------------------------------------------------

export type VillageNewsletterFrequency =
  | 'DAILY'
  | 'WEEKLY'
  | 'MONTHLY'
  | 'NEVER'

/** ニュースレター設定 */
export interface VillageNewsletterSettingsResponse {
  userId: number
  villageId: string | null
  frequency: VillageNewsletterFrequency
  optedOut: boolean
  lastSentAt: string | null
  nextScheduledAt: string | null
}

/** ニュースレター設定更新リクエスト */
export interface VillageNewsletterSettingsRequest {
  frequency: VillageNewsletterFrequency
}

/** ニュースレター購読停止レスポンス */
export interface VillageNewsletterOptOutResponse {
  userId: number
  optedOut: boolean
  optedOutAt: string | null
}

/** ニュースレター配信ログ */
export interface VillageNewsletterSendLogResponse {
  id: string
  villageId: string
  frequency: string
  sentAt: string
  recipientCount: number
}

// -----------------------------------------------------------------------------
// F17.1 Phase 2 — ロビー在席インジケーター (lobby presence)
// -----------------------------------------------------------------------------

/** ロビー在席メンバー */
export interface PresenceMember {
  userId: number
  nickname: string
}

/** ロビー在席レスポンス (GET /api/v1/villages/{villageId}/lobby/presence) */
export interface LobbyPresenceResponse {
  members: PresenceMember[]
  activeCount: number
}
