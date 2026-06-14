/**
 * F08.10 セット制（SET_BASED）の状態管理 composable（sports/04_volleyball.md §8.5）。
 *
 * バレーボールはタイマー（時間ベース）を使わず、「セット番号・各セットの得点・
 * デュース判定・勝ちセット数・試合終了判定」を管理する。
 * これはサッカー/バスケの useMatchTimerCore に相当するセット制専用 composable。
 *
 * ## 状態機械（§8.5）
 * ```
 * WAITING → SET_1 → SET_2 → SET_3 → [SET_4 → SET_5] → COMPLETED
 * ```
 * - 各セットは「進行中（得点入力可）→ デュース条件達成で確定可能 → 確定（次セットへ）」
 * - best-of-5: 先に 3 セット先取で試合終了。best-of-3 は 2 セット先取。
 *
 * ## セット確定ルール（§4.2）
 * - 通常セット（1〜4）: 25 点先取かつ 2 点差（デュース解消）
 * - 第 5 セット（ファイナルセット）: 15 点先取かつ 2 点差
 *
 * 【状態モデル分岐】
 * live.vue の共通シェルは SportTimer 型（連続時間制）を期待するが、
 * SET_BASED モジュールは SportTimer を提供しない（タイマー不要）。
 * live.vue 側で isSetBased フラグを判定しセット制パスへ分岐する
 * （境界は live.vue の sportModule.stateModel チェック 1 箇所のみ）。
 * VOLLEYBALL は生成型 Sport へ統合済み（AllSport=Sport・本トラッカーは内部状態機械でありBE DTOではない）。
 */
import { computed, ref } from 'vue'

/** セット制の「セット番号」（1〜5）。 */
export type SetNumber = 1 | 2 | 3 | 4 | 5

/** 1 セットの状態。 */
export interface MatchSet {
  /** セット番号（1〜5）。 */
  setNumber: SetNumber
  /** ホームチームの当セット内得点。 */
  homePoints: number
  /** アウェイチームの当セット内得点。 */
  awayPoints: number
  /** 確定済みか（SET_END 確定後は true）。 */
  confirmed: boolean
  /** 勝者サイド（confirmed 時のみ非 null）。 */
  winnerSide: 'HOME' | 'AWAY' | null
}

/** セット制トラッカーの状態（§8.5 の状態機械）。 */
export type SetTrackerState =
  | 'WAITING'
  | 'SET_1'
  | 'SET_2'
  | 'SET_3'
  | 'SET_4'
  | 'SET_5'
  | 'COMPLETED'

/** best-of: 何セット先取で試合終了か（best-of-5=3・best-of-3=2）。 */
export type BestOf = 3 | 5

/** useMatchSetTracker の初期化オプション。 */
export interface UseMatchSetTrackerOptions {
  /** best-of モード（デフォルト 5）。 */
  bestOf?: BestOf
}

// ===== ピュア関数（テスト可能・副作用なし） =====

/**
 * 指定セット番号のセット目標点数（通常 25・ファイナルセットのみ 15）。
 * bestOf=5 → 第 5 セットが 15 点、bestOf=3 → 第 3 セットが 15 点。
 */
export function setTargetPoints(setNumber: SetNumber, bestOf: BestOf): number {
  const finalSet = bestOf === 5 ? 5 : 3
  return setNumber === finalSet ? 15 : 25
}

/**
 * セット勝利条件を満たすか（§4.2）。
 * max(home, away) >= target かつ |home - away| >= 2
 */
export function isSetWon(
  homePoints: number,
  awayPoints: number,
  setNumber: SetNumber,
  bestOf: BestOf,
): boolean {
  const target = setTargetPoints(setNumber, bestOf)
  const maxPts = Math.max(homePoints, awayPoints)
  const diff = Math.abs(homePoints - awayPoints)
  return maxPts >= target && diff >= 2
}

/**
 * デュース状態か（目標点数 -1 以上かつ 2 点差未満）。
 * デュース = どちらも 24 点以上（通常セット）または 14 点以上（ファイナル）で
 * 2 点差がついていない状態。
 */
