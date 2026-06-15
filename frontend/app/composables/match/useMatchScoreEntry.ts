/**
 * F08.10 採点競技（SCORED・フィギュア/体操）の合計点入力 API composable（sports/07_scored.md §9 / §4.1）。
 *
 * 採点競技（FIGURE_SKATING / GYMNASTICS）は MVP では「合計点のみ・2 者対戦」を記録する。
 * 合計点は小数（フィギュア 198.45・体操 85.332）だが、BE は整数スケール×1000 で
 * `home_score`/`away_score` に格納する（§4.1）。よって FE は小数入力を受け取り、
 * **×1000 の整数へ変換して送信**する（生成型 `MatchRecordScoredResultRequest` の
 * `homeScoreScaled`/`awayScoreScaled` = int32 が受け皿）。勝敗は合計点の大小で BE が導出する
 * （`resolveResult()` 再利用・§4.2）。FE は勝敗を送らず合計点だけ送る。
 *
 * BE 契約（#1551・生成型 recordScore）:
 *   PUT /api/v1/organizations/{orgId}/matches/{matchId}/scored-result   採点結果記録/更新（冪等）
 *     body: MatchRecordScoredResultRequest { homeScoreScaled, awayScoreScaled }
 *     200:  ApiResponseMatchDetailResponse
 *
 * 【型規律（生成型を消費・手書き契約型ゼロ）】
 * BE DTO（MatchRecordScoredResultRequest / MatchDetailResponse）は OpenAPI 再生成（#1552）で
 * 生成型 `Schemas` に反映済みのため、再エクスポートのエイリアスのみで手書きの前向きユニオン/
 * 契約型は作らない（生成型が正本・any なし）。
 *
 * 【useMatchTurnTracker の採点版】タイマー・セットトラッカーを持たず、
 * WAITING→IN_PROGRESS→COMPLETED の最小遷移と合計点（home/away）を管理する軽量 composable（§9）。
 */
import { computed, ref } from 'vue'
import type { components } from '~/types/generated'

type Schemas = components['schemas']

// ===== DTO 型（生成型の再エクスポート・手書き契約型ゼロ） =====

/** 採点結果記録リクエスト（生成型 MatchRecordScoredResultRequest・整数スケール×1000）。 */
export type MatchScoredResultRequestPayload = Schemas['MatchRecordScoredResultRequest']
/** 採点結果を含む試合詳細レスポンス（生成型 MatchDetailResponse）。 */
export type ScoredMatchResponse = Schemas['MatchDetailResponse']

/** 採点入力トラッカーの状態（§9 の最小遷移＝ターン制と同型）。 */
export type ScoreEntryState = 'WAITING' | 'IN_PROGRESS' | 'COMPLETED'

/** 整数スケール係数（×1000・§4.1）。小数 198.45 → 整数 198450。 */
export const SCORE_SCALE_FACTOR = 1000

// ===== ピュア関数（テスト可能・副作用なし） =====

/**
 * 小数の合計点を整数スケール（×1000）へ変換する（§4.1）。
 * 浮動小数の誤差を避けるため四捨五入する（198.45 → 198450・85.332 → 85332）。
 * null / NaN / 負値は null を返す（未入力扱い・送信時にガードする）。
 */
export function toScaledScore(value: number | null | undefined): number | null {
  if (value === null || value === undefined || Number.isNaN(value)) return null
  if (value < 0) return null
  return Math.round(value * SCORE_SCALE_FACTOR)
}

/**
 * 整数スケール（×1000）を小数の合計点へ復元する（表示用・§4.1）。
 * null は null を返す。
 */
export function fromScaledScore(scaled: number | null | undefined): number | null {
  if (scaled === null || scaled === undefined || Number.isNaN(scaled)) return null
  return scaled / SCORE_SCALE_FACTOR
}

/**
 * 合計点（小数）からワイヤーペイロードを構築する（送出 JSON 形・§4.1）。
 * home/away の小数を整数スケール（×1000）へ変換する。未入力（null）は 0 として送る
 * （BE は両スコアを int32 で受ける＝未確定でも 0-0 の引分扱い。確定ガードは canSubmit で行う）。
 */
export function buildScoredResultPayload(
  homeScore: number | null,
  awayScore: number | null,
): MatchScoredResultRequestPayload {
  return {
    homeScoreScaled: toScaledScore(homeScore) ?? 0,
    awayScoreScaled: toScaledScore(awayScore) ?? 0,
  }
}

// ===== composable =====

/** useMatchScoreEntry の初期化オプション。 */
export interface UseMatchScoreEntryOptions {
  /** 競技識別（FIGURE_SKATING / GYMNASTICS）。表示ラベルの出し分けに使う。 */
  sport?: 'FIGURE_SKATING' | 'GYMNASTICS' | string
}

/**
 * 採点競技の合計点入力・API 呼び出しを管理する composable（MVP＝合計点のみ・2 者対戦）。
 *
 * 状態機械（§9・ターン制と同型）:
 *   WAITING → IN_PROGRESS → COMPLETED
 * - WAITING: 採点前（未開始）
 * - IN_PROGRESS: 採点入力中（home/away の合計点入力が可能）
 * - COMPLETED: 採点確定（スコア送信済み）
 */
