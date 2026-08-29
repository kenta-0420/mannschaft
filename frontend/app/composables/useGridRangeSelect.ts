/**
 * F03.19 §6.6 グリッド選択による予定作成 — ジェスチャの状態機械。
 *
 * 週ビュー（`CalendarWeekGrid.vue`）の時間グリッドをなぞって時間範囲を選ぶ操作を、
 * **描画から切り離して**保持する。コンポーネントは「クライアント座標 → グリッド上の点」の
 * 変換（{@link UseGridRangeSelectOptions.resolvePoint}）だけを与え、
 * 選択の開始条件・スナップ・正規化・モバイルの長押し判定・自動スクロールは全てここで行う。
 *
 * **キーボード経路（§6.7・W3-a4）を後から同じ出口に差し込めるようにしてある**:
 * {@link UseGridRangeSelect.beginAt} / {@link UseGridRangeSelect.extendTo} /
 * {@link UseGridRangeSelect.commit} はポインタから独立しており、
 * どの経路を通っても最終的に同一の `onCommit`（＝同一の `rangeSelect` emit）へ落ちる。
 */
import { MINUTES_PER_DAY } from '~/utils/calendarWeek'

/** グリッド上の1点。`minutes` はその日の 0:00 からの分（スナップ前の生値）。 */
export interface GridPoint {
  dayIndex: number
  minutes: number
}

/** 確定した選択範囲。**必ず同一日に閉じる**（§6.6.3・日をまたぐ選択はしない）。 */
export interface GridRange {
  dayIndex: number
  startMin: number
  endMin: number
}

/** 長押し成立までの時間(ms)（§6.6.4-2）。 */
export const LONG_PRESS_MS = 500
/** 長押し待機中にこの距離(px)以上動いたらスクロールと見なしてタイマーを破棄する（§6.6.4-2）。 */
export const TOUCH_CANCEL_PX = 10
/** PC でこの距離(px)未満ならドラッグではなく単クリック扱い（§6.6.2）。 */
export const CLICK_THRESHOLD_PX = 4
/** 単クリック時の既定の長さ(分)（§6.6.2）。 */
export const DEFAULT_DURATION_MIN = 60
/** 最小選択長(分)（§6.6.3）。 */
export const MIN_RANGE_MIN = 15
/** 自動スクロールを始めるコンテナ端からの距離(px)（§6.6.3）。 */
export const AUTOSCROLL_EDGE_PX = 40
/** 自動スクロールの1フレームあたりの移動量(px)（§6.6.3）。 */
export const AUTOSCROLL_STEP_PX = 8

/**
 * スナップ単位を**表示密度から導出**する（§6.6.3 の表）。
 *
 * 15分あたりの物理ピクセルが 8px を切る（＝1時間 < 32px）と指で狙えないため 30分へ粗くする。
 * 将来ズームアウトを入れたときに定数を書き換えずに済むよう、密度の関数として持つ。
 */
