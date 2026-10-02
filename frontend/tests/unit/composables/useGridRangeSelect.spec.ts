import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { GridPoint, GridRange, UseGridRangeSelect } from '~/composables/useGridRangeSelect'
import {
  CLICK_THRESHOLD_PX,
  LONG_PRESS_MS,
  MIN_RANGE_MIN,
  TOUCH_CANCEL_PX,
  defaultRangeFrom,
  normalizeRange,
  snapMinutesForDensity,
  snapToBoundary,
  useGridRangeSelect,
} from '~/composables/useGridRangeSelect'

/**
 * F03.19 §6.6 グリッド選択の受け入れテスト（AC-21d / AC-21e / AC-21f / AC-22a2）。
 *
 * **実際に起こりうる操作の形を再現する** — 指やマウスは「座標」しか持たないので、
 * テストも生の `pointerdown` / `touchstart` を dispatch し、composable が自分で
 * window に張ったリスナ経由で移動・離上を受け取らせる。ハンドラを直接呼ぶだけでは
 * 「リスナが張られていない」「passive で preventDefault が効かない」といった
 * 実機で起きる欠陥を素通りさせてしまう。
 */

/** 1時間 = 48px（既定密度）。1分 = 0.8px。 */
const MIN_H = 48 / 60
/** グリッド左上のクライアント座標（列幅 70px × 7 列 = 490px）。 */
const GRID_LEFT = 100
const GRID_TOP = 200
const COL_W = 70

function pointAt(clientX: number, clientY: number): GridPoint | null {
  const rawCol = Math.floor((clientX - GRID_LEFT) / COL_W)
  if (rawCol < 0 || rawCol > 6) return null
  return { dayIndex: rawCol, minutes: (clientY - GRID_TOP) / MIN_H }
}

/** 分 → その分を指すクライアント Y 座標。 */
const yOf = (minutes: number) => GRID_TOP + minutes * MIN_H
/** 曜日 → その列の中央を指すクライアント X 座標。 */
const xOf = (dayIndex: number) => GRID_LEFT + dayIndex * COL_W + COL_W / 2

interface Harness {
  api: UseGridRangeSelect
  committed: GridRange[]
  vibrateCalls: number[]
  scrollEl: HTMLElement
}

/**
 * マウント済みハーネスの後片付け表。
 *
 * **テスト間の汚染を防ぐために必須**。composable は `window` にリスナを張るため、
 * アンマウントし忘れたハーネスが次のテストの `touchmove` を掴み、選択モードのまま
 * `preventDefault()` を呼んでしまう（実際にこれで「成立前に preventDefault を呼ばない」
 * テストが偽の赤になった）。`window` は全テストで共有されている。
 */
const mountedHarnesses: Array<{ unmount: () => void }> = []

afterEach(() => {
  while (mountedHarnesses.length > 0) mountedHarnesses.pop()?.unmount()
})

/**
 * composable を実コンポーネントの setup 内で動かす。
 * `onUnmounted` の後始末を検証するには、本物のライフサイクルが要る。
 */
async function mountHarness(over: { enabled?: boolean; snap?: number; withVibrate?: boolean } = {}) {
  const committed: GridRange[] = []
  const vibrateCalls: number[] = []
  const scrollEl = document.createElement('div')
  let api!: UseGridRangeSelect

  const Harness = defineComponent({
    setup() {
      api = useGridRangeSelect({
        enabled: () => over.enabled ?? true,
        snapMinutes: () => over.snap ?? 15,
        resolvePoint: pointAt,
        scrollEl: () => scrollEl,
        onCommit: range => committed.push(range),
        vibrate: over.withVibrate === false ? undefined : (ms: number) => vibrateCalls.push(ms),
      })
      return () => h('div', {
        'data-testid': 'grid',
        'onPointerdown': api.onPointerDown,
        'onTouchstart': api.onTouchStart,
      }, [
        h('div', { 'data-testid': 'bar', 'data-range-select-ignore': '' }, '既存の予定'),
      ])
    },
  })

  const wrapper = await mountSuspended(Harness, { attachTo: document.body })
  mountedHarnesses.push(wrapper)
  return { wrapper, harness: { api, committed, vibrateCalls, scrollEl } satisfies Harness }
}

