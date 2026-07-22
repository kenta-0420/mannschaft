/**
 * F17.1 村機能 — 手書き型 ↔ 生成型の構造適合アサーション。
 *
 * 目的:
 *   `app/types/village.ts` の手書き型が Backend の実契約からドリフトしたら、
 *   実行時ではなく `npm run typecheck` で機械的に落とす。
 *
 * なぜ必要か:
 *   FE の API 呼び出しは `api<{ data: X }>(...)` という**型アサーション**であり、
 *   X が嘘（BE に存在しないフィールド名・形状）でも TypeScript は信じてしまう。
 *   2026-07 の村ドメイン契約不一致 17 件は、すべて「フィールド名・パス・形状の誤り」であり、
 *   本ファイルのキー集合照合で機械的に捕捉できる。
 *
 * 権威は生成型（`types/generated/index.ts` = openapi-typescript が `docs/openapi.json` から生成）。
 * 本ファイルが落ちたときに直すのは原則 `village.ts` の側であって、生成型ではない。
 * 生成型が古い疑いがあるときは `cd frontend && npm run generate:types` で再生成して裏を取ること。
 *
 * 本ファイルは型宣言のみで実行時コードを持たない（バンドルに載らない）。
 *
 * 検査していないもの（意図的な割り切り）:
 *   - null 許容の差。BE は nullable を `description: string | null` として返すが、
 *     生成型は `@Schema(required)` 未整備のため `description?: string` になる。
 *     `string | null` は `string | undefined` に代入できないため、値レベルの相互代入検査は成立しない。
 *     よって「フィールド名の集合」と「enum の値集合」に絞って検査する。
 *     今回の 17 件はこの網で 100% 捕捉できる。
 *   - optional / required の差（上と同根）。
 */

import type { SpringPage } from './api'
import type { components } from './generated'
import type {
  JoinRequestResponse,
  VillageCalendarEventCreateRequest,
  VillageCalendarEventListResponse,
  VillageCalendarEventLogCreateRequest,
  VillageCalendarEventLogResponse,
  VillageCalendarEventResponse,
  VillageCalendarEventUpdateRequest,
  VillageCreationRequestResponse,
  VillageEventArchiveResponse,
  VillageEventArchiveSourceType,
  VillageFestivalLivePostResponse,
  VillageFestivalLivePostTagRequest,
  VillageFestivalRsvpResponse,
  VillageFestivalRsvpStatus,
  VillageFestivalRsvpUpsertRequest,
  VillageMatchApplicationCreateRequest,
  VillageMatchApplicationResponse,
  VillageMatchApplicationReviewRequest,
  VillageMatchApplicationStatus,
  VillageMatchRecruitCategory,
  VillageMatchRecruitCreateRequest,
  VillageMatchRecruitListResponse,
  VillageMatchRecruitResponse,
  VillageMatchRecruitStatus,
  VillageMatchRecruitUpdateRequest,
  VillageMeetupAttendanceResponse,
  VillageMeetupAttendanceStatus,
  VillageMeetupAttendanceUpsertRequest,
  VillageMeetupCandidateDateAddRequest,
  VillageMeetupCandidateDateResponse,
  VillageMeetupCommentCreateRequest,
  VillageMeetupCommentResponse,
  VillageMeetupConfirmRequest,
  VillageMeetupCreateRequest,
  VillageMeetupResponse,
  VillageMeetupStatus,
  VillageMeetupTodoCreateRequest,
  VillageMeetupTodoResponse,
  VillageMeetupUpdateRequest,
  VillageMeetupVoteRequest,
  VillageMeetupVoteSummary,
  VillageMeetupVoteSummaryCandidate,
  VillageMeetupVoteType,
  VillageNewsletterFrequency,
  VillageNewsletterSendLogResponse,
  VillageNewsletterSetting,
  VillageNewsletterSettingsResponse,
  VillageNewsletterSettingUpdateRequest,
  VillageRequestStatus,
  VillageSubjectType,
} from './village'

type Schemas = components['schemas']

