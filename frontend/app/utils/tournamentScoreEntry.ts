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

/** セット入力 1 つ分（home/away の得点を string で保持）。 */
export interface ScoreEntrySet {
  /** セット番号（1 始まり・送信時 setNumber に写す）。 */
  setNumber: number
  /** ホームセット得点入力値（空文字 = 未入力）。 */
  home: string
  /** アウェイセット得点入力値（空文字 = 未入力）。 */
  away: string
}

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
  /** ホーム延長スコア入力値（hasExtraTime 大会のみ表示・送信）。 */
  homeExtraScore: string
  /** アウェイ延長スコア入力値。 */
  awayExtraScore: string
  /** ホーム PK スコア入力値（hasPenalties 大会のみ表示・送信）。 */
  homePenaltyScore: string
  /** アウェイ PK スコア入力値。 */
  awayPenaltyScore: string
  /**
   * セット別得点入力（hasSets 大会のみ表示・送信）。
   * 先取制ゆえ最大 `2*setsToWin-1` セット分の枠を初期化する。
   * 既存スコアがあれば setNumber 昇順で埋め、空セットは送信しない。
   */
  sets: ScoreEntrySet[]
}

/** セット別スコア送信エントリ（BE FixtureSetRequest 整合・全フィールド非 null 整数）。 */
export interface MatchSetEntryPayload {
  setNumber: number
  homeScore: number
  awayScore: number
}

/** BatchScoreRequest の 1 エントリ（BE BatchScoreRequest.MatchScoreEntry 整合）。 */
export interface BatchScoreEntryPayload {
  matchId: number
  homeScore: number | null
  awayScore: number | null
  /** 延長スコア（hasExtraTime 大会のみ。未入力 / 対象外は null）。 */
  homeExtraScore: number | null
  awayExtraScore: number | null
  /** PK スコア（hasPenalties 大会のみ。未入力 / 対象外は null）。 */
  homePenaltyScore: number | null
  awayPenaltyScore: number | null
  version: number
  /**
   * セット別スコア（hasSets 大会のみ）。setNumber 昇順・入力済みセットのみ同梱する。
   * BE は勝セット数（setsToWin 先取制）で勝敗を判定する（#1485）。
   * セット制でない大会では undefined（同梱しない）。
   */
  sets?: MatchSetEntryPayload[]
}

/**
 * スコア入力欄の出し分けフラグ（大会の TournamentScoringDto 由来）。
 */
export interface ScoreEntryColumnFlags {
  /** 延長スコア欄（home/away）を表示するか。 */
  showExtraTime: boolean
  /** PK スコア欄（home/away）を表示するか。 */
  showPenalties: boolean
  /** セット別スコア欄を表示するか（hasSets 由来）。 */
  showSets: boolean
  /** 何セット先取で勝利か（setsToWin 由来・1 以上）。セット枠数 = `2*setsToWin-1`。 */
  setsToWin: number
}

/** BatchScoreRequest 全体（BE は { scores: [...] } を期待する）。 */
export interface BatchScorePayload {
  scores: BatchScoreEntryPayload[]
}

