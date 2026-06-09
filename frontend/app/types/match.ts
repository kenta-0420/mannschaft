/**
 * F08.10 試合記録・分析 — 型エイリアス（生成型優先・CLAUDE.md）
 *
 * 生成型 `app/types/generated/index.ts`（openapi-typescript 自動生成）の
 * `components['schemas']['*']` を**薄く再エクスポート**する。生成型と重複する
 * 独自定義はしない（生成型が正本）。UX 用の補助ユニオンのみ手動で定義する。
 *
 * BE API パス（04_frontend_and_ux.md §G.4 / docs/openapi.json）:
 *   GET    /api/v1/organizations/{orgId}/teams/{teamId}/matches              listMatches
 *   POST   /api/v1/organizations/{orgId}/teams/{teamId}/matches              createMatch
 *   GET    /api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}    getMatch
 *   PATCH  /api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}    updateMatch
 *   DELETE /api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}    deleteMatch
 *   PATCH  .../matches/{matchId}/status                                      changeStatus
 *   PATCH  .../matches/{matchId}/score                                       finalizeScore
 *   PATCH  .../matches/{matchId}/recording-mode                             changeRecordingMode
 *   GET/POST   /api/v1/organizations/{orgId}/matches/{matchId}/events        events
 *   PATCH/DELETE .../events/{eventId}
 *   GET    /api/v1/organizations/{orgId}/matches/{matchId}/appearances
 *   GET    /api/v1/organizations/{orgId}/users/{userId}/match-stats          getUserStats
 *   GET    .../users/{userId}/match-stats/timeline                           getUserTimeline
 *   GET    .../users/{userId}/teams/{teamId}/match-stats                     getUserTeamStats
 *   GET    /api/v1/organizations/{orgId}/teams/{teamId}/match-stats          getTeamStats
 */
import type { components, operations } from '~/types/generated'

type Schemas = components['schemas']

// ===== レスポンス DTO（生成型の再エクスポート） =====

/** 試合サマリ（一覧 listMatches の各行） */
export type MatchSummaryResponse = Schemas['MatchSummaryResponse']
/** ページング付き試合サマリ一覧（listMatches のレスポンス本体） */
export type PagedResponseMatchSummaryResponse = Schemas['PagedResponseMatchSummaryResponse']
/**
 * 試合詳細（getMatch / createMatch / updateMatch / changeStatus / finalizeScore / recordingMode のレスポンス）。
 *
 * BE クラス名は `match.dto.MatchResponse` だが、tournament(リーグ)側の同名 `MatchResponse` と
 * OpenAPI スキーマ名が衝突するため、BE 側で `@Schema(name="MatchDetailResponse")` に分離した。
 * 生成型でも `MatchDetailResponse` が正本（F08.10 3-B 根治）。
 */
export type MatchResponse = Schemas['MatchDetailResponse']
/** イベント */
export type MatchEventResponse = Schemas['MatchEventResponse']
/** イベント一覧（スコア整合チェック付き） */
export type MatchEventsResponse = Schemas['MatchEventsResponse']
/** 出場記録 */
export type PlayerAppearanceResponse = Schemas['PlayerAppearanceResponse']
/** 個人キャリア統計 */
export type UserMatchStatsResponse = Schemas['UserMatchStatsResponse']
/** 個人試合タイムライン 1 行 */
export type UserMatchTimelineEntry = Schemas['UserMatchTimelineEntry']
/** チーム統計 */
export type TeamMatchStatsResponse = Schemas['TeamMatchStatsResponse']
/** ページングメタ */
export type PageMeta = Schemas['PageMeta']

// ===== リクエスト DTO（生成型の再エクスポート） =====

export type CreateMatchRequest = Schemas['CreateMatchRequest']
export type UpdateMatchRequest = Schemas['UpdateMatchRequest']
// BE クラス名は match.dto.ChangeStatusRequest だが、他ドメイン（property/incident/translation）の
// 同名 DTO と OpenAPI スキーマ名が衝突するため @Schema(name="MatchChangeStatusRequest") に分離（3-B 根治）。
export type ChangeStatusRequest = Schemas['MatchChangeStatusRequest']
export type FinalizeScoreRequest = Schemas['FinalizeScoreRequest']
export type ChangeRecordingModeRequest = Schemas['ChangeRecordingModeRequest']
export type MatchEventRequest = Schemas['MatchEventRequest']

