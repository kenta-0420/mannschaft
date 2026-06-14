/**
 * F08.10 ターン制（TURN_BASED）の状態管理 composable（sports/05_shogi.md §8.5 / sports/06_go.md §8.5）。
 *
 * 将棋・囲碁はタイマー（時間ベース）を使わず、
 * 「勝者サイド・勝ち方・総手数（任意）・目数差（囲碁のみ・任意）・局面写真・コメント」を管理する。
 * これは useMatchTimerCore / useMatchSetTracker に相当するターン制専用 composable。
 *
 * ## 状態機械（§8.5）
 * ```
 * WAITING → IN_PROGRESS → COMPLETED
 * ```
 * - WAITING: 対局前（未開始）
 * - IN_PROGRESS: 対局中（勝者・勝ち方の入力が可能）
 * - COMPLETED: 対局確定（勝者・勝ち方が確定済み。引分け含む）
 *
 * ## 勝敗格納規約（コア §B.1.2）
 * - 勝者: home_score=1 / away_score=0 → HOME 勝ち
 * - 勝者: home_score=0 / away_score=1 → AWAY 勝ち
 * - 引分（千日手/持将棋/持碁）: home_score=0 / away_score=0 + win_method=null
 *
 * 【前向きユニオン境界】
 * BE が Sport enum を SHOGI/GO へ拡張し openapi 再生成後、AllSport へ統合する。
 * 現時点の境界は sportModuleRegistry の REGISTRY・AllSport 定義の 2 箇所のみ（any 禁止）。
 * TODO: BE openapi 再生成後に LiveSport ユニオンへ統合
 */
import { computed, ref } from 'vue'

/** ターン制の勝者サイド（null=引分け）。 */
export type TurnWinnerSide = 'HOME' | 'AWAY' | null

/**
 * 将棋の勝ち方（ShogiWinMethod・sports/05_shogi.md §4.1）。
 * 引分け（千日手/持将棋）は winner=null で表現し、win_method=null となる。
 */
export type ShogiWinMethod =
  | 'RESIGNATION' // 投了
  | 'CHECKMATE' // 詰み
  | 'TIMEOUT' // 時間切れ
  | 'FOUL_WIN' // 反則勝ち
  | 'REPETITION' // 千日手（引分けにもなり得る）
  | 'IMPASSE' // 持将棋（引分けにもなり得る）
  | 'DEFAULT_WIN' // 不戦勝

/**
 * 囲碁の勝ち方（GoWinMethod・sports/06_go.md §4.1）。
 * 引分け（持碁）は winner=null + win_method=null で表現する。
 */
export type GoWinMethod =
  | 'RESIGNATION' // 投了（中押し勝ち）
  | 'POINTS_WIN' // 目数差勝ち（margin を任意保持）
  | 'TIMEOUT' // 時間切れ
  | 'FOUL_WIN' // 反則勝ち
  | 'DEFAULT_WIN' // 不戦勝

/** ターン制の勝ち方（将棋・囲碁共通のユニオン）。 */
export type TurnWinMethod = ShogiWinMethod | GoWinMethod

/** ターン制トラッカーの状態（§8.5 の状態機械）。 */
export type TurnTrackerState = 'WAITING' | 'IN_PROGRESS' | 'COMPLETED'

/** useMatchTurnTracker の初期化オプション。 */
export interface UseMatchTurnTrackerOptions {
  /**
   * 競技識別（将棋/囲碁）。
   * 囲碁のみ目数差（margin）入力が有効。
   */
  sport?: 'SHOGI' | 'GO' | string
}

// ===== ピュア関数（テスト可能・副作用なし） =====

/**
 * 勝者サイドとスコア列への格納値を算出（コア §B.1.2）。
 * - HOME 勝ち: { homeScore: 1, awayScore: 0 }
 * - AWAY 勝ち: { homeScore: 0, awayScore: 1 }
 * - 引分け:    { homeScore: 0, awayScore: 0 }
 */
export function resolveScoreFromWinner(winnerSide: TurnWinnerSide): {
  homeScore: number
  awayScore: number
} {
  if (winnerSide === 'HOME') return { homeScore: 1, awayScore: 0 }
  if (winnerSide === 'AWAY') return { homeScore: 0, awayScore: 1 }
  return { homeScore: 0, awayScore: 0 } // 引分け
}

/**
 * 勝者サイドと勝ち方の組み合わせが有効か検証（🟡 6-④c BE 整合・MATCH_028）。
 *
 * BE は引分（winnerSide=null）のとき win_method 非 NULL を 400(MATCH_028) で弾く
 * （勝敗＝スコア・勝ち方＝enum の責務分離・§4.2）。FE もこれに合わせ、引分時は
 * win_method を null に限る（千日手/持碁の UI 選択は「引分の種類ラベル」止まりとし、
 * 送信ペイロードでは buildTurnResultPayload が win_method を落とす）。
 * - winnerSide=null（引分け）のとき win_method は null のみ有効。
 * - winnerSide 非 null のとき win_method は null（未入力）または任意の勝ち方。
 */