export function isDeuceState(
  homePoints: number,
  awayPoints: number,
  setNumber: SetNumber,
  bestOf: BestOf,
): boolean {
  const target = setTargetPoints(setNumber, bestOf)
  const deuce = target - 1 // 24 or 14
  return (
    homePoints >= deuce &&
    awayPoints >= deuce &&
    !isSetWon(homePoints, awayPoints, setNumber, bestOf)
  )
}

/**
 * セットトラッカーの次の状態へ進む（セット確定後に呼ぶ）。
 * COMPLETED になる条件: 勝ちセット数 >= 必要セット数（best-of-5 → 3・best-of-3 → 2）。
 */
export function nextSetState(
  current: SetTrackerState,
  setsToWin: number,
  homeWins: number,
  awayWins: number,
): SetTrackerState {
  if (current === 'WAITING') return 'SET_1'
  if (homeWins >= setsToWin || awayWins >= setsToWin) return 'COMPLETED'
  const map: Partial<Record<SetTrackerState, SetTrackerState>> = {
    SET_1: 'SET_2',
    SET_2: 'SET_3',
    SET_3: 'SET_4',
    SET_4: 'SET_5',
    SET_5: 'COMPLETED',
  }
  return map[current] ?? 'COMPLETED'
}

// ===== composable =====