// ---- 生のイベントを作る（jsdom には PointerEvent / TouchEvent の実装が無い） ----

function pointerEvent(type: string, init: { clientX: number; clientY: number; pointerType?: string; button?: number }): Event {
  const ev = new Event(type, { bubbles: true, cancelable: true })
  return Object.assign(ev, {
    clientX: init.clientX,
    clientY: init.clientY,
    pointerType: init.pointerType ?? 'mouse',
    button: init.button ?? 0,
  })
}

function touchEvent(type: string, points: Array<{ clientX: number; clientY: number }>): Event {
  const ev = new Event(type, { bubbles: true, cancelable: true })
  const touches = points.map(p => ({ clientX: p.clientX, clientY: p.clientY }))
  return Object.assign(ev, { touches, changedTouches: touches })
}

describe('useGridRangeSelect — 純粋関数（§6.6.3）', () => {
  it('スナップ単位を表示密度から導出する（1時間 < 32px で 30分へ粗くなる）', () => {
    expect(snapMinutesForDensity(48)).toBe(15)
    expect(snapMinutesForDensity(32)).toBe(15)
    expect(snapMinutesForDensity(31)).toBe(30)
  })

  it('丸めは切り捨てではなく最も近い境界へ（9:59 は 10:00 になる）', () => {
    expect(snapToBoundary(9 * 60 + 59, 15)).toBe(10 * 60)
    expect(snapToBoundary(9 * 60 + 7, 15)).toBe(9 * 60)
    expect(snapToBoundary(9 * 60 + 8, 15)).toBe(9 * 60 + 15)
    // 上下限
    expect(snapToBoundary(-30, 15)).toBe(0)
    expect(snapToBoundary(2000, 15)).toBe(1440)
  })

  /**
   * AC-21d: 9:07 起点は 9:00 へ、終端は 15分境界の**最も近い方**へ丸まる。
   *
   * **経緯**: 当初の AC-21d は「9:07 → 10:22 で 9:00 – 10:30」と書いていたが、これは算術的に誤り
   * だった。§6.6.3 が正とする `Math.round` では 10:22 の最近傍境界は 10:15 である
   * （10:22-10:15 = 7分 < 10:30-10:22 = 8分）。10:30 へ丸まる最初の分は **10:23**。
   * 規範である §6.6.3（最近傍丸め）を実装の正とし、**設計書側の例示値を 10:23 へ改めた**
   * （本 PR で修正済み。反例として 10:22 → 10:15 も併記した）。
   * 切り捨て(floor)にすれば 10:22 → 10:30 も作れるが、それは §6.6.3 が
   * 「9:59 から下へなぞると 9:45 開始になり直感に反する」として明確に禁じた丸め方であり採らない。
   */
  it('AC-21d: 終端は最も近い 15分境界へ丸まる（10:22→10:15 / 10:23→10:30。切り捨てではない）', () => {
    const nearMid = normalizeRange(9 * 60 + 7, 10 * 60 + 22, 15)
    expect(nearMid.startMin).toBe(9 * 60)
    expect(nearMid.endMin).toBe(10 * 60 + 15)

    // 境界を1分越えれば 10:30 側へ丸まる（AC-21d が意図した結果）
    const overMid = normalizeRange(9 * 60 + 7, 10 * 60 + 23, 15)
    expect(overMid.startMin).toBe(9 * 60)
    expect(overMid.endMin).toBe(10 * 60 + 30)
  })

  it('AC-21e: 逆方向のドラッグでも start > end にならず、正の範囲に入れ替わる', () => {
    const r = normalizeRange(11 * 60, 9 * 60 + 30, 15)
    expect(r.startMin).toBe(9 * 60 + 30)
    expect(r.endMin).toBe(11 * 60)
    expect(r.endMin).toBeGreaterThan(r.startMin)
  })

  it('AC-21e: 逆方向に 0 まで戻し切っても最小 15分が維持される', () => {
    const r = normalizeRange(10 * 60, 10 * 60, 15)
    expect(r.endMin - r.startMin).toBe(15)
    // 5分だけ戻した（＝スナップすると同じ境界に落ちる）ケースも最小 15分
    const r2 = normalizeRange(10 * 60, 10 * 60 - 5, 15)
    expect(r2.endMin - r2.startMin).toBe(15)
  })

  it('24:00 を超えない。23:55 起点でも 24:00 で止まり、最小長は下側へ確保される', () => {
    const r = normalizeRange(23 * 60 + 55, 23 * 60 + 58, 15)
    expect(r.endMin).toBe(1440)
    expect(r.startMin).toBe(1440 - 15)
  })

  it('単クリックはその位置から既定 60分。24:00 を越える位置なら 24:00 で止める（§6.6.2）', () => {
    expect(defaultRangeFrom(9 * 60, 15)).toEqual({ startMin: 540, endMin: 600 })
    expect(defaultRangeFrom(23 * 60 + 30, 15)).toEqual({ startMin: 1410, endMin: 1440 })
  })

  /**
   * [P2] 最終スナップ境界へ丸まる位置の単クリックでゼロ分の予定が生まれてはならない。
   *
   * 23:53 は最近傍丸めで 24:00 になる。素朴に `Math.min(1440, start + 60)` で終端を切ると
   * 開始 1440・終了 1440 の**ゼロ分の予定**が確定する（§6.6.3 の最小15分に反する）。
   */
  it('[P2] 24:00 直前の単クリックでもゼロ分にならず、15分以上の正の範囲になる', () => {
    // 23:53 → 開始が 24:00 へ丸まる位置
    const late = defaultRangeFrom(23 * 60 + 53, 15)
    expect(late.endMin).toBeGreaterThan(late.startMin)
    expect(late.endMin - late.startMin).toBeGreaterThanOrEqual(MIN_RANGE_MIN)
    expect(late).toEqual({ startMin: 23 * 60 + 45, endMin: 1440 })

    // 23:59 に近い位置でも同様（24:00 を越えず、正の範囲を保つ）
    const later = defaultRangeFrom(23 * 60 + 59, 15)
    expect(later.endMin).toBeGreaterThan(later.startMin)
    expect(later.endMin - later.startMin).toBeGreaterThanOrEqual(MIN_RANGE_MIN)
    expect(later.endMin).toBe(1440)

    // ちょうど 24:00 を指した場合も同じ
    const atEnd = defaultRangeFrom(1440, 15)
    expect(atEnd).toEqual({ startMin: 1425, endMin: 1440 })
  })

  it('[P2] 通常の位置では従来どおり「その位置から60分」のまま（末端対応が通常経路を壊していない）', () => {
    expect(defaultRangeFrom(9 * 60 + 7, 15)).toEqual({ startMin: 540, endMin: 600 })
    expect(defaultRangeFrom(0, 15)).toEqual({ startMin: 0, endMin: 60 })
    expect(defaultRangeFrom(13 * 60, 15)).toEqual({ startMin: 780, endMin: 840 })
    // 22:30 は 60分ぶん取れる（23:30 で 24:00 に触れない）
    expect(defaultRangeFrom(22 * 60 + 30, 15)).toEqual({ startMin: 1350, endMin: 1410 })
  })

  it('スナップ 30分（将来のズームアウト）でも最小長は 30分になり負の範囲を作らない', () => {
    const r = normalizeRange(10 * 60, 10 * 60 + 5, 30)
    expect(r.startMin).toBe(600)
    expect(r.endMin).toBe(630)
  })
})