export function snapMinutesForDensity(hourHeightPx: number): number {
  return hourHeightPx < 32 ? 30 : MIN_RANGE_MIN
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * スナップ境界へ丸める。**最も近い境界へ**（`Math.round`）であって切り捨てではない（§6.6.3）。
 * 切り捨てにすると 9:59 から下へなぞったとき 9:45 開始になり直感に反する。
 */
export function snapToBoundary(minutes: number, snap: number): number {
  return clamp(Math.round(clamp(minutes, 0, MINUTES_PER_DAY) / snap) * snap, 0, MINUTES_PER_DAY)
}

/**
 * 2点から正の範囲を作る（§6.6.3）。
 *
 * - 両端に独立してスナップを適用する
 * - 逆方向（下から上）へ引かれていれば入れ替えて `start > end` を作らない
 * - 逆方向に 0 まで戻し切っても最小長を維持する
 * - 24:00 を越えない（越える場合は 24:00 で止め、最小長は下側へ確保する）
 */
export function normalizeRange(
  anchorMinutes: number,
  currentMinutes: number,
  snap: number,
): { startMin: number; endMin: number } {
  const minLength = Math.max(MIN_RANGE_MIN, snap)
  const a = snapToBoundary(anchorMinutes, snap)
  const b = snapToBoundary(currentMinutes, snap)
  let startMin = Math.min(a, b)
  let endMin = Math.max(a, b)
  if (endMin - startMin < minLength) {
    endMin = startMin + minLength
    if (endMin > MINUTES_PER_DAY) {
      endMin = MINUTES_PER_DAY
      startMin = endMin - minLength
    }
  }
  return { startMin, endMin }
}

/**
 * 単クリックの範囲（その位置から既定60分・§6.6.2）。24:00 を越えたら 24:00 で止める。
 *
 * **正規化は {@link normalizeRange} に委ねる。** 自前で `Math.min(MINUTES_PER_DAY, ...)` を
 * 掛けると、23:53 のように開始が 24:00 へ丸まる位置で開始と終了が同値になり、
 * **ゼロ分の予定**が確定してしまう（§6.6.3 の「最小選択長は15分」に反する）。
 * normalizeRange は 24:00 のクランプと最小長の確保を両方持っているため、
 * 「この位置から60分」を1本の範囲として通せば、末端でも自動的に
 * 23:45–24:00 のような正の範囲へ収まる。判定を二重に持たないことが肝要。
 */
export function defaultRangeFrom(minutes: number, snap: number): { startMin: number; endMin: number } {
  return normalizeRange(minutes, minutes + DEFAULT_DURATION_MIN, snap)
}

export interface UseGridRangeSelectOptions {
  /**
   * ジェスチャを受け付けてよいか（§6.6.2・**週ビュー限定**）。
   * 月ビュー／アジェンダビューでは常に `false` を返させ、ジェスチャ自体を成立させない。
   */
  enabled: () => boolean
  /** 現在のスナップ単位(分)。{@link snapMinutesForDensity} の結果を渡す。 */
  snapMinutes: () => number
  /** クライアント座標 → グリッド上の点。グリッド外なら `null`。 */
  resolvePoint: (clientX: number, clientY: number) => GridPoint | null
  /** 自動スクロール対象の要素（時間グリッドのスクロールコンテナ）。 */
  scrollEl: () => HTMLElement | null
  /** 範囲が確定したときに呼ばれる唯一の出口。ドラッグ・キーボードのどちらからも同じここへ来る。 */
  onCommit: (range: GridRange) => void
  /** 触覚フィードバック（§6.6.4-3）。既定は `navigator.vibrate`。**非対応環境では何も起きない**。 */
  vibrate?: (durationMs: number) => void
}

export interface UseGridRangeSelect {
  /** 選択中の範囲。null なら非選択（ハイライトを描かない）。 */
  selection: Readonly<Ref<GridRange | null>>
  /** 選択モードに入っているか（`touch-action: none` の切替に使う・§6.6.4-4）。 */
  isSelecting: Readonly<Ref<boolean>>
  onPointerDown: (event: PointerEvent) => void
  onTouchStart: (event: TouchEvent) => void
  /** キーボード経路（W3-a4）の差込口。ポインタから独立している。 */
  beginAt: (point: GridPoint) => void
  extendTo: (minutes: number) => void
  commit: () => void
  cancel: () => void
}

type Phase = 'idle' | 'pendingTouch' | 'active'

/**
 * 選択を開始してはならない領域（§6.6.2）に居るか。
 *
 * 既存の予定バー・終日帯・時刻ラベル列には `data-range-select-ignore` を付けてあり、
 * その内側から始まったジェスチャは一切拾わない。**祖先を辿る**ので、
 * バー内のアイコンやテキストを掴んでも同じく弾かれる。
 */
function isIgnoredTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  return target.closest('[data-range-select-ignore]') !== null
}

