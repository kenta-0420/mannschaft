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
 *
 * 契約の権威（この順に優先する）:
 *   1. Backend 実コード（backend/src/main/java/com/mannschaft/app/village/）と Flyway DDL
 *   2. app/types/generated/index.ts（openapi-typescript 自動生成）
 *   3. 本ファイル（手書き。権威ではない）
 *
 * 本ファイルの型は `village.contract.ts` の適合アサーションで生成型と機械的に照合される。
 * フィールド名・形状を変えたらそちらも合わせて更新すること。
 */

import type { SpringPage } from './api'

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

/**
 * §4.5 村参加申請一覧レスポンス。
 * BE: `GET /api/v1/villages/{villageId}/join-requests` は `ApiResponse<Page<JoinRequestResponse>>` を返す。
 *
 * Spring の `Page` をそのまま露出する意図的な設計（`VillageJoinRequestControllerTest#list_success` が
 * `$.data.content[0].status` で固定済み）。
 */
export type JoinRequestPageResponse = SpringPage<JoinRequestResponse>

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

/**
 * §4.6 村作成申請一覧（運営向け）。
 * BE: `GET /api/v1/admin/village-creation-requests` は `ApiResponse<Page<VillageCreationRequestResponse>>`。
 *
 * 自分の申請一覧（`GET /api/v1/me/village-creation-requests`）は素の配列
 * （`ApiResponse<List<...>>`）を返す。この非対称は MockMvc テストで固定済みのため、
 * 型でもそのまま非対称に表現する（無理に揃えないこと）。
 */
export type VillageCreationRequestPageResponse = SpringPage<VillageCreationRequestResponse>

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

/**
 * §4.5 参加申請一覧のページングクエリ。
 * 絞り込み `status` は `listJoinRequests` の第 2 引数で指定するため含めない。
 */
export interface JoinRequestListParams {
  page?: number
  size?: number
}

/** §4.6 管理者向け村作成申請一覧クエリ */
export interface CreationRequestListParams {
  status?: VillageRequestStatus
  page?: number
  size?: number
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
  /** 委任実行ユーザー表示名（Backend 解決後のスナップショット。解決不可なら null） */
  grantedByDisplayName: string | null
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
  /** 作成者表示名。BE は現状 null 固定（将来 PostingIdentity 連携で埋める予約フィールド） */
  createdByDisplayName: string | null
  createdAt: string
}

/**
 * 歳時記カレンダー月別一覧レスポンス。BE: `CalendarEventListResponse`。
 *
 * 一覧 API は配列ではなく本エンベロープを返す。
 */
export interface VillageCalendarEventListResponse {
  items: VillageCalendarEventResponse[]
  /** 対象年 */
  year: number
  /** 対象月（1〜12） */
  month: number
}