describe('useGridRangeSelect — PC（マウス）', () => {
  it('ドラッグするとスナップされた範囲で確定し、onCommit へ 1 度だけ届く', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element

    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(3), clientY: yOf(9 * 60 + 7) }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(3), clientY: yOf(10 * 60 + 23) }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(3), clientY: yOf(10 * 60 + 23) }))

    expect(harness.committed).toEqual([{ dayIndex: 3, startMin: 540, endMin: 630 }])
  })

  it('AC-21f: 横へ別の曜日の列まで動かしても、選択は開始した列に固定される', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element

    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(2), clientY: yOf(9 * 60) }))
    // 2列目 → 5列目へ大きく横移動しながら下へ
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(5), clientY: yOf(11 * 60) }))
    expect(harness.api.selection.value?.dayIndex).toBe(2)
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(5), clientY: yOf(11 * 60) }))

    expect(harness.committed).toEqual([{ dayIndex: 2, startMin: 540, endMin: 660 }])
  })

  it('閾値未満（4px 未満）の移動は単クリック扱いで、その位置から既定 60分になる', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element

    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(1), clientY: yOf(9 * 60) }))
    // 3px だけ震える（手ブレ）。CLICK_THRESHOLD_PX = 4 未満。
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(1) + CLICK_THRESHOLD_PX - 1, clientY: yOf(9 * 60) }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(1) + CLICK_THRESHOLD_PX - 1, clientY: yOf(9 * 60) }))

    expect(harness.committed).toEqual([{ dayIndex: 1, startMin: 540, endMin: 600 }])
  })

  it('閾値以上動けばドラッグ扱いになる（60分の既定に落ちない）', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element

    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(1), clientY: yOf(9 * 60) }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(1), clientY: yOf(9 * 60) + CLICK_THRESHOLD_PX }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(1), clientY: yOf(9 * 60) + CLICK_THRESHOLD_PX }))

    // 4px = 5分 → 最も近い境界は 9:00 のまま。最小 15分が効いて 9:00–9:15。
    expect(harness.committed).toEqual([{ dayIndex: 1, startMin: 540, endMin: 555 }])
  })

  it('§6.6.2: 既存の予定バーの上で開始した場合は選択が始まらない（従来どおり eventClick へ譲る）', async () => {
    const { wrapper, harness } = await mountHarness()
    const bar = wrapper.get('[data-testid="bar"]').element

    bar.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(3), clientY: yOf(9 * 60) }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(3), clientY: yOf(11 * 60) }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(3), clientY: yOf(11 * 60) }))

    expect(harness.api.selection.value).toBeNull()
    expect(harness.committed).toEqual([])
  })

  it('AC-22b: 週ビュー以外（enabled=false）ではジェスチャが一切成立しない', async () => {
    const { wrapper, harness } = await mountHarness({ enabled: false })
    const grid = wrapper.get('[data-testid="grid"]').element

    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(3), clientY: yOf(9 * 60) }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(3), clientY: yOf(11 * 60) }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(3), clientY: yOf(11 * 60) }))

    expect(harness.api.selection.value).toBeNull()
    expect(harness.committed).toEqual([])
  })

  it('タッチ由来の pointerdown は無視する（長押し経路が受け持つ。ここで拾うとスクロールが死ぬ）', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element

    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(3), clientY: yOf(9 * 60), pointerType: 'touch' }))
    expect(harness.api.selection.value).toBeNull()
    expect(harness.api.isSelecting.value).toBe(false)
  })

  it('pointercancel では選択を破棄し、ダイアログを開かない', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element

    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(3), clientY: yOf(9 * 60) }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(3), clientY: yOf(11 * 60) }))
    window.dispatchEvent(pointerEvent('pointercancel', { clientX: xOf(3), clientY: yOf(11 * 60) }))

    expect(harness.api.selection.value).toBeNull()
    expect(harness.committed).toEqual([])
  })
})