// ===== UX 補助ユニオン =====
// 生成型は各プロパティを optional + 文字列リテラルユニオンで持つが、フォーム選択肢や
// バッジ表示で「全候補値の配列」を回したい場面のための補助。生成型から導出する
// （NonNullable で optional/undefined を除去）ので、生成型と二重定義にならない。

/** 競技（生成型 CreateMatchRequest.sport から導出） */
export type Sport = NonNullable<CreateMatchRequest['sport']>
/** 試合種別（生成型 CreateMatchRequest.kind から導出） */
export type MatchKind = NonNullable<CreateMatchRequest['kind']>
/** ホーム/アウェイ（生成型 CreateMatchRequest.homeAway から導出） */
export type HomeAway = NonNullable<CreateMatchRequest['homeAway']>
/** ステータス変更の受付値（生成型 ChangeStatusRequest.status から導出） */
export type MatchStatusInput = NonNullable<ChangeStatusRequest['status']>
/** 一覧サマリのステータス表示値（生成型 MatchSummaryResponse.status から導出） */
export type MatchSummaryStatus = NonNullable<MatchSummaryResponse['status']>
/** イベント種別（生成型 MatchEventRequest.eventType から導出・全競技の器） */
export type MatchEventType = NonNullable<MatchEventRequest['eventType']>
/** ピリオド（生成型 MatchEventRequest.period から導出） */
export type MatchPeriod = NonNullable<MatchEventRequest['period']>
/** イベントのチーム側（生成型 MatchEventRequest.teamSide から導出） */
export type TeamSide = NonNullable<MatchEventRequest['teamSide']>

// ===== サッカー固有の語彙（sports/01_soccer.md §5/§7・i18n キー用） =====
// 理由コード・ポジションは BE では自由文字列カラムだが、UI の選択肢列挙として固定する。
// i18n の短ラベルは match.card_reason.* / match.position.* で 6 言語表示する（§9）。

/** 警告の理由コード（JFA 標準・§5.1） */
export type CautionCode = 'C1' | 'C2' | 'C3' | 'C4' | 'C5' | 'C6' | 'C7' | 'C8'
/** 退場の理由コード（JFA 標準・§5.2） */
export type SendingOffCode = 'S1' | 'S2' | 'S3' | 'S4' | 'S5' | 'S6' | 'CS'
/** カード理由コード（警告・退場の和集合） */
export type CardReasonCode = CautionCode | SendingOffCode
/** サッカーのポジション大分類（§7） */
export type SoccerPosition = 'GK' | 'DF' | 'MF' | 'FW'

/** 試合種別の全候補（フィルタ/作成フォームの選択肢） */
export const MATCH_KINDS: readonly MatchKind[] = [
  'PRACTICE',
  'FRIENDLY',
  'TOURNAMENT',
  'LEAGUE',
] as const

/** 一覧フィルタのステータス候補 */
export const MATCH_SUMMARY_STATUSES: readonly MatchSummaryStatus[] = [
  'SCHEDULED',
  'IN_PROGRESS',
  'COMPLETED',
  'POSTPONED',
  'CANCELLED',
] as const

/**
 * 一覧フィルタ・取得クエリのパラメータ（listMatches）。
 * 生成型 operations['listMatches'] の query を正本とする（status は MatchSummaryStatus 系）。
 */
export type ListMatchesParams = NonNullable<
  operations['listMatches']['parameters']['query']
>

/** 集計取得クエリのパラメータ（getUserStats / getTeamStats 等・生成型 operations から導出） */
export type MatchStatsParams = NonNullable<
  operations['getUserStats']['parameters']['query']
>

/** 個人タイムライン取得クエリのパラメータ（getUserTimeline・生成型 operations から導出） */
export type UserTimelineParams = NonNullable<
  operations['getUserTimeline']['parameters']['query']
>