// =============================================================================
// アサーション用ヘルパー
// =============================================================================

/**
 * `T` が `true` でなければ「制約を満たさない」コンパイルエラーになる。
 * エラーメッセージに実際の型（差分のキー名や enum 値）がそのまま出る。
 */
type AssertTrue<T extends true> = T

/**
 * `Sub` が `Super` に代入可能なら `true`、そうでなければ `Sub` 自身を返す。
 * `[T] extends [U]` のタプル包みは union の分配（distributive conditional）を止めるため。
 */
type Assignable<Sub, Super> = [Sub] extends [Super] ? true : Sub

/** `H` のフィールド名のうち `G` に存在しないものの union。 */
type ExtraKeys<H, G> = Exclude<keyof H, keyof G>

/**
 * `H` と `G` のフィールド名集合が完全一致すれば `true`、
 * 食い違えば「余分な / 欠けているキー名」の union を返す。
 */
type SameKeys<H, G> = Assignable<ExtraKeys<H, G> | ExtraKeys<G, H>, never>

// =============================================================================
// A. 寄合 (meetup)
// =============================================================================

export type MeetupResponseKeysMatch = AssertTrue<SameKeys<VillageMeetupResponse, Schemas['MeetupResponse']>>

export type MeetupCandidateDateResponseKeysMatch = AssertTrue<
  SameKeys<VillageMeetupCandidateDateResponse, Schemas['MeetupCandidateDateResponse']>
>

export type MeetupVoteSummaryKeysMatch = AssertTrue<
  SameKeys<VillageMeetupVoteSummary, Schemas['MeetupVoteSummaryResponse']>
>

export type MeetupVoteSummaryCandidateKeysMatch = AssertTrue<
  SameKeys<VillageMeetupVoteSummaryCandidate, Schemas['CandidateDateSummary']>
>

export type MeetupCreateRequestKeysMatch = AssertTrue<
  SameKeys<VillageMeetupCreateRequest, Schemas['MeetupCreateRequest']>
>

export type MeetupUpdateRequestKeysMatch = AssertTrue<
  SameKeys<VillageMeetupUpdateRequest, Schemas['MeetupUpdateRequest']>
>

export type MeetupConfirmRequestKeysMatch = AssertTrue<
  SameKeys<VillageMeetupConfirmRequest, Schemas['MeetupConfirmRequest']>
>

export type MeetupVoteRequestKeysMatch = AssertTrue<
  SameKeys<VillageMeetupVoteRequest, Schemas['MeetupVoteRequest']>
>

export type MeetupCandidateDateAddRequestKeysMatch = AssertTrue<
  SameKeys<VillageMeetupCandidateDateAddRequest, Schemas['MeetupCandidateDateAddRequest']>
>

/** 候補日は object 配列 `{date, time?}`（#2357）。素の string 配列に戻したらここで落ちる。 */
export type MeetupCreateCandidateDatesShapeMatch = AssertTrue<
  Assignable<VillageMeetupCreateRequest['candidateDates'], NonNullable<Schemas['MeetupCreateRequest']['candidateDates']>>
>

export type MeetupStatusEnumMatch = AssertTrue<
  Assignable<VillageMeetupStatus, NonNullable<Schemas['MeetupResponse']['status']>>
>
export type MeetupStatusEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['MeetupResponse']['status']>, VillageMeetupStatus>
>

export type MeetupVoteTypeEnumMatch = AssertTrue<
  Assignable<VillageMeetupVoteType, Schemas['MeetupVoteRequest']['voteType']>
>
export type MeetupVoteTypeEnumExhaustive = AssertTrue<
  Assignable<Schemas['MeetupVoteRequest']['voteType'], VillageMeetupVoteType>
>

// -----------------------------------------------------------------------------
// A-2. F17.2 Wave1 ②寄合後半戦 — 出欠 / コメント / 宿題TODO
// -----------------------------------------------------------------------------