describe('useGridRangeSelect — モバイル長押し（AC-22a2・§6.6.4）', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('① touchstart 直後は preventDefault を呼ばない（ブラウザの慣性スクロールを生かす）', async () => {
    const { wrapper } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    const ev = touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }])
    grid.dispatchEvent(ev)
    expect(ev.defaultPrevented).toBe(false)
  })

  it('① 499ms 時点ではまだ選択が始まっていない', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))

    vi.advanceTimersByTime(LONG_PRESS_MS - 1)
    expect(harness.api.selection.value).toBeNull()
    expect(harness.api.isSelecting.value).toBe(false)
    expect(harness.vibrateCalls).toEqual([])
  })

  it('② 500ms 経過で選択モードに入り、触覚フィードバックがちょうど 1 度だけ呼ばれる', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))

    vi.advanceTimersByTime(LONG_PRESS_MS)
    expect(harness.api.isSelecting.value).toBe(true)
    expect(harness.api.selection.value).toEqual({ dayIndex: 3, startMin: 540, endMin: 555 })
    expect(harness.vibrateCalls).toEqual([10])

    // さらに時間を進めても 2 度目は無い（タイマーが張りっぱなしになっていない）
    vi.advanceTimersByTime(LONG_PRESS_MS * 3)
    expect(harness.vibrateCalls).toEqual([10])
  })

  it('② navigator.vibrate が未定義の環境でも例外にならず、選択は成立する', async () => {
    const original = Object.getOwnPropertyDescriptor(navigator, 'vibrate')
    // 非対応端末を再現する（プロパティ自体が存在しない）
    Reflect.deleteProperty(navigator as unknown as Record<string, unknown>, 'vibrate')
    try {
      const { wrapper, harness } = await mountHarness({ withVibrate: false })
      const grid = wrapper.get('[data-testid="grid"]').element
      grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))
      expect(() => vi.advanceTimersByTime(LONG_PRESS_MS)).not.toThrow()
      expect(harness.api.isSelecting.value).toBe(true)
    }
    finally {
      if (original) Object.defineProperty(navigator, 'vibrate', original)
    }
  })

  it('③ 500ms 未満に 10px 以上動いたらタイマーが破棄され、選択に入らない（＝縦スクロールが生きる）', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))

    vi.advanceTimersByTime(120)
    const moveEv = touchEvent('touchmove', [{ clientX: xOf(3), clientY: yOf(9 * 60) + TOUCH_CANCEL_PX }])
    window.dispatchEvent(moveEv)
    // 【最重要】成立前の touchmove で preventDefault を呼んではならない。呼んだ瞬間スクロールが死ぬ。
    expect(moveEv.defaultPrevented).toBe(false)

    vi.advanceTimersByTime(LONG_PRESS_MS)
    expect(harness.api.selection.value).toBeNull()
    expect(harness.api.isSelecting.value).toBe(false)
    expect(harness.vibrateCalls).toEqual([])
  })

  it('③ 10px 未満の震えではタイマーは破棄されず、500ms で長押しが成立する', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))

    vi.advanceTimersByTime(120)
    window.dispatchEvent(touchEvent('touchmove', [{ clientX: xOf(3), clientY: yOf(9 * 60) + TOUCH_CANCEL_PX - 1 }]))
    vi.advanceTimersByTime(LONG_PRESS_MS)

    expect(harness.api.isSelecting.value).toBe(true)
    expect(harness.vibrateCalls).toEqual([10])
  })

  it('④ 500ms 未満に touchend が来たらタイマーが破棄され、作成ダイアログは開かない', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))

    vi.advanceTimersByTime(200)
    window.dispatchEvent(touchEvent('touchend', []))
    vi.advanceTimersByTime(LONG_PRESS_MS)

    expect(harness.api.selection.value).toBeNull()
    expect(harness.committed).toEqual([])
    expect(harness.vibrateCalls).toEqual([])
  })

  it('⑤ touchcancel（着信等）では選択が破棄され、中途半端な時刻で確定しない', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))
    vi.advanceTimersByTime(LONG_PRESS_MS)
    window.dispatchEvent(touchEvent('touchmove', [{ clientX: xOf(3), clientY: yOf(10 * 60) }]))
    expect(harness.api.selection.value).not.toBeNull()

    window.dispatchEvent(touchEvent('touchcancel', []))

    expect(harness.api.selection.value).toBeNull()
    expect(harness.committed).toEqual([])
  })

  it('長押し成立後の touchmove では preventDefault を呼んでスクロールを止め、範囲が追従する', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(4), clientY: yOf(9 * 60) }]))
    vi.advanceTimersByTime(LONG_PRESS_MS)

    const moveEv = touchEvent('touchmove', [{ clientX: xOf(4), clientY: yOf(10 * 60 + 30) }])
    window.dispatchEvent(moveEv)
    expect(moveEv.defaultPrevented).toBe(true)
    expect(harness.api.selection.value).toEqual({ dayIndex: 4, startMin: 540, endMin: 630 })

    window.dispatchEvent(touchEvent('touchend', []))
    expect(harness.committed).toEqual([{ dayIndex: 4, startMin: 540, endMin: 630 }])
  })

  it('§6.6.2: 終日帯・時刻ラベル列（data-range-select-ignore）の上では長押しタイマーすら張らない', async () => {
    const { wrapper, harness } = await mountHarness()
    const bar = wrapper.get('[data-testid="bar"]').element
    bar.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))

    vi.advanceTimersByTime(LONG_PRESS_MS * 2)
    expect(harness.api.selection.value).toBeNull()
    expect(harness.vibrateCalls).toEqual([])
  })
})