export function useMatchScoreEntry(options: UseMatchScoreEntryOptions = {}) {
  const sport = options.sport ?? 'FIGURE_SKATING'

  // useApi/useNotification/useI18n は API 呼び出し時にのみ解決する（遅延）。
  // composable 構築時に呼ぶと Pinia/PrimeVue/i18n コンテキストを要求するため、
  // 純粋な状態トラッカー（createScoreEntry）として live.vue 外でも構築できるようにする
  // （useMatchTurnTracker と同じく状態は副作用なしで持つ）。

  const base = (orgId: number, matchId: string) =>
    `/api/v1/organizations/${orgId}/matches/${matchId}`

  /** 現在のトラッカー状態（§9 の最小遷移）。 */
  const entryState = ref<ScoreEntryState>('WAITING')

  /** ホーム（自/相手のいずれか）の合計点（小数・未入力 null）。 */
  const homeScore = ref<number | null>(null)
  /** アウェイの合計点（小数・未入力 null）。 */
  const awayScore = ref<number | null>(null)

  /** 採点が確定済みか（COMPLETED 遷移済み）。 */
  const isCompleted = computed<boolean>(() => entryState.value === 'COMPLETED')

  /** フィギュアスケートか（表示ラベルの出し分け）。 */
  const isFigureSkating = computed<boolean>(() => sport === 'FIGURE_SKATING')
  /** 体操か（表示ラベルの出し分け）。 */
  const isGymnastics = computed<boolean>(() => sport === 'GYMNASTICS')

  /**
   * 確定（送信）できるか（COMPLETED 遷移の前提条件）。
   * - IN_PROGRESS 状態
   * - home/away の両方に合計点が入力済み（0 以上）
   * 勝敗は BE が導出するため FE は両合計点を送るだけ（同点＝引分は BE 判定・§4.2）。
   */
  const canSubmit = computed<boolean>(() => {
    if (entryState.value !== 'IN_PROGRESS') return false
    return (
      homeScore.value !== null &&
      homeScore.value >= 0 &&
      awayScore.value !== null &&
      awayScore.value >= 0
    )
  })

  // ===== 状態遷移・操作 =====

  /** 採点開始（WAITING → IN_PROGRESS）。 */
  function start(): void {
    if (entryState.value !== 'WAITING') return
    entryState.value = 'IN_PROGRESS'
  }

  /** ホームの合計点を設定（小数・負値は null へ丸める）。 */
  function setHomeScore(value: number | null): void {
    homeScore.value = value === null || value < 0 ? null : value
  }

  /** アウェイの合計点を設定（小数・負値は null へ丸める）。 */
  function setAwayScore(value: number | null): void {
    awayScore.value = value === null || value < 0 ? null : value
  }

  /** 状態を復元する（既存試合の再開時・BE レスポンスから初期化）。 */
  function restore(restored: {
    state?: ScoreEntryState
    homeScoreScaled?: number | null
    awayScoreScaled?: number | null
  }): void {
    if (restored.state) entryState.value = restored.state
    if (restored.homeScoreScaled !== undefined) {
      homeScore.value = fromScaledScore(restored.homeScoreScaled)
    }
    if (restored.awayScoreScaled !== undefined) {
      awayScore.value = fromScaledScore(restored.awayScoreScaled)
    }
  }

  // ===== API =====

  /**
   * 採点結果（合計点）を記録/更新する（PUT /scored-result・冪等）。
   * 小数の合計点を整数スケール×1000 へ変換して送る。BE が home/away_score を確定し、
   * 大小から勝敗（W/D/L）を導出する（§4.2）。status の COMPLETED 遷移は別途 changeStatus で行う。
   */
  async function recordScore(orgId: number, matchId: string): Promise<ScoredMatchResponse> {
    const api = useApi()
    const payload = buildScoredResultPayload(homeScore.value, awayScore.value)
    try {
      const res = await api<{ data: ScoredMatchResponse }>(
        `${base(orgId, matchId)}/scored-result`,
        { method: 'PUT', body: payload },
      )
      return res.data
    } catch (err) {
      const notification = useNotification()
      const { t } = useI18n()
      notification.error(t('match.scored.error.record_failed'))
      throw err
    }
  }

  /**
   * 採点を確定する（recordScore 成功時に COMPLETED へ遷移）。
   * 送信前ガード（canSubmit）を満たさない場合は何もしない。
   */
  async function submit(orgId: number, matchId: string): Promise<ScoredMatchResponse | null> {
    if (!canSubmit.value) return null
    const res = await recordScore(orgId, matchId)
    entryState.value = 'COMPLETED'
    return res
  }

  return {
    // 設定
    sport,
    isFigureSkating,
    isGymnastics,
    // 状態
    entryState,
    homeScore,
    awayScore,
    // 算出
    isCompleted,
    canSubmit,
    // 遷移・操作
    start,
    setHomeScore,
    setAwayScore,
    restore,
    // API
    recordScore,
    submit,
  }
}

export type MatchScoreEntryReturn = ReturnType<typeof useMatchScoreEntry>