export type MeetupAttendanceResponseKeysMatch = AssertTrue<
  SameKeys<VillageMeetupAttendanceResponse, Schemas['MeetupAttendanceResponse']>
>

export type MeetupAttendanceUpsertRequestKeysMatch = AssertTrue<
  SameKeys<VillageMeetupAttendanceUpsertRequest, Schemas['MeetupAttendanceUpsertRequest']>
>

export type MeetupAttendanceStatusEnumMatch = AssertTrue<
  Assignable<VillageMeetupAttendanceStatus, NonNullable<Schemas['MeetupAttendanceResponse']['status']>>
>
export type MeetupAttendanceStatusEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['MeetupAttendanceResponse']['status']>, VillageMeetupAttendanceStatus>
>

export type MeetupCommentResponseKeysMatch = AssertTrue<
  SameKeys<VillageMeetupCommentResponse, Schemas['MeetupCommentResponse']>
>

export type MeetupCommentCreateRequestKeysMatch = AssertTrue<
  SameKeys<VillageMeetupCommentCreateRequest, Schemas['MeetupCommentCreateRequest']>
>

export type MeetupTodoResponseKeysMatch = AssertTrue<
  SameKeys<VillageMeetupTodoResponse, Schemas['MeetupTodoResponse']>
>

export type MeetupTodoCreateRequestKeysMatch = AssertTrue<
  SameKeys<VillageMeetupTodoCreateRequest, Schemas['MeetupTodoCreateRequest']>
>

// =============================================================================
// B. 歳時記カレンダー (calendar)
// =============================================================================

export type CalendarEventResponseKeysMatch = AssertTrue<
  SameKeys<VillageCalendarEventResponse, Schemas['CalendarEventResponse']>
>

export type CalendarEventListResponseKeysMatch = AssertTrue<
  SameKeys<VillageCalendarEventListResponse, Schemas['CalendarEventListResponse']>
>

export type CalendarEventCreateRequestKeysMatch = AssertTrue<
  SameKeys<VillageCalendarEventCreateRequest, Schemas['CalendarEventCreateRequest']>
>

export type CalendarEventUpdateRequestKeysMatch = AssertTrue<
  SameKeys<VillageCalendarEventUpdateRequest, Schemas['CalendarEventUpdateRequest']>
>

// -----------------------------------------------------------------------------
// B-2. F17.2 Wave1 ④歳時記×村史の年輪（去年の様子）
// -----------------------------------------------------------------------------

export type CalendarEventLogResponseKeysMatch = AssertTrue<
  SameKeys<VillageCalendarEventLogResponse, Schemas['CalendarEventLogResponse']>
>

export type CalendarEventLogCreateRequestKeysMatch = AssertTrue<
  SameKeys<VillageCalendarEventLogCreateRequest, Schemas['CalendarEventLogCreateRequest']>
>

// =============================================================================
// B-3. F17.2 Wave2 ③お祭りの参加レイヤー — RSVP / 実況
// =============================================================================

export type FestivalRsvpResponseKeysMatch = AssertTrue<
  SameKeys<VillageFestivalRsvpResponse, Schemas['FestivalRsvpResponse']>
>

export type FestivalRsvpUpsertRequestKeysMatch = AssertTrue<
  SameKeys<VillageFestivalRsvpUpsertRequest, Schemas['FestivalRsvpUpsertRequest']>
>

export type FestivalRsvpStatusEnumMatch = AssertTrue<
  Assignable<VillageFestivalRsvpStatus, NonNullable<Schemas['FestivalRsvpResponse']['status']>>
>
export type FestivalRsvpStatusEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['FestivalRsvpResponse']['status']>, VillageFestivalRsvpStatus>
>

export type FestivalLivePostResponseKeysMatch = AssertTrue<
  SameKeys<VillageFestivalLivePostResponse, Schemas['FestivalLivePostResponse']>
>

export type FestivalLivePostTagRequestKeysMatch = AssertTrue<
  SameKeys<VillageFestivalLivePostTagRequest, Schemas['FestivalLivePostTagRequest']>
>

