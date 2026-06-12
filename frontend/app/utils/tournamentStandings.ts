/**
 * F08.7 順位UI Wave1: 順位表 / マトリクス / ランキング表示の純関数群。
 *
 * Vue コンポーネントから DOM・API 依存を切り離してテスト容易にするため、
 * 表示整形ロジック（マトリクスのグリッド化・ランキングのチャート整形・可視性レベル定義）を
 * ここに集約する。
 */
import type {
  TournamentMatrix,
  MatrixCell,
  IndividualRanking,
  IndividualRankingContextDto,
} from '~/types/tournament'

// ──────────────────────────────────────────────────
// 可視性 6 レベル（Wave0 BE enum 名と一致）
// ──────────────────────────────────────────────────

/** 大会 visibility の 6 レベル（BE enum 名）。 */
export const TOURNAMENT_VISIBILITY_LEVELS = [
  'PUBLIC',
  'SUPPORTERS_AND_ABOVE',
  'MEMBERS_AND_ABOVE',
  'ADMINS_AND_ABOVE',
  'SCOPE_AFFILIATED',
  'PARTICIPANTS_ONLY',
] as const

export type TournamentVisibility = (typeof TOURNAMENT_VISIBILITY_LEVELS)[number]

/**
 * 任意の文字列が 6 レベルの可視性 enum 名かどうかを判定する（型ガード）。
 * 未知値（null / 旧値 / 不正値）を弾いて UI のセレクタを壊さないために使う。
 */
export function isTournamentVisibility(v: unknown): v is TournamentVisibility {
  return (
    typeof v === 'string' &&
    (TOURNAMENT_VISIBILITY_LEVELS as readonly string[]).includes(v)
  )
}

// ──────────────────────────────────────────────────
// マトリクス（総当たり表）整形
// ──────────────────────────────────────────────────

/** マトリクスの 1 セル（行=home participant・列=away participant の交点）。 */
export interface MatrixGridCell {
  /** 対角（同一 participant）か。対角は対戦しないため斜線等で表現する。 */
  isDiagonal: boolean
  /** 該当試合のセル。未対戦 / 対角は null。 */
  cell: MatrixCell | null
}

/** マトリクスの 1 行（行ヘッダ participant ＋ 各列のセル）。 */
export interface MatrixGridRow {
  participantId: number
  teamName: string
  cells: MatrixGridCell[]
}

/** マトリクスのグリッド表示モデル（列ヘッダ ＋ 行配列）。 */
export interface MatrixGrid {
  columns: Array<{ participantId: number; teamName: string }>
  rows: MatrixGridRow[]
}

/**
 * マトリクスのセルキーを生成する（BE と同じ `${home}_${away}` 形式）。
 */
export function matrixCellKey(homeParticipantId: number, awayParticipantId: number): string {
  return `${homeParticipantId}_${awayParticipantId}`
}

/**
 * MatrixResponse を「行×列」のグリッド表示モデルへ整形する。
 *
 * - 行 = home participant、列 = away participant（participants の順序を維持）。
 * - 対角（home==away）は対戦しないため isDiagonal=true・cell=null。
 * - cells に該当キーが無い交点は未対戦として cell=null。
 *
 * @param matrix BE MatrixResponse（participants ＋ cells）。null/未定義は空グリッドを返す。
 */
export function buildMatrixGrid(matrix: TournamentMatrix | null | undefined): MatrixGrid {
  const participants = matrix?.participants ?? []
  const cells = matrix?.cells ?? {}

  const columns = participants.map((p) => ({
    participantId: p.participantId,
    teamName: p.teamName,
  }))

  const rows: MatrixGridRow[] = participants.map((home) => ({
    participantId: home.participantId,
    teamName: home.teamName,
    cells: participants.map((away) => {
      if (home.participantId === away.participantId) {
        return { isDiagonal: true, cell: null }
      }
      const key = matrixCellKey(home.participantId, away.participantId)
      return { isDiagonal: false, cell: cells[key] ?? null }
    }),
  }))

  return { columns, rows }
}

/**
 * マトリクスセルのスコア表記を生成する（home-away 視点）。
 * 未対戦 / スコア未確定は空文字を返す（呼び出し側でプレースホルダ表示）。
 */
export function matrixCellScoreText(cell: MatrixCell | null): string {
  if (!cell) return ''
  if (cell.homeScore == null || cell.awayScore == null) return ''
  return `${cell.homeScore}-${cell.awayScore}`
}

// ──────────────────────────────────────────────────
// 個人ランキング 選手名表示（F08.7 順位UI 項目① / BE #1466）
// ──────────────────────────────────────────────────