describe('useGridRangeSelect — 後始末', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('onUnmounted で長押しタイマーが解除される（アンマウント後に選択が成立しない）', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(touchEvent('touchstart', [{ clientX: xOf(3), clientY: yOf(9 * 60) }]))

    const clearSpy = vi.spyOn(globalThis, 'clearTimeout')
    wrapper.unmount()
    expect(clearSpy).toHaveBeenCalled()
    clearSpy.mockRestore()

    vi.advanceTimersByTime(LONG_PRESS_MS * 2)
    expect(harness.api.selection.value).toBeNull()
    expect(harness.vibrateCalls).toEqual([])
  })

  it('onUnmounted で window のリスナが外れる（アンマウント後のイベントで確定しない）', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element
    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(3), clientY: yOf(9 * 60) }))

    wrapper.unmount()

    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(3), clientY: yOf(11 * 60) }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(3), clientY: yOf(11 * 60) }))
    expect(harness.committed).toEqual([])
  })

  it('確定した時点で window のリスナが外れる（次のドラッグ前に二重確定しない）', async () => {
    const { wrapper, harness } = await mountHarness()
    const grid = wrapper.get('[data-testid="grid"]').element

    grid.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(3), clientY: yOf(9 * 60) }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(3), clientY: yOf(10 * 60) }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(3), clientY: yOf(10 * 60) }))
    expect(harness.committed.length).toBe(1)

    // 離した後の余分な pointerup が 2 件目を作らない
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(3), clientY: yOf(10 * 60) }))
    expect(harness.committed.length).toBe(1)
    expect(harness.api.isSelecting.value).toBe(false)
  })
})

describe('useGridRangeSelect — キーボード経路の差込口（§6.7・W3-a4 の下地）', () => {
  it('beginAt / extendTo / commit がドラッグと同一の出口へ落ちる', async () => {
    const { harness } = await mountHarness()

    harness.api.beginAt({ dayIndex: 5, minutes: 9 * 60 })
    expect(harness.api.selection.value).toEqual({ dayIndex: 5, startMin: 540, endMin: 555 })

    harness.api.extendTo(10 * 60 + 30)
    expect(harness.api.selection.value).toEqual({ dayIndex: 5, startMin: 540, endMin: 630 })

    harness.api.commit()
    expect(harness.committed).toEqual([{ dayIndex: 5, startMin: 540, endMin: 630 }])
    expect(harness.api.selection.value).toBeNull()
  })

  it('cancel は選択を捨て、onCommit を呼ばない（Escape 相当）', async () => {
    const { harness } = await mountHarness()
    harness.api.beginAt({ dayIndex: 5, minutes: 9 * 60 })
    harness.api.cancel()
    expect(harness.api.selection.value).toBeNull()
    expect(harness.committed).toEqual([])
  })
})