// -----------------------------------------------------------------------------
// B-4. F17.2 Wave2 ⑦ 村史（行事アーカイブ）— BE 追補 #2448（2026-07-22 main 済み）
// -----------------------------------------------------------------------------

export type VillageEventArchiveResponseKeysMatch = AssertTrue<
  SameKeys<VillageEventArchiveResponse, Schemas['VillageEventArchiveResponse']>
>

export type VillageEventArchiveSourceTypeEnumMatch = AssertTrue<
  Assignable<VillageEventArchiveSourceType, NonNullable<Schemas['VillageEventArchiveResponse']['sourceType']>>
>
export type VillageEventArchiveSourceTypeEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['VillageEventArchiveResponse']['sourceType']>, VillageEventArchiveSourceType>
>

// =============================================================================
// C. 参加申請 / 村作成申請（Spring Page 露出）
// =============================================================================

export type JoinRequestResponseKeysMatch = AssertTrue<
  SameKeys<JoinRequestResponse, Schemas['JoinRequestResponse']>
>

export type VillageCreationRequestResponseKeysMatch = AssertTrue<
  SameKeys<VillageCreationRequestResponse, Schemas['VillageCreationRequestResponse']>
>

/** 参加申請一覧は Spring の `Page` 形状をそのまま露出する（意図的な設計）。 */
export type JoinRequestPageKeysMatch = AssertTrue<
  SameKeys<SpringPage<JoinRequestResponse>, Schemas['PageJoinRequestResponse']>
>

/** 村作成申請の運営向け一覧も `Page`。自分の申請一覧は素の配列（非対称は BE 側で固定済み）。 */
export type VillageCreationRequestPageKeysMatch = AssertTrue<
  SameKeys<SpringPage<VillageCreationRequestResponse>, Schemas['PageVillageCreationRequestResponse']>
>

export type SpringPageableKeysMatch = AssertTrue<
  SameKeys<SpringPage<JoinRequestResponse>['pageable'], Schemas['PageableObject']>
>

export type SpringSortKeysMatch = AssertTrue<
  SameKeys<SpringPage<JoinRequestResponse>['sort'], Schemas['SortObject']>
>

export type VillageRequestStatusEnumMatch = AssertTrue<
  Assignable<VillageRequestStatus, NonNullable<Schemas['JoinRequestResponse']['status']>>
>
export type VillageRequestStatusEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['JoinRequestResponse']['status']>, VillageRequestStatus>
>

export type VillageSubjectTypeEnumMatch = AssertTrue<
  Assignable<VillageSubjectType, NonNullable<Schemas['JoinRequestResponse']['subjectType']>>
>
export type VillageSubjectTypeEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['JoinRequestResponse']['subjectType']>, VillageSubjectType>
>

// =============================================================================
// D. 練習試合・募集 (match recruit)
// =============================================================================

export type MatchRecruitResponseKeysMatch = AssertTrue<
  SameKeys<VillageMatchRecruitResponse, Schemas['MatchRecruitResponse']>
>

/** 一覧は配列ではなく `{items, page, size, total}` エンベロープ。 */
export type MatchRecruitListResponseKeysMatch = AssertTrue<
  SameKeys<VillageMatchRecruitListResponse, Schemas['MatchRecruitListResponse']>
>

export type MatchRecruitCreateRequestKeysMatch = AssertTrue<
  SameKeys<VillageMatchRecruitCreateRequest, Schemas['MatchRecruitCreateRequest']>
>

export type MatchRecruitUpdateRequestKeysMatch = AssertTrue<
  SameKeys<VillageMatchRecruitUpdateRequest, Schemas['MatchRecruitUpdateRequest']>
>

export type MatchApplicationResponseKeysMatch = AssertTrue<
  SameKeys<VillageMatchApplicationResponse, Schemas['MatchApplicationResponse']>
>

export type MatchApplicationCreateRequestKeysMatch = AssertTrue<
  SameKeys<VillageMatchApplicationCreateRequest, Schemas['MatchApplicationCreateRequest']>
>