export function isValidTurnResult(
  winnerSide: TurnWinnerSide,
  winMethod: TurnWinMethod | null,
): boolean {
  if (winnerSide === null) {
    // 引分け: win_method は null のみ（BE MATCH_028 整合）
    return winMethod === null
  }
  // 勝者あり: 何でも OK（未入力 null 含む）
  return true
}

/**
 * 団体戦のボード勝ち星を集計して親 match の勝者サイドを導出（コア §B.6・sports/05_shogi.md §4.3）。
 *
 * 各ボードの `winnerSide`:
 * - HOME: home に 2 点（整数スケール）
 * - AWAY: away に 2 点
 * - null（引分け）: home/away それぞれ 1 点（0.5 勝 × 2 スケール）
 *
 * 合計 home > away → HOME 勝ち、away > home → AWAY 勝ち、同数 → 引分け（null）。
 */
export function aggregateTeamMatchWinner(
  boards: ReadonlyArray<{ winnerSide: TurnWinnerSide; confirmed: boolean }>,
): TurnWinnerSide {
  const confirmedBoards = boards.filter((b) => b.confirmed)
  let homePoints = 0
  let awayPoints = 0
  for (const b of confirmedBoards) {
    if (b.winnerSide === 'HOME') homePoints += 2
    else if (b.winnerSide === 'AWAY') awayPoints += 2
    else {
      // 引分け: 0.5 勝ずつ（整数スケール ×2）
      homePoints += 1
      awayPoints += 1
    }
  }
  if (homePoints > awayPoints) return 'HOME'
  if (awayPoints > homePoints) return 'AWAY'
  return null // 同数=引分け
}

/**
 * 団体戦のボード進捗サマリ（n/N・確定状況・§G.16a）。
 *
 * @returns confirmedCount: 確定済みボード数, totalCount: 総ボード数
 */
export function boardProgressSummary(
  boards: ReadonlyArray<{ confirmed: boolean }>,
): { confirmedCount: number; totalCount: number } {
  return {
    confirmedCount: boards.filter((b) => b.confirmed).length,
    totalCount: boards.length,
  }
}

// ===== composable =====