/** 既定の出し分けフラグ（延長/PK/セットいずれも無し・setsToWin は安全側 1）。 */
export const DEFAULT_COLUMN_FLAGS: ScoreEntryColumnFlags = {
  showExtraTime: false,
  showPenalties: false,
  showSets: false,
  setsToWin: 1,
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
 * 先取制（setsToWin）で起こり得る最大セット数を返す（= 2*setsToWin-1）。
 * setsToWin が不正（1 未満 / 非整数）の場合は安全側で 1 にフォールバックする。
 */
export function maxSetCount(setsToWin: number): number {
  const n = Number.isInteger(setsToWin) && setsToWin >= 1 ? setsToWin : 1
  return 2 * n - 1
}

/**
 * 既存セットスコア＋セット枠数から入力用のセット配列を初期化する。
 *
 * - 枠数は先取制の最大セット数（2*setsToWin-1）。
 * - 既存 sets を setNumber 昇順に並べ、各枠へ home/away を埋める。空き枠は空文字。
 * - 既存セットが枠数を超える場合（過去データ等）はその分も末尾に残す（取りこぼし防止）。
 */
export function buildScoreEntrySets(
  existing: ReadonlyArray<{ setNumber: number; homeScore: number; awayScore: number }> | null | undefined,
  setsToWin: number,
): ScoreEntrySet[] {
  const slots = maxSetCount(setsToWin)
  const byNumber = new Map<number, { homeScore: number; awayScore: number }>()
  for (const s of existing ?? []) {
    byNumber.set(s.setNumber, { homeScore: s.homeScore, awayScore: s.awayScore })
  }
  const total = Math.max(slots, ...[...byNumber.keys(), 0])
  const result: ScoreEntrySet[] = []
  for (let n = 1; n <= total; n++) {
    const hit = byNumber.get(n)
    result.push({
      setNumber: n,
      home: scoreToInput(hit?.homeScore),
      away: scoreToInput(hit?.awayScore),
    })
  }
  return result
}

/**
 * 節の試合配列を入力グリッド行へ整形する。
 *
 * - version は MatchResponse.audit.version を採用（楽観ロックに必須）。version 不在は 0 とする。
 * - スコアは既存値を入力欄初期値として埋める（再編集を想定）。
 * - home/away 名はマトリクス由来の name マップで解決する。
 * - flags.showSets のときセット入力枠を初期化する（先取制の最大セット数 + 既存セット）。
 */
export function buildScoreEntryRows(
  matches: TournamentMatch[],
  nameMap: Map<number, string>,
  flags: ScoreEntryColumnFlags = DEFAULT_COLUMN_FLAGS,
): ScoreEntryRow[] {
  return matches.map((m) => ({
    matchId: m.id,
    version: m.audit?.version ?? 0,
    homeName: resolveParticipantName(m.participants?.homeParticipantId, nameMap),
    awayName: resolveParticipantName(m.participants?.awayParticipantId, nameMap),
    homeScore: scoreToInput(m.score?.homeScore),
    awayScore: scoreToInput(m.score?.awayScore),
    homeExtraScore: scoreToInput(m.score?.homeExtraScore),
    awayExtraScore: scoreToInput(m.score?.awayExtraScore),
    homePenaltyScore: scoreToInput(m.score?.homePenaltyScore),
    awayPenaltyScore: scoreToInput(m.score?.awayPenaltyScore),
    sets: flags.showSets ? buildScoreEntrySets(m.sets, flags.setsToWin) : [],
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

/** 1 組のスコア欄（home/away）が「両方空 or 両方非負整数」かを判定する。 */
function isScorePairValid(home: string, away: string): boolean {
  const h = home.trim()
  const a = away.trim()
  const valid = (s: string) => s === '' || /^\d+$/.test(s)
  if (!valid(h) || !valid(a)) return false
  // 片方のみ入力は不正（両方入力 or 両方未入力のみ許可）
  return (h === '') === (a === '')
}

/**
 * セット入力配列が送信可能かを判定する。
 *
 * - 各セットは「両方空 or 両方非負整数」（途中のセットを片方だけ入力するのは不正）。
 * - 空セットは送信対象外（入力途中は許容する）。確定時は呼び出し側が isRowValid 全体で弾く。
 */
function areSetsValid(sets: ScoreEntrySet[]): boolean {
  for (const s of sets) {
    if (!isScorePairValid(s.home, s.away)) return false
  }
  return true
}

/**
 * 1 行が送信可能かを判定する。
 *
 * - セット制（showSets）では本戦 home/away 欄を入力に使わない（合計はセットから自動算出）ため検証しない。
 *   代わりに各セットを「両方空 or 両方非負整数」で検証する。
 * - 非セット制では本戦 home/away は「両方空 or 両方非負整数」。
 * - flags で延長/PK 欄が有効な場合は、その欄も同じ規則で検証する。
 *   無効な欄（フラグ false）の入力は送信対象外なので検証しない。
 * - 片方だけ入力／非数値・負数・小数が含まれる行は不正とする。
 */
export function isRowValid(
  row: ScoreEntryRow,
  flags: ScoreEntryColumnFlags = DEFAULT_COLUMN_FLAGS,
): boolean {
  if (flags.showSets) {
    // セット制では本戦欄は使わない。セット入力のみ検証する。
    return areSetsValid(row.sets)
  }
  if (!isScorePairValid(row.homeScore, row.awayScore)) return false
  if (flags.showExtraTime && !isScorePairValid(row.homeExtraScore, row.awayExtraScore)) {
    return false
  }
  if (flags.showPenalties && !isScorePairValid(row.homePenaltyScore, row.awayPenaltyScore)) {
    return false
  }
  return true
}

/**
 * 入力済みセット（home/away 両方非負整数）だけを setNumber 昇順で抽出する。
 * 片方のみ入力・空セットは除外する（送信対象外）。
 */
export function collectFilledSets(sets: ScoreEntrySet[]): MatchSetEntryPayload[] {
  const filled: MatchSetEntryPayload[] = []
  for (const s of sets) {
    const home = parseScoreInput(s.home)
    const away = parseScoreInput(s.away)
    if (home == null || away == null) continue
    filled.push({ setNumber: s.setNumber, homeScore: home, awayScore: away })
  }
  filled.sort((a, b) => a.setNumber - b.setNumber)
  return filled
}

/**
 * 入力行から送信対象（本戦 home/away 両方入力済みの行）だけを抽出してバッチペイロードを組み立てる。
 *
 * - 両方未入力の行はスキップ（未消化の試合を 0-0 で確定させない）。
 * - 各エントリに version を必ず同梱する（楽観ロック・Wave3a #1459 を壊さない）。
 * - flags で延長/PK が有効な大会のみ extra/penalty を同梱する。フラグ false の欄は常に null。
 * - 不正行（片方のみ・非数値等）が 1 つでもあれば null を返す（呼び出し側で保存を中断する）。
 */
export function buildBatchScorePayload(
  rows: ScoreEntryRow[],
  flags: ScoreEntryColumnFlags = DEFAULT_COLUMN_FLAGS,
): BatchScorePayload | null {
  const scores: BatchScoreEntryPayload[] = []
  for (const row of rows) {
    if (!isRowValid(row, flags)) return null

    if (flags.showSets) {
      // セット制: 入力済みセットのみ抽出し sets として同梱する。
      // 本戦 home/away はセット得点の合計を自動算出して載せる（タイブレーク安全既定）。
      // セットが 1 つも入力されていない試合はスキップ（未消化を確定させない）。
      const sets = collectFilledSets(row.sets)
      if (sets.length === 0) continue
      const homeSum = sets.reduce((acc, s) => acc + s.homeScore, 0)
      const awaySum = sets.reduce((acc, s) => acc + s.awayScore, 0)
      scores.push({
        matchId: row.matchId,
        homeScore: homeSum,
        awayScore: awaySum,
        homeExtraScore: null,
        awayExtraScore: null,
        homePenaltyScore: null,
        awayPenaltyScore: null,
        version: row.version,
        sets,
      })
      continue
    }

    const home = parseScoreInput(row.homeScore)
    const away = parseScoreInput(row.awayScore)
    // 本戦が両方未入力はスキップ（送信対象外）
    if (home == null && away == null) continue
    scores.push({
      matchId: row.matchId,
      homeScore: home,
      awayScore: away,
      homeExtraScore: flags.showExtraTime ? parseScoreInput(row.homeExtraScore) : null,
      awayExtraScore: flags.showExtraTime ? parseScoreInput(row.awayExtraScore) : null,
      homePenaltyScore: flags.showPenalties ? parseScoreInput(row.homePenaltyScore) : null,
      awayPenaltyScore: flags.showPenalties ? parseScoreInput(row.awayPenaltyScore) : null,
      version: row.version,
    })
  }
  return { scores }
}

/**
 * 大会の採点設定（TournamentScoringDto）から入力欄の出し分けフラグを導く。
 *
 * - セット制（hasSets）のときは showSets を立て、setsToWin（先取制）をフラグへ載せる。
 *   setsToWin が未指定 / 不正のときは安全側で 1 にフォールバックする。
 * - セット制では延長/PK は使わない（勝敗はセット数で決まる）ため showExtraTime/showPenalties は折る。
 */
export function deriveScoreEntryColumnFlags(scoring: {
  hasExtraTime?: boolean
  hasPenalties?: boolean
  hasSets?: boolean
  setsToWin?: number | null
} | null | undefined): ScoreEntryColumnFlags {
  const showSets = scoring?.hasSets === true
  const rawSetsToWin = scoring?.setsToWin
  const setsToWin = typeof rawSetsToWin === 'number' && Number.isInteger(rawSetsToWin) && rawSetsToWin >= 1
    ? rawSetsToWin
    : 1
  return {
    showExtraTime: !showSets && scoring?.hasExtraTime === true,
    showPenalties: !showSets && scoring?.hasPenalties === true,
    showSets,
    setsToWin,
  }
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