/** 応募審査の body は `action` ではなく `status`。 */
export type MatchApplicationReviewRequestKeysMatch = AssertTrue<
  SameKeys<VillageMatchApplicationReviewRequest, Schemas['MatchApplicationReviewRequest']>
>

/**
 * 審査 status は BE が ACCEPTED / REJECTED のみ許容するため、手書き型は生成型より意図的に狭い。
 * よって「手書き ⊆ 生成」の一方向のみ検査する（逆方向は成立しないのが正しい）。
 */
export type MatchApplicationReviewStatusEnumMatch = AssertTrue<
  Assignable<VillageMatchApplicationReviewRequest['status'], Schemas['MatchApplicationReviewRequest']['status']>
>

export type MatchRecruitCategoryEnumMatch = AssertTrue<
  Assignable<VillageMatchRecruitCategory, NonNullable<Schemas['MatchRecruitResponse']['category']>>
>
export type MatchRecruitCategoryEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['MatchRecruitResponse']['category']>, VillageMatchRecruitCategory>
>

export type MatchRecruitStatusEnumMatch = AssertTrue<
  Assignable<VillageMatchRecruitStatus, NonNullable<Schemas['MatchRecruitResponse']['status']>>
>
export type MatchRecruitStatusEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['MatchRecruitResponse']['status']>, VillageMatchRecruitStatus>
>

export type MatchApplicationStatusEnumMatch = AssertTrue<
  Assignable<VillageMatchApplicationStatus, NonNullable<Schemas['MatchApplicationResponse']['status']>>
>
export type MatchApplicationStatusEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['MatchApplicationResponse']['status']>, VillageMatchApplicationStatus>
>

// =============================================================================
// E. ニュースレター (newsletter) — 課題D（19 件目の契約不一致）
// =============================================================================
//
// これがドリフト再発防止の柵。以前の手書き型は「頻度を 1 つ選ぶ」フラット単一形状
// （`{userId, frequency, optedOut, ...}`）で、BE の「WEEKLY/MONTHLY をそれぞれ
// オン/オフする」配列形状（`{villageId, settings: [...], optedOut}`）と食い違い、
// GET の Select が常に空・PUT が必ず 400 になっていた。BE を正として寄せた。

/** 設定一覧は `{villageId, settings, optedOut}`。`userId` や単一 `frequency` はここで落ちる。 */
export type NewsletterSettingsResponseKeysMatch = AssertTrue<
  SameKeys<VillageNewsletterSettingsResponse, Schemas['NewsletterSettingsResponse']>
>

/** 配列要素は `{id, villageId, frequency, isEnabled, lastSentAt, nextScheduledAt, createdAt, updatedAt, version}`。 */
export type NewsletterSettingResponseKeysMatch = AssertTrue<
  SameKeys<VillageNewsletterSetting, Schemas['NewsletterSettingResponse']>
>

/** PUT の body は `{frequency, isEnabled}`（frequency のみは BE で 400）。 */
export type NewsletterSettingUpdateRequestKeysMatch = AssertTrue<
  SameKeys<VillageNewsletterSettingUpdateRequest, Schemas['NewsletterSettingUpdateRequest']>
>

/** 配信ログは `{id, newsletterId, sentAt, recipientCount, successCount, failureCount}`。 */
export type NewsletterSendLogResponseKeysMatch = AssertTrue<
  SameKeys<VillageNewsletterSendLogResponse, Schemas['NewsletterSendLogResponse']>
>

/** 頻度 enum は WEEKLY / MONTHLY の 2 値のみ（DAILY / NEVER を足すとここで落ちる）。 */
export type NewsletterFrequencyEnumMatch = AssertTrue<
  Assignable<VillageNewsletterFrequency, NonNullable<Schemas['NewsletterSettingResponse']['frequency']>>
>
export type NewsletterFrequencyEnumExhaustive = AssertTrue<
  Assignable<NonNullable<Schemas['NewsletterSettingResponse']['frequency']>, VillageNewsletterFrequency>
>