export function useMatchTurnTracker(options: UseMatchTurnTrackerOptions = {}) {
  const sport = options.sport ?? 'SHOGI'

  /** 現在のトラッカー状態（§8.5 の状態機械）。 */
  const trackerState = ref<TurnTrackerState>('WAITING')

  /** 勝者サイド（null=引分け・未入力）。 */
  const winnerSide = ref<TurnWinnerSide>(null)

  /** 勝ち方（null=未入力 or 引分け）。 */
  const winMethod = ref<TurnWinMethod | null>(null)

  /** 総手数（任意・ターン制固有）。 */
  const totalMoves = ref<number | null>(null)

  /**
   * 目数差（囲碁のみ・POINTS_WIN 時の任意入力）。
   * 将棋では使用しない（null 固定）。
   */
  const margin = ref<number | null>(null)

  /** コメント（note・任意自由記述）。 */
  const comment = ref<string>('')

  /** 局面写真の添付情報（presign 方式・任意）。 */
  const positionPhotos = ref<PositionPhoto[]>([])

  /** 対局が終了しているか（COMPLETED 遷移済み）。 */
  const isCompleted = computed<boolean>(() => trackerState.value === 'COMPLETED')

  /** 引分けか（winnerSide=null かつ COMPLETED）。 */
  const isDraw = computed<boolean>(() => isCompleted.value && winnerSide.value === null)

  /** 結果確定ができるか（COMPLETED 遷移の前提条件）。
   * - winnerSide が選択済み（HOME/AWAY/null=引分け選択）
   * - IN_PROGRESS 状態
   * 勝ち方・手数・写真は任意なので確定のブロッカーにしない（ADHD 配慮）。
   */
  const canComplete = computed<boolean>(() => {
    // IN_PROGRESS かつ「勝者を選択したか引分けを選択した」= resultSelected が true
    return trackerState.value === 'IN_PROGRESS' && resultSelected.value
  })

  /**
   * 勝者または引分けが選択済みか。
   * 「HOME 勝ち」「AWAY 勝ち」「引分け（明示的に選択）」のいずれかが選択されたとき true。
   * WAITING 初期状態では winnerSide=null は「未選択」を意味するため、
   * 引分けは専用の isDraw フラグで明示する（winnerSide=null + drawSelected=true）。
   */
  const drawSelected = ref<boolean>(false)
  const resultSelected = computed<boolean>(() => {
    return winnerSide.value !== null || drawSelected.value
  })

  /** 囲碁か（GO のみ margin 入力が有効）。 */
  const isGo = computed<boolean>(() => sport === 'GO')

  // ===== スコア算出 =====

  /** 確定済みスコア（home_score / away_score）。COMPLETED 前は null。 */
  const resolvedScore = computed<{ homeScore: number; awayScore: number } | null>(() => {
    if (!isCompleted.value) return null
    const side = winnerSide.value
    return resolveScoreFromWinner(side)
  })

  // ===== 状態遷移 =====

  /** 対局開始（WAITING → IN_PROGRESS）。 */
  function start(): void {
    if (trackerState.value !== 'WAITING') return
    trackerState.value = 'IN_PROGRESS'
  }

  /**
   * 結果を確定し対局完了（IN_PROGRESS → COMPLETED）。
   * 勝者が選択されていない場合は何もしない。
   */
  function complete(): void {
    if (!canComplete.value) return
    trackerState.value = 'COMPLETED'
  }

  /**
   * 引分けを選択（千日手/持将棋/持碁）。
   * winnerSide=null を明示的に「引分け選択」として扱う。
   */
  function selectDraw(): void {
    winnerSide.value = null
    drawSelected.value = true
  }

  /** HOME 勝ちを選択。 */
  function selectHomeWin(): void {
    winnerSide.value = 'HOME'
    drawSelected.value = false
  }

  /** AWAY 勝ちを選択。 */
  function selectAwayWin(): void {
    winnerSide.value = 'AWAY'
    drawSelected.value = false
  }

  /** 勝ち方を設定。 */
  function setWinMethod(method: TurnWinMethod | null): void {
    winMethod.value = method
  }

  /** 総手数を設定（任意・0 以下は null に丸める）。 */
  function setTotalMoves(moves: number | null): void {
    if (moves === null || moves <= 0) {
      totalMoves.value = null
    } else {
      totalMoves.value = Math.floor(moves)
    }
  }

  /** 目数差を設定（囲碁のみ有効・任意）。 */
  function setMargin(m: number | null): void {
    if (!isGo.value) return
    margin.value = m
  }

  /** コメントを設定。 */
  function setComment(text: string): void {
    comment.value = text
  }

  /** 局面写真を追加（presign 方式）。 */
  function addPositionPhoto(photo: PositionPhoto): void {
    positionPhotos.value.push(photo)
  }

  /** 局面写真を削除。 */
  function removePositionPhoto(key: string): void {
    positionPhotos.value = positionPhotos.value.filter((p) => p.key !== key)
  }

  /** 選択をリセット（COMPLETED 前のみ）。 */
  function resetResult(): void {
    if (trackerState.value === 'COMPLETED') return
    winnerSide.value = null
    drawSelected.value = false
    winMethod.value = null
  }

  /** 状態を復元する（既存試合の再開時・BE レスポンスから初期化）。 */
  function restore(restored: {
    state?: TurnTrackerState
    winnerSide?: TurnWinnerSide
    winMethod?: TurnWinMethod | null
    totalMoves?: number | null
    margin?: number | null
    comment?: string
    photos?: PositionPhoto[]
  }): void {
    if (restored.state) trackerState.value = restored.state
    if (restored.winnerSide !== undefined) {
      winnerSide.value = restored.winnerSide
      if (restored.winnerSide === null && restored.state === 'COMPLETED') {
        drawSelected.value = true
      }
    }
    if (restored.winMethod !== undefined) winMethod.value = restored.winMethod ?? null
    if (restored.totalMoves !== undefined) totalMoves.value = restored.totalMoves ?? null
    if (restored.margin !== undefined) margin.value = restored.margin ?? null
    if (restored.comment !== undefined) comment.value = restored.comment
    if (restored.photos !== undefined) positionPhotos.value = restored.photos
  }

  return {
    // 設定
    sport,
    isGo,
    // 状態
    trackerState,
    winnerSide,
    winMethod,
    totalMoves,
    margin,
    comment,
    positionPhotos,
    drawSelected,
    // 算出
    isCompleted,
    isDraw,
    canComplete,
    resultSelected,
    resolvedScore,
    // 遷移・操作
    start,
    complete,
    selectHomeWin,
    selectAwayWin,
    selectDraw,
    setWinMethod,
    setTotalMoves,
    setMargin,
    setComment,
    addPositionPhoto,
    removePositionPhoto,
    resetResult,
    restore,
  }
}

export type MatchTurnTrackerReturn = ReturnType<typeof useMatchTurnTracker>

// ===== 局面写真型定義 =====

/** 局面写真の添付情報（presign 方式・コア §B.7）。 */
export interface PositionPhoto {
  /** ストレージのオブジェクトキー（presign URL の基）。 */
  key: string
  /** 表示用 URL（署名済み一時 URL）。 */
  url: string
  /** ファイル名（表示用）。 */
  filename: string
}
