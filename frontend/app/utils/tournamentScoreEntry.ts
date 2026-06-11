/**
 * F08.7 順位UI Wave2: 管理者向けスコア入力グリッド／CSV取込の純関数群。
 *
 * Vue コンポーネントから DOM・API 依存を切り離してテスト容易にするため、
 * バッチペイロード組み立て（楽観ロック version 同梱）・参加チーム名解決・
 * エラー HTTP ステータス判定・CSV テンプレ文字列生成をここに集約する。
 */
import type { TournamentMatch, TournamentMatrix } from '~/types/tournament'

// ──────────────────────────────────────────────────
// 入力行モデル
// ──────────────────────────────────────────────────

/** スコア入力グリッドの 1 行（1 試合）。入力欄の値は string で保持し送信時に数値化する。 */
export interface ScoreEntryRow {
  /** 試合 ID（BatchScoreRequest.matchId）。 */
  matchId: number
  /** 楽観ロック version（MatchResponse.audit.version 由来・送信時必須）。 */
  version: number
  /** ホーム参加チーム表示名（解決済み・未解決は #participantId）。 */
  homeName: string
  /** アウェイ参加チーム表示名。 */
  awayName: string
  /** ホームスコア入力値（空文字 = 未入力 = null 送信）。 */
  homeScore: string
  /** アウェイスコア入力値。 */
  awayScore: string
}

/** BatchScoreRequest の 1 エントリ（BE BatchScoreRequest.MatchScoreEntry 整合）。 */
export interface BatchScoreEntryPayload {
  matchId: number
  homeScore: number | null
  awayScore: number | null
  version: number
}

/** BatchScoreRequest 全体（BE は { scores: [...] } を期待する）。 */
export interface BatchScorePayload {
  scores: BatchScoreEntryPayload[]
}

// ──────────────────────────────────────────────────
// 参加チーム名解決
// ──────────────────────────────────────────────────

/**
 * マトリクスの participants から participantId → チーム名のマップを作る。
 * マトリクス API は participantId と teamName を同梱して返すため、これを名前解決の正本に使う。
 */
export function buildParticipantNameMap(
  matrix: TournamentMatrix | null | undefined,
): Map<number, string> {
  const map = new Map<number, string>()
  for (const p of matrix?.participants ?? []) {
    map.set(p.participantId, p.teamName)
  }
  return map
}

/** participantId を表示名へ解決する（未解決は `#id`、id 自体が無ければ "-"）。 */
export function resolveParticipantName(
  participantId: number | undefined,
  nameMap: Map<number, string>,
): string {
  if (participantId == null) return '-'
  return nameMap.get(participantId) ?? `#${participantId}`
}

/** スコア入力欄の表示値（null/undefined は空文字）。 */
function scoreToInput(v: number | null | undefined): string {
  return v == null ? '' : String(v)
}

/**
 * 節の試合配列を入力グリッド行へ整形する。
 *
 * - version は MatchResponse.audit.version を採用（楽観ロックに必須）。version 不在は 0 とする。
 * - スコアは既存値を入力欄初期値として埋める（再編集を想定）。
 * - home/away 名はマトリクス由来の name マップで解決する。
 */
export function buildScoreEntryRows(
  matches: TournamentMatch[],
  nameMap: Map<number, string>,
): ScoreEntryRow[] {
  return matches.map((m) => ({
    matchId: m.id,
    version: m.audit?.version ?? 0,
    homeName: resolveParticipantName(m.participants?.homeParticipantId, nameMap),
    awayName: resolveParticipantName(m.participants?.awayParticipantId, nameMap),
    homeScore: scoreToInput(m.score?.homeScore),
    awayScore: scoreToInput(m.score?.awayScore),
  }))
}

// ──────────────────────────────────────────────────
// バッチペイロード組み立て
// ──────────────────────────────────────────────────

/**
 * 入力欄文字列を送信用スコア（整数 or null）へ正規化する。
 * 空文字は null（未入力）。非数値・負数・小数は不正値として扱い null を返さず例外検出に回すため、
 * ここでは「有効な非負整数 or null」のみを返し、不正は呼び出し側の isRowValid で弾く。
 */
export function parseScoreInput(raw: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  if (!/^\d+$/.test(trimmed)) return null
  return Number(trimmed)
}

/**
 * 1 行が送信可能か（home/away ともに「空 or 非負整数」）を判定する。
 * 片方だけ入力されている／非数値・負数・小数が含まれる行は不正とする。
 */
export function isRowValid(row: ScoreEntryRow): boolean {
  const h = row.homeScore.trim()
  const a = row.awayScore.trim()
  const valid = (s: string) => s === '' || /^\d+$/.test(s)
  if (!valid(h) || !valid(a)) return false
  // 片方のみ入力は不正（両方入力 or 両方未入力のみ許可）
  if ((h === '') !== (a === '')) return false
  return true
}

/**
 * 入力行から送信対象（home/away 両方入力済みの行）だけを抽出してバッチペイロードを組み立てる。
 *
 * - 両方未入力の行はスキップ（未消化の試合を 0-0 で確定させない）。
 * - 各エントリに version を必ず同梱する（楽観ロック）。
 * - 不正行（片方のみ・非数値等）が 1 つでもあれば null を返す（呼び出し側で保存を中断する）。
 */
export function buildBatchScorePayload(rows: ScoreEntryRow[]): BatchScorePayload | null {
  const scores: BatchScoreEntryPayload[] = []
  for (const row of rows) {
    if (!isRowValid(row)) return null
    const home = parseScoreInput(row.homeScore)
    const away = parseScoreInput(row.awayScore)
    // 両方未入力はスキップ（送信対象外）
    if (home == null && away == null) continue
    scores.push({
      matchId: row.matchId,
      homeScore: home,
      awayScore: away,
      version: row.version,
    })
  }
  return { scores }
}

// ──────────────────────────────────────────────────
// エラー HTTP ステータス判定
// ──────────────────────────────────────────────────

/** ofetch / fetch 由来エラーから HTTP ステータスコードを取り出す（取得不能は undefined）。 */
export function extractStatus(e: unknown): number | undefined {
  const err = e as { response?: { status?: number }; statusCode?: number; status?: number }
  return err?.response?.status ?? err?.statusCode ?? err?.status
}

/** 楽観ロック衝突（409 Conflict）かどうか。 */
export function isConflictError(e: unknown): boolean {
  return extractStatus(e) === 409
}

/** 権限不足（403/404＝不可視 IDOR）かどうか。 */
export function isForbiddenError(e: unknown): boolean {
  const s = extractStatus(e)
  return s === 403 || s === 404
}

// ──────────────────────────────────────────────────
// CSV テンプレート
// ──────────────────────────────────────────────────

/** CSV 取込のヘッダー行（BE importScores の列順と一致させる）。 */
export const SCORE_CSV_HEADER = 'matchId,homeScore,awayScore'

/**
 * 現在の入力行から CSV テンプレート文字列を生成する（matchId 入りひな形）。
 * BOM 無しの UTF-8。各行は matchId と既存スコア（空なら空欄）を出力する。
 */
export function buildCsvTemplate(rows: ScoreEntryRow[]): string {
  const lines = [SCORE_CSV_HEADER]
  for (const row of rows) {
    lines.push(`${row.matchId},${row.homeScore},${row.awayScore}`)
  }
  return lines.join('\n') + '\n'
}
