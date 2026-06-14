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

/** 競技（生成型 CreateMatchRequest.sport から導出・全 6 競技） */
export type Sport = NonNullable<CreateMatchRequest['sport']>

/**
 * 連続時間制（CONTINUOUS_TIME）の競技（サッカー/フットサル/バスケ・04 §G.16）。
 *
 * 旧前向きユニオン（'SOCCER' | 'FUTSAL' | 'BASKETBALL' の手書き）を、BE OpenAPI 再生成で
 * 生成型 `Sport` が全 6 競技（SOCCER/FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO）へ拡張されたため、
 * **生成型から導出**する形へ返済した。セット制（VOLLEYBALL）/ターン制（SHOGI/GO）を除いた残り
 * （＝連続時間制）が `LiveSport`。BE がさらに連続時間制競技を追加した場合は除外リストを更新する。
 */
export type LiveSport = Exclude<Sport, 'VOLLEYBALL' | 'SHOGI' | 'GO'>
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
/** フットサルのポジション大分類（sports/02_futsal.md §7） */
export type FutsalPosition = 'GK' | 'FIXO' | 'ALA' | 'PIVO'
/** バスケのポジション大分類（sports/03_basketball.md §7） */
export type BasketballPosition = 'PG' | 'SG' | 'SF' | 'PF' | 'C'

// ===== バスケ固有イベント種別・ファウルコード（sports/03_basketball.md §2/§5） =====
// BE OpenAPI 再生成により、生成型 `MatchEventType`（MatchEventRequest.eventType）へバスケ固有値
// （FIELD_GOAL_2 等）が統合済みとなった。旧前向きユニオン（手書きの BasketballEventType 列挙）を、
// 生成型から `Extract` で導出する形へ返済する（生成型が正本・二重定義を解消・any/手書き列挙なし）。

/** バスケ固有の event_type（生成型 MatchEventType から Extract で導出・§2）。 */
export type BasketballEventType = Extract<
  MatchEventType,
  | 'FIELD_GOAL_2'
  | 'FIELD_GOAL_3'
  | 'FREE_THROW'
  | 'SHOT_MISS'
  | 'REBOUND'
  | 'STEAL'
  | 'BLOCK'
  | 'TURNOVER'
  | 'PERSONAL_FOUL'
  | 'TECHNICAL_FOUL'
  | 'FOUL_OUT'
>

/**
 * 全競技の event_type を保持するカタログ用ユニオン（FE 側の器）。
 * 生成型 `MatchEventType` がバスケ固有値を含むため、現状 `MatchEventType` と等価となった
 * （旧前向きユニオンの `| BasketballEventType` は生成型統合により冗長になり撤去）。
 * カタログ定数・競技別シート・i18n キーの型付けに用いる。
 */
export type CatalogEventType = MatchEventType

/** バスケのファウル理由コード（FIBA 標準・sports/03_basketball.md §5）。 */
export type BasketballFoulCode = 'PF' | 'SF' | 'OF' | 'TF' | 'UF' | 'DF'

/** 競技横断のポジション語彙ユニオン（i18n 引きの型付け用）。 */
export type CatalogPosition = SoccerPosition | FutsalPosition | BasketballPosition

// ===== ライブ観戦（STOMP 配信ペイロード・07_realtime_spectator.md §J.2.1） =====
//
// 【前向きユニオン境界（7-C 追加）】
// `/topic/matches/{matchId}/live` の STOMP 配信ペイロード（BE `match.live.MatchLiveUpdatePayload`/
// `MatchLiveEventView`/`MatchLiveScoreSummary`/`MatchLiveUpdateType`）は **REST DTO ではない**ため
// OpenAPI（docs/openapi.json）に現れず、生成型 `types/generated` に未反映である。観戦ビュー（read-only）
// は配信を消費するため、ここで前向きユニオンとして定義する。生成型一括再生成で BE 配信 DTO が openapi へ
// 露出した暁には本ユニオンを生成型へ寄せて段階移行する（any 禁止・境界は STOMP 受信パースの 1 箇所に閉じる）。

/** 配信メッセージ種別（BE MatchLiveUpdateType・07 §J.2.1）。 */
export type MatchLiveUpdateType =
  | 'EVENT_ADDED'
  | 'EVENT_UPDATED'
  | 'EVENT_DELETED'
  | 'SCORE_UPDATED'
  | 'STATUS_CHANGED'

/**
 * 配信用の最小タイムラインイベントビュー（BE MatchLiveEventView・07 §J.3.3）。
 * 機微情報（内部ユーザー ID・所有チーム ID）は **意図的に含まれない**（BE 側で除外済み）。
 * FE は本ビューをスナップショットの {@link MatchEventResponse} 形へマージする（player_user_id 等は欠落）。
 */
export interface MatchLiveEventView {
  id?: string
  minute?: number | null
  stoppageMinute?: number | null
  period?: MatchPeriod | null
  eventType?: MatchEventType
  cardReasonCode?: string | null
  customLabel?: string | null
  teamSide?: TeamSide
  playerName?: string | null
  jerseyNumber?: number | null
  relatedPlayerName?: string | null
  note?: string | null
  linkedEventId?: string | null
  sortSeq?: number
}

/** 配信スコアサマリ（BE MatchLiveScoreSummary・07 §J.2.1）。 */
export interface MatchLiveScoreSummary {
  homeScore?: number | null
  awayScore?: number | null
  homePenaltyScore?: number | null
  awayPenaltyScore?: number | null
}

/** 配信差分ペイロード（BE MatchLiveUpdatePayload・07 §J.2.1）。 */
export interface MatchLiveUpdatePayload {
  type: MatchLiveUpdateType
  matchId?: string
  /** 単調増加シーケンス（順序検出・飛び検知＝スナップショット再取得・07 §J.2.1 / §J.4）。 */
  serverSeq: number
  /** 差分イベント（EVENT_ADDED/EVENT_UPDATED 時）。 */
  event?: MatchLiveEventView | null
  /** 削除イベント ID（EVENT_DELETED 時）。 */
  eventId?: string | null
  /** 更新後スコア（SCORE_UPDATED 時）。 */
  score?: MatchLiveScoreSummary | null
  /** 遷移後ステータス（STATUS_CHANGED 時）。 */
  status?: MatchSummaryStatus | null
}

/**
 * 観戦ビューの接続状態（04 §G.17 接続状態インジケーター）。
 * - CONNECTING:   初回接続中（購読確立前）
 * - LIVE:         STOMP 接続中・差分配信を受信中（ライブ）
 * - RECONNECTING: 切断→再接続中（復帰後にスナップショット再取得・07 §J.4）
 * - OFFLINE:      WebSocket・HTTP ともに不通（最終スナップショット表示＝「最新でない可能性」明示）
 * - DENIED:       購読拒否（可視性なし・F00／STOMP ERROR フレーム・07 §J.3.1）
 */
export type SpectatorConnectionState =
  | 'CONNECTING'
  | 'LIVE'
  | 'RECONNECTING'
  | 'OFFLINE'
  | 'DENIED'

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