export function useMatchSetTracker(options: UseMatchSetTrackerOptions = {}) {
  const bestOf: BestOf = options.bestOf ?? 5
  const setsToWin = bestOf === 5 ? 3 : 2

  /** 現在のトラッカー状態（§8.5 の状態機械）。 */
  const trackerState = ref<SetTrackerState>('WAITING')

  /** 全セットの記録（最大 5 セット）。 */
  const sets = ref<MatchSet[]>([])

  /** 現在進行中のセット番号（WAITING/COMPLETED は null）。 */
  const currentSetNumber = computed<SetNumber | null>(() => {
    if (trackerState.value === 'WAITING' || trackerState.value === 'COMPLETED') return null
    const n = Number(trackerState.value.replace('SET_', '')) as SetNumber
    return n >= 1 && n <= 5 ? n : null
  })

  /** 現在進行中のセット（currentSetNumber に対応する sets 要素）。 */
  const currentSet = computed<MatchSet | null>(() => {
    const n = currentSetNumber.value
    if (n === null) return null
    return sets.value.find((s) => s.setNumber === n) ?? null
  })

  /** ホームの獲得セット数。 */
  const homeWins = computed<number>(() =>
    sets.value.filter((s) => s.confirmed && s.winnerSide === 'HOME').length,
  )

  /** アウェイの獲得セット数。 */
  const awayWins = computed<number>(() =>
    sets.value.filter((s) => s.confirmed && s.winnerSide === 'AWAY').length,
  )

  /** 現在セットのデュース状態か。 */
  const isDeuce = computed<boolean>(() => {
    const s = currentSet.value
    if (!s) return false
    return isDeuceState(s.homePoints, s.awayPoints, s.setNumber, bestOf)
  })

  /** 現在セットの勝利条件を満たしているか（セット確定ボタン有効化の条件）。 */
  const canConfirmSet = computed<boolean>(() => {
    const s = currentSet.value
    if (!s || trackerState.value === 'WAITING' || trackerState.value === 'COMPLETED') return false
    return isSetWon(s.homePoints, s.awayPoints, s.setNumber, bestOf)
  })

  /** 試合が終了しているか（3 セット先取または COMPLETED 遷移済み）。 */
  const isCompleted = computed<boolean>(() => {
    return (
      trackerState.value === 'COMPLETED' ||
      homeWins.value >= setsToWin ||
      awayWins.value >= setsToWin
    )
  })

  /** セット制開始（WAITING → SET_1）。 */
  function startFirstSet(): void {
    if (trackerState.value !== 'WAITING') return
    trackerState.value = 'SET_1'
    sets.value = [
      {
        setNumber: 1,
        homePoints: 0,
        awayPoints: 0,
        confirmed: false,
        winnerSide: null,
      },
    ]
  }

  /** 現在セットのホーム得点を +1（詳細記録モード・副動線用）。 */
  function incrementHome(): void {
    const s = currentSet.value
    if (!s || s.confirmed) return
    s.homePoints += 1
  }

  /** 現在セットのアウェイ得点を +1（詳細記録モード・副動線用）。 */
  function incrementAway(): void {
    const s = currentSet.value
    if (!s || s.confirmed) return
    s.awayPoints += 1
  }

  /** 現在セットのホーム得点を -1（最小 0）。 */
  function decrementHome(): void {
    const s = currentSet.value
    if (!s || s.confirmed) return
    s.homePoints = Math.max(0, s.homePoints - 1)
  }

  /** 現在セットのアウェイ得点を -1（最小 0）。 */
  function decrementAway(): void {
    const s = currentSet.value
    if (!s || s.confirmed) return
    s.awayPoints = Math.max(0, s.awayPoints - 1)
  }

  /**
   * 現在セットのホーム得点を直接セット（ステッパー入力・主動線）。
   * 0 未満は 0 に丸める。
   */
  function setHomePoints(points: number): void {
    const s = currentSet.value
    if (!s || s.confirmed) return
    s.homePoints = Math.max(0, Math.floor(points))
  }

  /**
   * 現在セットのアウェイ得点を直接セット（ステッパー入力・主動線）。
   * 0 未満は 0 に丸める。
   */
  function setAwayPoints(points: number): void {
    const s = currentSet.value
    if (!s || s.confirmed) return
    s.awayPoints = Math.max(0, Math.floor(points))
  }

  /**
   * 現在セットを確定する（SET_END）。
   * デュース条件 isSetWon を満たさない場合は何もしない（記録ミスを弾く・§4.2）。
   * 確定後、次セット状態へ遷移し、必要なら新セット行を追加する。
   */
  function confirmCurrentSet(): void {
    if (!canConfirmSet.value) return
    const s = currentSet.value
    if (!s) return

    const winner: 'HOME' | 'AWAY' = s.homePoints > s.awayPoints ? 'HOME' : 'AWAY'
    s.confirmed = true
    s.winnerSide = winner

    const newHome = homeWins.value
    const newAway = awayWins.value

    const nextState = nextSetState(trackerState.value, setsToWin, newHome, newAway)
    trackerState.value = nextState

    if (nextState !== 'COMPLETED') {
      const nextSetNum = Number(nextState.replace('SET_', '')) as SetNumber
      if (!sets.value.find((ms) => ms.setNumber === nextSetNum)) {
        sets.value.push({
          setNumber: nextSetNum,
          homePoints: 0,
          awayPoints: 0,
          confirmed: false,
          winnerSide: null,
        })
      }
    }
  }

  /** 試合を手動で完了にする（全セット確定後または緊急）。 */
  function completeMatch(): void {
    trackerState.value = 'COMPLETED'
  }

  /** 状態を復元する（既存試合の再開時・BE レスポンスから初期化）。 */
  function restore(restored: {
    state?: SetTrackerState
    sets?: MatchSet[]
  }): void {
    if (restored.state) trackerState.value = restored.state
    if (restored.sets) sets.value = restored.sets
  }

  return {
    // 設定
    bestOf,
    setsToWin,
    // 状態
    trackerState,
    sets,
    // 算出
    currentSetNumber,
    currentSet,
    homeWins,
    awayWins,
    isDeuce,
    canConfirmSet,
    isCompleted,
    // 遷移・操作
    startFirstSet,
    incrementHome,
    incrementAway,
    decrementHome,
    decrementAway,
    setHomePoints,
    setAwayPoints,
    confirmCurrentSet,
    completeMatch,
    restore,
  }
}

export type MatchSetTrackerReturn = ReturnType<typeof useMatchSetTracker>