export interface VillageCalendarEventCreateRequest {
  title: string
  description?: string | null
  eventDate: string
  eventEndDate?: string | null
  /** BE は @NotNull のため必須 */
  isAnnualRecurring: boolean
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

/**
 * 歳時記カレンダー一覧クエリ。BE: `VillageCalendarController#listByMonth`。
 *
 * BE の @RequestParam は year / month のみ。期間指定・年中行事フィルタは存在しない。
 * 未指定時は BE 側が現在の年月を既定値にする。
 */
export interface VillageCalendarEventListParams {
  /** 対象年（未指定なら BE 側で現在年） */
  year?: number
  /** 対象月 1〜12（未指定なら BE 側で現在月） */
  month?: number
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
  /** 投稿者表示名（BE 解決後のスナップショット） */
  postedByDisplayName: string | null
  /** チーム代表として投稿した場合のチーム ID */
  postedByTeamId: number | null
  /** チーム名（BE 解決後のスナップショット） */
  postedByTeamName: string | null
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
  /** BE は @NotNull のため必須 (YYYY-MM-DD) */
  matchDate: string
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
  /** チームとして投稿する場合のチーム ID */
  postedByTeamId?: number | null
}

/**
 * 練習試合・審判募集一覧レスポンス。BE: `MatchRecruitListResponse`。
 *
 * 一覧 API は配列ではなく本エンベロープを返す（Spring の `Page` 形状ではない独自形状）。
 */
export interface VillageMatchRecruitListResponse {
  items: VillageMatchRecruitResponse[]
  page: number
  size: number
  total: number
}

/** 練習試合募集一覧クエリ */
export interface VillageMatchRecruitListParams {
  category?: VillageMatchRecruitCategory
  status?: VillageMatchRecruitStatus
  /** 試合日の絞り込み開始日 (YYYY-MM-DD) */
  fromDate?: string
  /** 試合日の絞り込み終了日 (YYYY-MM-DD) */
  toDate?: string
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
  recruitId: string
  applicantUserId: number
  /** 応募者表示名（BE 解決後のスナップショット） */
  applicantDisplayName: string | null
  /** チームとして応募した場合のチーム ID */
  applicantTeamId: number | null
  /** チーム名（BE 解決後のスナップショット） */
  applicantTeamName: string | null
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

/**
 * 練習試合募集への応募審査リクエスト。BE: `MatchApplicationReviewRequest`。
 *
 * BE は `action` ではなく `status` を受け取る。ACCEPTED / REJECTED のみ許容され、
 * それ以外は Service 層で VILLAGE_068 (MATCH_APPLICATION_INVALID_STATUS) となる。
 * 生成型の status は enum 全 4 値だが、ここは BE の実許容値に絞って表現する。
 */
export interface VillageMatchApplicationReviewRequest {
  status: Extract<VillageMatchApplicationStatus, 'ACCEPTED' | 'REJECTED'>
  reviewComment?: string | null
}

// -----------------------------------------------------------------------------
// F17 Phase 3 — 寄合 (village_meetups)
// -----------------------------------------------------------------------------

/**
 * 寄合の状態。BE: `entity.enums.VillageMeetupStatus`（3 値のみ）。
 * DDL: `V9.154__create_village_meetups.sql` — `DEFAULT 'PLANNING'`。
 */
export type VillageMeetupStatus = 'PLANNING' | 'CONFIRMED' | 'CANCELLED'

/** 寄合投票の選択肢。BE: `entity.enums.VillageMeetupVoteType`。 */
export type VillageMeetupVoteType = 'AVAILABLE' | 'MAYBE' | 'UNAVAILABLE'

/**
 * 寄合候補日。BE: `MeetupCandidateDateResponse`。
 *
 * 投票集計は本 DTO に含まれない。集計値は投票集計 API
 * （{@link VillageMeetupVoteSummary}）から取得すること。
 */
export interface VillageMeetupCandidateDateResponse {
  id: string
  meetupId: string
  /** 候補日 (YYYY-MM-DD) */
  candidateDate: string
  /** 候補の時刻 (HH:mm:ss)。任意・null は終日（#2357） */
  candidateTime: string | null
  /** 表示順 */
  sortOrder: number
}

/** 寄合。BE: `MeetupResponse`。 */
export interface VillageMeetupResponse {
  id: string
  villageId: string
  title: string
  description: string | null
  organizerUserId: number
  status: VillageMeetupStatus
  /** 確定日 (YYYY-MM-DD)。CONFIRMED 時のみセットされる */
  confirmedDate: string | null
  /** 確定時刻 (HH:mm:ss)。CONFIRMED かつ時刻ありの場合のみ。null は終日（#2357） */
  confirmedTime: string | null
  /** 集合場所（BE のフィールド名は venue ではなく location） */
  location: string | null
  createdAt: string
  /** 詳細取得時のみ詰められる。一覧取得時は null */
  candidateDates: VillageMeetupCandidateDateResponse[] | null
}

/** 寄合投票集計の候補日別内訳。BE: `MeetupVoteSummaryResponse.CandidateDateSummary`。 */
export interface VillageMeetupVoteSummaryCandidate {
  candidateDateId: string
  /** 候補日 (YYYY-MM-DD) */
  candidateDate: string
  /** 候補の時刻 (HH:mm:ss)。null は終日（#2357） */
  candidateTime: string | null
  availableCount: number
  maybeCount: number
  unavailableCount: number
}

/** 寄合投票集計。BE: `MeetupVoteSummaryResponse`。 */
export interface VillageMeetupVoteSummary {
  meetupId: string
  candidates: VillageMeetupVoteSummaryCandidate[]
}

/**
 * 寄合作成時の候補日 1 件。BE: `MeetupCandidateDateInput`（#2357）。
 *
 * `date` は必須、`time` は任意（省略 / null は終日）。
 */
export interface VillageMeetupCandidateDateInput {
  /** 候補日 (YYYY-MM-DD) */
  date: string
  /** 候補の時刻 (HH:mm)。任意・省略は終日（送信時は空なら省略する） */
  time?: string
}

/**
 * 寄合作成リクエスト。BE: `MeetupCreateRequest`。
 *
 * `candidateDates` は object 配列 `{date, time?}`。1〜30 件（#2357）。
 */
export interface VillageMeetupCreateRequest {
  title: string
  description?: string | null
  location?: string | null
  /** 候補日 object の配列 `{date, time?}` */
  candidateDates: VillageMeetupCandidateDateInput[]
}

/** 寄合更新リクエスト。BE: `MeetupUpdateRequest`（部分更新・全 optional）。 */
export interface VillageMeetupUpdateRequest {
  title?: string | null
  description?: string | null
  location?: string | null
}

/** 寄合確定リクエスト。BE: `MeetupConfirmRequest`。 */
export interface VillageMeetupConfirmRequest {
  candidateDateId: string
}

/**
 * 寄合投票リクエスト。BE: `MeetupVoteRequest`。
 *
 * 候補日は `PUT /villages/{villageId}/meetups/{meetupId}/candidate-dates/{candidateDateId}/vote`
 * のパス変数で渡すため body には含めない。BE にコメント欄は存在しない。
 * レスポンスは 204 No Content（投票 DTO は返らない）。
 */
export interface VillageMeetupVoteRequest {
  voteType: VillageMeetupVoteType
}

/** 寄合候補日追加リクエスト。BE: `MeetupCandidateDateAddRequest`。 */
export interface VillageMeetupCandidateDateAddRequest {
  /** 候補日 (YYYY-MM-DD) */
  candidateDate: string
  /** 候補の時刻 (HH:mm)。任意・省略 / null は終日（#2357） */
  candidateTime?: string | null
  sortOrder?: number | null
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

/**
 * ニュースレターの配信頻度。
 *
 * BE `VillageNewsletterFrequency` enum は **WEEKLY / MONTHLY の 2 値のみ**
 * （`backend/.../entity/enums/VillageNewsletterFrequency.java`）。
 * 以前ここにあった DAILY / NEVER は BE に存在せず、契約不一致の原因だったため撤去した。
 */
export type VillageNewsletterFrequency =
  | 'WEEKLY'
  | 'MONTHLY'

/**
 * ニュースレター設定の 1 行（BE: `NewsletterSettingResponse`）。
 *
 * 村ごとに WEEKLY / MONTHLY それぞれ 0〜1 件。設定が未作成の頻度は
 * {@link VillageNewsletterSettingsResponse.settings} に含まれない。
 */
export interface VillageNewsletterSetting {
  id: string
  villageId: string
  frequency: VillageNewsletterFrequency
  isEnabled: boolean
  lastSentAt: string | null
  nextScheduledAt: string | null
  createdAt: string | null
  updatedAt: string | null
  version: number
}

/**
 * 村のニュースレター設定一覧レスポンス（BE: `NewsletterSettingsResponse`）。
 *
 * `settings` は WEEKLY / MONTHLY の 0〜2 件。`optedOut` は
 * **閲覧ユーザー個人**の受信停止状態（村レベルの全停止ではない）。
 */
export interface VillageNewsletterSettingsResponse {
  villageId: string
  settings: VillageNewsletterSetting[]
  optedOut: boolean
}

/**
 * ニュースレター設定更新リクエスト（BE: `NewsletterSettingUpdateRequest`）。
 *
 * 指定頻度（frequency）の設定を isEnabled で upsert する。
 * BE は単一の {@link VillageNewsletterSetting}（upsert した 1 行）を返す。
 */
export interface VillageNewsletterSettingUpdateRequest {
  frequency: VillageNewsletterFrequency
  isEnabled: boolean
}

/** ニュースレター配信ログ（BE: `NewsletterSendLogResponse`）。 */
export interface VillageNewsletterSendLogResponse {
  id: string
  newsletterId: string
  sentAt: string
  recipientCount: number
  successCount: number
  failureCount: number
}

// -----------------------------------------------------------------------------
// F17.1 Phase 2 — ロビー在席インジケーター (lobby presence)
// -----------------------------------------------------------------------------

/**
 * ロビー在席メンバー。BE: `LobbyPresenceResponse.PresenceMember`。
 *
 * 以前は §4.10 ブロックにも同名の別形状（displayName / avatarR2Key / joinedAt を持つ）が
 * 宣言されており、TypeScript の interface 宣言マージで両者が合体して
 * 「存在しないフィールドにアクセスしてもコンパイルが通る」状態になっていた。
 * BE 実装に存在しない側を削除して本宣言に一本化した。
 */
export interface PresenceMember {
  userId: number
  nickname: string
}

/** ロビー在席レスポンス (GET /api/v1/villages/{villageId}/lobby/presence) */
export interface LobbyPresenceResponse {
  members: PresenceMember[]
  activeCount: number
}