export function useGridRangeSelect(options: UseGridRangeSelectOptions): UseGridRangeSelect {
  const selection = ref<GridRange | null>(null)
  const isSelecting = ref(false)

  let phase: Phase = 'idle'
  /** 選択を開始した列（＝日）。**ドラッグ中に横へ移っても書き換えない**（§6.6.3）。 */
  let anchorDayIndex = 0
  let anchorMinutes = 0
  let originX = 0
  let originY = 0
  /** PC: 閾値を越えて動いたか。越えていなければ単クリック扱い（§6.6.2）。 */
  let movedBeyondThreshold = false
  /** 自動スクロールの再評価に使う直近のクライアント座標。 */
  let lastClientX = 0
  let lastClientY = 0
  let longPressTimerId: ReturnType<typeof setTimeout> | null = null
  let autoScrollRafId: number | null = null

  function clearLongPressTimer(): void {
    if (longPressTimerId !== null) {
      clearTimeout(longPressTimerId)
      longPressTimerId = null
    }
  }

  function stopAutoScroll(): void {
    if (autoScrollRafId !== null) {
      cancelAnimationFrame(autoScrollRafId)
      autoScrollRafId = null
    }
  }

  function triggerVibrate(): void {
    const fn = options.vibrate
      ?? (typeof navigator !== 'undefined' ? navigator.vibrate?.bind(navigator) : undefined)
    // 非対応環境では `vibrate` 自体が存在しない。呼ばないだけで、例外にも警告にもしない（§6.6.1）。
    fn?.(10)
  }

  /** 現在位置から選択範囲を作り直す。**列は anchorDayIndex に固定**する（§6.6.3）。 */
  function updateSelectionFrom(clientX: number, clientY: number): void {
    const point = options.resolvePoint(clientX, clientY)
    if (!point) return
    const { startMin, endMin } = normalizeRange(anchorMinutes, point.minutes, options.snapMinutes())
    selection.value = { dayIndex: anchorDayIndex, startMin, endMin }
  }

  /**
   * 上端・下端を越えるドラッグで時間グリッドを自動スクロールする（§6.6.3）。
   * 端から 40px 以内で毎フレーム 8px。スクロール後は同じ指の位置で範囲を取り直すため、
   * 指を止めていても選択が伸び続ける。24:00 を超えた側は {@link normalizeRange} が止める。
   */
  function autoScrollTick(): void {
    autoScrollRafId = null
    if (phase !== 'active') return
    const el = options.scrollEl()
    if (el) {
      const rect = el.getBoundingClientRect()
      let delta = 0
      if (lastClientY < rect.top + AUTOSCROLL_EDGE_PX) delta = -AUTOSCROLL_STEP_PX
      else if (lastClientY > rect.bottom - AUTOSCROLL_EDGE_PX) delta = AUTOSCROLL_STEP_PX
      if (delta !== 0) {
        const maxTop = Math.max(0, el.scrollHeight - el.clientHeight)
        const next = clamp(el.scrollTop + delta, 0, maxTop)
        if (next !== el.scrollTop) {
          el.scrollTop = next
          updateSelectionFrom(lastClientX, lastClientY)
        }
      }
    }
    autoScrollRafId = requestAnimationFrame(autoScrollTick)
  }

  function startAutoScroll(): void {
    if (autoScrollRafId === null && typeof requestAnimationFrame === 'function') {
      autoScrollRafId = requestAnimationFrame(autoScrollTick)
    }
  }

  function finish(): void {
    clearLongPressTimer()
    stopAutoScroll()
    detachPointerListeners()
    detachTouchListeners()
    phase = 'idle'
    isSelecting.value = false
  }

  function cancel(): void {
    finish()
    selection.value = null
  }

  function commit(): void {
    const range = selection.value
    finish()
    selection.value = null
    if (range) options.onCommit(range)
  }

  // ---- キーボード経路（§6.7・W3-a4）の差込口 ----
  function beginAt(point: GridPoint): void {
    if (!options.enabled()) return
    anchorDayIndex = point.dayIndex
    anchorMinutes = point.minutes
    const { startMin, endMin } = normalizeRange(point.minutes, point.minutes, options.snapMinutes())
    selection.value = { dayIndex: point.dayIndex, startMin, endMin }
  }

  function extendTo(minutes: number): void {
    if (!selection.value) return
    const { startMin, endMin } = normalizeRange(anchorMinutes, minutes, options.snapMinutes())
    selection.value = { dayIndex: anchorDayIndex, startMin, endMin }
  }

  // ---- PC（マウス／ペン） ----
  function onPointerMove(event: PointerEvent): void {
    if (phase !== 'active') return
    lastClientX = event.clientX
    lastClientY = event.clientY
    if (Math.hypot(event.clientX - originX, event.clientY - originY) >= CLICK_THRESHOLD_PX) {
      movedBeyondThreshold = true
    }
    if (movedBeyondThreshold) updateSelectionFrom(event.clientX, event.clientY)
    startAutoScroll()
  }

  function onPointerUp(): void {
    if (phase !== 'active') return
    if (!movedBeyondThreshold) {
      // 閾値未満 ＝ 単クリック。その位置の時刻から既定60分（§6.6.2）。
      const { startMin, endMin } = defaultRangeFrom(anchorMinutes, options.snapMinutes())
      selection.value = { dayIndex: anchorDayIndex, startMin, endMin }
    }
    commit()
  }

  function attachPointerListeners(): void {
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', onPointerUp)
    window.addEventListener('pointercancel', cancel)
  }

  function detachPointerListeners(): void {
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', cancel)
  }

  function onPointerDown(event: PointerEvent): void {
    // タッチは長押し経路（onTouchStart）が受け持つ。ここで拾うと長押し前に選択が始まりスクロールが死ぬ。
    if (event.pointerType === 'touch') return
    if (!options.enabled() || isIgnoredTarget(event.target)) return
    if (event.button !== undefined && event.button !== 0) return
    const point = options.resolvePoint(event.clientX, event.clientY)
    if (!point) return

    phase = 'active'
    isSelecting.value = true
    anchorDayIndex = point.dayIndex
    anchorMinutes = point.minutes
    originX = event.clientX
    originY = event.clientY
    lastClientX = event.clientX
    lastClientY = event.clientY
    movedBeyondThreshold = false
    const { startMin, endMin } = normalizeRange(point.minutes, point.minutes, options.snapMinutes())
    selection.value = { dayIndex: point.dayIndex, startMin, endMin }
    attachPointerListeners()
  }

  // ---- モバイル（タッチ・§6.6.4） ----
  function onTouchMove(event: TouchEvent): void {
    const touch = event.touches[0]
    if (!touch) return
    if (phase === 'pendingTouch') {
      // 【要】ここでは **preventDefault を呼ばない**。長押し成立前はブラウザの慣性スクロールが最優先。
      if (Math.hypot(touch.clientX - originX, touch.clientY - originY) >= TOUCH_CANCEL_PX) {
        // 10px 以上動いた ＝ スクロール意図。タイマーを破棄して通常のスクロールへ戻す（§6.6.4-2）。
        finish()
      }
      return
    }
    if (phase !== 'active') return
    // 長押しが成立して初めてスクロールを止める（§6.6.4-3）。ここより早く呼ぶとスクロールが死ぬ。
    if (event.cancelable) event.preventDefault()
    lastClientX = touch.clientX
    lastClientY = touch.clientY
    movedBeyondThreshold = true
    updateSelectionFrom(touch.clientX, touch.clientY)
    startAutoScroll()
  }

  function onTouchEnd(event: TouchEvent): void {
    if (phase === 'pendingTouch') {
      // 500ms 経つ前に離れた ＝ タップ／スクロール。選択は始めない（§6.6.4-2・AC-22）。
      finish()
      return
    }
    if (phase !== 'active') return
    // 直後に合成される click が下の要素へ届かないようにする（成立後なので既定動作を潰してよい）。
    if (event.cancelable) event.preventDefault()
    commit()
  }

  function attachTouchListeners(): void {
    // `passive: false` でなければ `preventDefault()` が無視される。**成立後に効かせるための前提**。
    window.addEventListener('touchmove', onTouchMove, { passive: false })
    window.addEventListener('touchend', onTouchEnd, { passive: false })
    window.addEventListener('touchcancel', cancel)
  }

  function detachTouchListeners(): void {
    window.removeEventListener('touchmove', onTouchMove)
    window.removeEventListener('touchend', onTouchEnd)
    window.removeEventListener('touchcancel', cancel)
  }

  function onTouchStart(event: TouchEvent): void {
    if (!options.enabled() || isIgnoredTarget(event.target)) return
    const touch = event.touches[0]
    if (!touch) return
    const point = options.resolvePoint(touch.clientX, touch.clientY)
    if (!point) return

    // §6.6.4-1: この時点では**何もしない**。preventDefault も呼ばない。
    phase = 'pendingTouch'
    anchorDayIndex = point.dayIndex
    anchorMinutes = point.minutes
    originX = touch.clientX
    originY = touch.clientY
    lastClientX = touch.clientX
    lastClientY = touch.clientY
    movedBeyondThreshold = false
    attachTouchListeners()

    clearLongPressTimer()
    longPressTimerId = setTimeout(() => {
      longPressTimerId = null
      if (phase !== 'pendingTouch') return
      // §6.6.4-3: 長押し成立。ここで初めて触覚フィードバック＋ハイライト＋選択モード。
      phase = 'active'
      isSelecting.value = true
      const { startMin, endMin } = normalizeRange(anchorMinutes, anchorMinutes, options.snapMinutes())
      selection.value = { dayIndex: anchorDayIndex, startMin, endMin }
      triggerVibrate()
    }, LONG_PRESS_MS)
  }

  // 後始末（タイマー・rAF・window リスナ。1つでも残すとアンマウント済みの ref を触り続ける）。
  onUnmounted(() => {
    clearLongPressTimer()
    stopAutoScroll()
    detachPointerListeners()
    detachTouchListeners()
  })

  return {
    selection: readonly(selection) as Readonly<Ref<GridRange | null>>,
    isSelecting: readonly(isSelecting),
    onPointerDown,
    onTouchStart,
    beginAt,
    extendTo,
    commit,
    cancel,
  }
}