/**
 * ランキング行の選手表示名を解決する（B-1）。
 *
 * BE #1466 は F19.1 本人可視性を経由して解決済みで、匿名化フォールバック時には
 * displayName にサーバ側日本語固定値（「投稿者」「退会済みユーザー」「匿名のユーザー#…」等）を
 * 詰めて返す。en/zh 等で日本語が露出しないよう、{@code anonymized === true} のときは
 * 呼び出し側がローカライズ済みの汎用匿名ラベルを渡し、それを優先表示する。
 *
 * - anonymized === true → ローカライズ匿名ラベル（具体種別の出し分けは BE がキーを返さないため一括）
 * - anonymized !== true かつ displayName あり → displayName
 * - いずれも無い → #userId（userId 不在なら "-"）
 *
 * @param context ランキング行のコンテキスト（displayName / anonymized / userId）
 * @param anonymousLabel i18n 済みの匿名汎用ラベル（例 t('tournament.rankings.anonymousPlayer')）
 */
export function resolveRankingPlayerName(
  context: IndividualRankingContextDto | null | undefined,
  anonymousLabel: string,
): string {
  if (context?.anonymized === true) return anonymousLabel
  const name = context?.displayName
  if (name != null && name.trim() !== '') return name
  const userId = context?.userId
  return userId != null ? `#${userId}` : '-'
}

// ──────────────────────────────────────────────────
// 個人ランキング → チャートデータ整形
// ──────────────────────────────────────────────────

/** chart.js の bar 用最小データ構造（BaseChart に渡す形）。 */
export interface RankingChartData {
  labels: string[]
  datasets: Array<{ label: string; data: number[] }>
}

/**
 * 個人ランキングを bar チャート用データへ整形する（得点王等の上位可視化）。
 *
 * - 値は totalValueInt > totalValueDecimal > totalValueTime の優先で採用（null は 0）。
 * - rank 昇順に並べ、上位 limit 件のみ採用。
 * - ラベルは「ユーザー名解決関数」で参照名へ変換（無ければ #userId）。
 *
 * @param rankings 個人ランキング配列
 * @param datasetLabel データセット名（i18n 済み文字列を渡す）
 * @param resolveName userId → 表示名（無ければ undefined を返してよい）
 * @param limit 上位何件を可視化するか（既定 10）
 */
export function buildRankingChartData(
  rankings: IndividualRanking[],
  datasetLabel: string,
  resolveName: (userId: number | undefined) => string | undefined,
  limit = 10,
): RankingChartData {
  const sorted = [...rankings]
    .sort((a, b) => (a.rank ?? Number.MAX_SAFE_INTEGER) - (b.rank ?? Number.MAX_SAFE_INTEGER))
    .slice(0, limit)

  const labels = sorted.map((r) => {
    const userId = r.context?.userId
    return resolveName(userId) ?? (userId != null ? `#${userId}` : '-')
  })

  const data = sorted.map((r) => rankingValue(r))

  return {
    labels,
    datasets: [{ label: datasetLabel, data }],
  }
}

/**
 * BE の LocalTime JSON 文字列（"HH:mm:ss" / "HH:mm:ss.SSS"）を秒数へ変換する。
 *
 * BE は time 系 stat（最速タイム等）を数値ではなく文字列で返すため、チャートの
 * 数値軸に載せるには秒換算が必要。パースできない値は null を返す（呼び出し側で 0 扱い）。
 */
export function parseTimeToSeconds(value: string | null | undefined): number | null {
  if (value == null) return null
  const parts = value.split(':')
  if (parts.length !== 3) return null
  const hours = Number(parts[0])
  const minutes = Number(parts[1])
  const seconds = Number(parts[2]) // 小数秒（"SS.SSS"）も Number で吸収
  if (!Number.isFinite(hours) || !Number.isFinite(minutes) || !Number.isFinite(seconds)) {
    return null
  }
  return hours * 3600 + minutes * 60 + seconds
}

/**
 * 個人ランキングの集計値をチャート用の数値で取り出す。
 * int > decimal > time（秒換算） の優先で採用。すべて null / パース不能なら 0。
 *
 * time 系（totalValueTime）は "HH:mm:ss" 文字列なので秒数へ換算してから数値化する。
 * 文字列をそのまま数値扱いすると NaN になりチャートが破綻するため、必ず換算を通す。
 */
export function rankingValue(r: IndividualRanking): number {
  const stat = r.stat
  if (!stat) return 0
  if (stat.totalValueInt != null) return stat.totalValueInt
  if (stat.totalValueDecimal != null) return stat.totalValueDecimal
  if (stat.totalValueTime != null) {
    return parseTimeToSeconds(stat.totalValueTime) ?? 0
  }
  return 0
}

/**
 * ランキング集計値の表示用テキスト（単位付き）。
 *
 * - time 系（totalValueTime のみ存在）は BE の "HH:mm:ss" 文字列をそのまま表示する
 *   （秒換算した数値ではなく、人間可読のタイム表記を維持する）。time の場合 unit は付けない。
 * - int / decimal 系は数値 ＋ 単位で表示する。
 */
export function rankingValueText(r: IndividualRanking, unit?: string): string {
  const stat = r.stat
  // time 系は文字列をそのまま表示（int/decimal が無く time のみのケース）
  if (
    stat &&
    stat.totalValueInt == null &&
    stat.totalValueDecimal == null &&
    stat.totalValueTime != null
  ) {
    return stat.totalValueTime
  }
  const v = rankingValue(r)
  return unit ? `${v}${unit}` : String(v)
}
