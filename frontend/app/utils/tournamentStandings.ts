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
 * 個人ランキングの集計値を数値で取り出す。
 * int > decimal > time（秒） の優先で採用。すべて null なら 0。
 */
export function rankingValue(r: IndividualRanking): number {
  const stat = r.stat
  if (!stat) return 0
  if (stat.totalValueInt != null) return stat.totalValueInt
  if (stat.totalValueDecimal != null) return stat.totalValueDecimal
  if (stat.totalValueTime != null) return stat.totalValueTime
  return 0
}

/**
 * ランキング集計値の表示用テキスト（単位付き）。
 * time 系（totalValueTime のみ存在）は秒数をそのまま表示する（呼び出し側で必要なら整形）。
 */
export function rankingValueText(r: IndividualRanking, unit?: string): string {
  const v = rankingValue(r)
  return unit ? `${v}${unit}` : String(v)
}
