<script setup lang="ts">
/**
 * F22.1 横スワイプ・スコープカルーセル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.6 / §3
 * - 3 パネル（個人/チーム/組織）を同時マウント。常に [prev, center, next] の循環順に並べ、
 *   transform: translateX で中央（-100%）を表示する。再描画なし（v-show 不使用・常時 DOM 上に存在）。
 * - 自前 touch ハンドラ（swiper ライブラリ不使用）。
 *   閾値 = 画面幅 20% かつ |Δx| > |Δy| * 1.5。慣性（フリック速度）対応。閾値未満はばね戻し。
 * - 無限循環: 個人↔チーム↔組織↔個人 を常に「隣へ 1 枚だけ」スライド。1 ステップ後に transition を
 *   切って centerIndex を更新＝画面外で再センタリングするため、個人↔組織もチームを横切らない。
 * - PC: 上部セグメントトグル + 左右矢印 + キーボード ←→（入力フォーカス時は奪わない）。
 * - モバイル: 下部ドット。
 * - prefers-reduced-motion: アニメ無効・即時切替。
 * - ARIA: role=tablist/tab/tabpanel + aria-selected、切替時 aria-live=polite で通知。
 */
import type { ActivePanel } from '~/stores/useScopeDashboardStore'

const { t } = useI18n()
const store = useScopeDashboardStore()

/** パネル定義（順序が循環インデックスに対応）。 */
const PANELS: { panel: ActivePanel; labelKey: string; icon: string }[] = [
  { panel: 'PERSONAL', labelKey: 'scopeDashboard.tabs.personal', icon: 'pi pi-user' },
  { panel: 'TEAM', labelKey: 'scopeDashboard.tabs.team', icon: 'pi pi-users' },
  { panel: 'ORGANIZATION', labelKey: 'scopeDashboard.tabs.organization', icon: 'pi pi-building' },
]
const PANEL_COUNT = PANELS.length

/**
 * store.activePanel ↔ activeIndex の相互変換。
 * activeIndex は「選択中（ハイライト対象）」のパネル。切替操作の瞬間に更新する。
 */
const activeIndex = computed<number>({
  get: () => PANELS.findIndex(p => p.panel === store.activePanel),
  set: (idx) => {
    const target = PANELS[idx]
    if (target) store.setActivePanel(target.panel)
  },
})

/**
 * レイアウト上の「中央スロット」に置くパネル。
 * 常に [prev, center, next] の 3 枚を循環順に並べ、translateX -100% で中央を表示する。
 * 1 ステップ分だけスライド（-100%→-200% / -100%→0%）した後、transition を切って
 * centerIndex を更新＝裏で再センタリングする。これにより個人↔組織もチームを横切らずに
 * 「隣へ 1 枚だけ」滑らかにスライドする（無限循環カルーセル）。
 */
const centerIndex = ref(activeIndex.value)
/** 現在アニメ中のスライド方向。-1=prev / 0=停止 / +1=next。 */
const slideDir = ref(0)
/** スライドアニメ進行中フラグ（多重入力・外部変更との競合防止）。 */
const isAnimating = ref(false)

/** レイアウトに並べる 3 スロット（循環順 [prev, center, next]）。各要素は PANELS の要素＋実 index。 */
const slots = computed(() => {
  const c = centerIndex.value
  return [
    (c - 1 + PANEL_COUNT) % PANEL_COUNT,
    c,
    (c + 1) % PANEL_COUNT,
  ].map(i => ({ index: i, ...PANELS[i]! }))
})

// --- prefers-reduced-motion ---
const reducedMotion = ref(false)
let motionQuery: MediaQueryList | null = null
function onMotionChange(e: MediaQueryListEvent) {
  reducedMotion.value = e.matches
}

// --- スワイプ状態 ---
const trackEl = ref<HTMLElement | null>(null)
const transitionEnabled = ref(true)
/** ドラッグ中の追加オフセット（px）。確定で 0 に戻す。 */
const dragOffsetPx = ref(0)
const isDragging = ref(false)

let startX = 0
let startY = 0
let startTime = 0
let pointerActive = false
let directionLocked: 'horizontal' | 'vertical' | null = null
let containerWidth = 0

/** aria-live 用メッセージ。パネル切替時に更新する。 */
const liveMessage = ref('')

function announce(idx: number) {
  const target = PANELS[idx]
  if (!target) return
  liveMessage.value = t('scopeDashboard.switchedTo', { name: t(target.labelKey) })
}

/** 1 ステップ（隣へ 1 枚）のスライド時間（秒）。全切替で一定。 */
const BASE_DURATION = 0.42
let slideTimer: ReturnType<typeof setTimeout> | null = null
/** 自分（step）が起こした activeIndex 変更を watcher に無視させるための抑止フラグ。 */
let suppressActiveWatch = false
/**
 * 消化待ちのスライド方向キュー。
 * 選択(activeIndex)はクリック毎に即時更新するが、表示(centerIndex)はこのキューを
 * 1 スライドずつ順番に消化して追従する。連打でも「方向どおりに 1 枚ずつ」滑らかに流れ、
 * 飛び・方向反転・タイマー多重が起きない。
 */
let pendingDirs: (1 | -1)[] = []

/** activeIndex を内部更新する（自前 watcher を抑止）。選択＝ハイライトに即反映される。 */
function setActiveInternal(idx: number) {
  suppressActiveWatch = true
  activeIndex.value = idx
  suppressActiveWatch = false
}

/** 進行中スライドを着地点 landed へ瞬間移動して確定する（transition を切って再センタリング）。 */
function settleSlide(landed: number) {
  if (slideTimer !== null) {
    clearTimeout(slideTimer)
    slideTimer = null
  }
  transitionEnabled.value = false
  centerIndex.value = landed
  slideDir.value = 0
  isAnimating.value = false
}

/** キューの先頭を 1 つ取り出して 1 枚スライドを開始する。アニメ中・空なら何もしない。 */
function drainQueue() {
  if (isAnimating.value) return
  const dir = pendingDirs.shift()
  if (dir === undefined) return

  const dst = (centerIndex.value + dir + PANEL_COUNT) % PANEL_COUNT
  transitionEnabled.value = true
  isAnimating.value = true
  slideDir.value = dir

  slideTimer = setTimeout(() => {
    // 画面外での再センタリング（transition を切って瞬間移動 → 次フレームで再有効化）→ 次の1枚へ。
    settleSlide(dst)
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        transitionEnabled.value = true
        drainQueue()
      })
    })
  }, BASE_DURATION * 1000 + 30)
}

/**
 * 隣のパネルへ 1 ステップだけスライドする（dir = +1: next / -1: prev）。
 * 選択(activeIndex)を即時更新し、表示用キューに方向を積む。アニメ中の連打でも入力を捨てない。
 */
function step(dir: 1 | -1) {
  // activeIndex はキュー末尾までの最終到達先を指すので、これを起点に 1 つ進める。
  const to = (activeIndex.value + dir + PANEL_COUNT) % PANEL_COUNT
  setActiveInternal(to)
  announce(to)

  if (reducedMotion.value) {
    // アニメ無効: キューを捨てて即時切替。
    pendingDirs = []
    settleSlide(to)
    return
  }

  pendingDirs.push(dir)
  drainQueue()
}

/**
 * 指定パネルへ切り替える（タブ/ドットのクリック用）。
 * 3 枚の循環なので任意の遷移は必ず ±1 ステップに収まる（前進 or 後退）。
 */
function goTo(idx: number) {
  if (idx === activeIndex.value) return
  const dir: 1 | -1 = idx === (activeIndex.value + 1) % PANEL_COUNT ? 1 : -1
  step(dir)
}

function next() {
  step(1)
}
function prev() {
  step(-1)
}

// 外部（他コンポーネント）から store.activePanel が変更された場合は即時センタリングする。
// 自前 step による変更は suppressActiveWatch で除外。sync flush で同期的に判定する。
watch(
  activeIndex,
  (v) => {
    if (suppressActiveWatch || v === centerIndex.value) return
    // 外部変更: キューを破棄して即センタリング。
    pendingDirs = []
    settleSlide(v)
  },
  { flush: 'sync' },
)

// --- touch / pointer ハンドラ ---
function onTouchStart(e: TouchEvent) {
  const touch = e.touches[0]
  if (!touch) return
  beginDrag(touch.clientX, touch.clientY)
}

function beginDrag(x: number, y: number) {
  // 進行中アニメ・消化待ちキューがあれば、最終選択(activeIndex)へ確定してからドラッグに入る。
  // これをしないと、アニメの transform 途中位置を基準にドラッグ追従して基準ズレが生じる。
  if (isAnimating.value || pendingDirs.length > 0) {
    pendingDirs = []
    settleSlide(activeIndex.value)
  }
  pointerActive = true
  isDragging.value = false
  directionLocked = null
  startX = x
  startY = y
  startTime = performance.now()
  containerWidth = trackEl.value?.clientWidth ?? window.innerWidth
  dragOffsetPx.value = 0
}

function onTouchMove(e: TouchEvent) {
  const touch = e.touches[0]
  if (!touch || !pointerActive) return
  const dx = touch.clientX - startX
  const dy = touch.clientY - startY

  // 方向判定（横 > 縦*1.5 で横ロック、それ以外は縦スクロール優先）。
  if (directionLocked === null) {
    if (Math.abs(dx) > Math.abs(dy) * 1.5 && Math.abs(dx) > 8) {
      directionLocked = 'horizontal'
    } else if (Math.abs(dy) > Math.abs(dx)) {
      directionLocked = 'vertical'
    }
  }

  if (directionLocked === 'horizontal') {
    // 縦スクロールを抑止して横ドラッグに追従。
    e.preventDefault()
    isDragging.value = true
    if (!reducedMotion.value) {
      transitionEnabled.value = false
      dragOffsetPx.value = dx
    }
  }
}

function onTouchEnd() {
  if (!pointerActive) return
  pointerActive = false
  const dx = dragOffsetPx.value
  const elapsed = Math.max(1, performance.now() - startTime)
  const velocity = dx / elapsed // px/ms

  transitionEnabled.value = true
  dragOffsetPx.value = 0
  isDragging.value = false

  if (directionLocked !== 'horizontal') {
    directionLocked = null
    return
  }
  directionLocked = null

  const threshold = containerWidth * 0.2
  const FLICK_VELOCITY = 0.4 // px/ms（慣性判定）

  if (dx <= -threshold || velocity <= -FLICK_VELOCITY) {
    next()
  } else if (dx >= threshold || velocity >= FLICK_VELOCITY) {
    prev()
  }
  // 閾値未満は dragOffsetPx を 0 に戻すことでばね戻し（transition で復帰）。
}

// --- キーボード ---
function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  return (
    tag === 'INPUT' ||
    tag === 'TEXTAREA' ||
    tag === 'SELECT' ||
    target.isContentEditable
  )
}

function onKeydown(e: KeyboardEvent) {
  // 入力フォーカス時は矢印キーを奪わない（§3.2 / §3.8）。
  if (isEditableTarget(e.target)) return
  if (e.key === 'ArrowRight') {
    e.preventDefault()
    next()
  } else if (e.key === 'ArrowLeft') {
    e.preventDefault()
    prev()
  }
}

// --- トラックの transform スタイル ---
const trackStyle = computed(() => {
  // 中央スロット（slot1）を表示する基準位置が -100%。
  // slideDir に応じて 1 枚分だけスライド（next: -200% / prev: 0%）。
  const x = (-1 - slideDir.value) * 100
  const base = `translateX(calc(${x}% + ${dragOffsetPx.value}px))`
  // 加速も減速も対称な ease-in-out。1 ステップ固定なので時間は常に一定。
  const transition =
    transitionEnabled.value && !reducedMotion.value
      ? `transform ${BASE_DURATION}s ease-in-out`
      : 'none'
  return { transform: base, transition }
})

onMounted(async () => {
  if (import.meta.client) {
    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    reducedMotion.value = motionQuery.matches
    motionQuery.addEventListener('change', onMotionChange)
    window.addEventListener('keydown', onKeydown)
  }
  // ナビゲーション時もスコープ一覧を再同期する（プラグインはページ遷移で再実行されないため）。
  // BIGINT→UUID マイグレーションも兼ねる。
  const authStore = useAuthStore()
  if (authStore.isAuthenticated) {
    await Promise.all([
      store.loadTabs('TEAM', store.teamTabPage),
      store.loadTabs('ORGANIZATION', store.orgTabPage),
    ])
  }
})

onBeforeUnmount(() => {
  motionQuery?.removeEventListener('change', onMotionChange)
  if (import.meta.client) window.removeEventListener('keydown', onKeydown)
  if (slideTimer !== null) clearTimeout(slideTimer)
})
</script>

<template>
  <div class="flex flex-col" data-testid="scope-carousel">
    <!-- PC: 上部セグメントトグル + 左右矢印 -->
    <div class="mb-4 flex items-center justify-center gap-7">
      <Button
        icon="pi pi-chevron-left"
        text
        rounded
        data-testid="scope-prev"
        :aria-label="$t('scopeDashboard.prevPanel')"
        @click="prev"
      />

      <div
        role="tablist"
        data-testid="scope-segment-tablist"
        :aria-label="$t('scopeDashboard.tabs.personal')"
        class="field-bordered border-2 flex items-center gap-1 rounded-full bg-surface-100 p-1 dark:bg-surface-800"
      >
        <button
          v-for="(p, idx) in PANELS"
          :id="`scope-tab-${p.panel}`"
          :key="p.panel"
          type="button"
          role="tab"
          :data-testid="`scope-segment-${p.panel}`"
          :aria-selected="idx === activeIndex"
          :aria-controls="`scope-panel-${p.panel}`"
          class="flex items-center gap-1.5 rounded-full px-4 py-1.5 text-sm font-medium transition-colors"
          :class="
            idx === activeIndex
              ? 'bg-primary text-primary-contrast'
              : 'text-surface-600 hover:bg-surface-200 dark:text-surface-300 dark:hover:bg-surface-700'
          "
          @click="goTo(idx)"
        >
          <i
            :class="p.icon"
            class="text-xs"
            aria-hidden="true"
          />
          {{ $t(p.labelKey) }}
        </button>
      </div>

      <Button
        icon="pi pi-chevron-right"
        text
        rounded
        data-testid="scope-next"
        :aria-label="$t('scopeDashboard.nextPanel')"
        @click="next"
      />
    </div>

    <!-- カルーセル本体（3 パネル同時マウント） -->
    <DashboardStorageSummary />

    <div
      class="relative w-full overflow-hidden"
      @touchstart.passive="onTouchStart"
      @touchmove="onTouchMove"
      @touchend="onTouchEnd"
      @touchcancel="onTouchEnd"
    >
      <div
        ref="trackEl"
        class="flex w-full"
        :style="trackStyle"
      >
        <!--
          循環順 [prev, center, next] の 3 スロットを描画。:key はパネル固定なので
          slots の並び替え時も DOM/コンポーネントは再マウントされず移動するだけ（二重 fetch なし）。
        -->
        <section
          v-for="slot in slots"
          :id="`scope-panel-${slot.panel}`"
          :key="slot.panel"
          role="tabpanel"
          :aria-labelledby="`scope-tab-${slot.panel}`"
          :aria-hidden="slot.index !== activeIndex"
          class="w-full shrink-0 grow-0 basis-full"
        >
          <DashboardPersonalPanel v-if="slot.panel === 'PERSONAL'" />
          <DashboardTeamPanel v-else-if="slot.panel === 'TEAM'" />
          <DashboardOrgPanel v-else />
        </section>
      </div>
    </div>

    <!-- モバイル: 下部ドット -->
    <div class="mt-4 flex items-center justify-center gap-2">
      <button
        v-for="(p, idx) in PANELS"
        :key="`dot-${p.panel}`"
        type="button"
        class="h-2.5 w-2.5 rounded-full transition-colors"
        :class="idx === activeIndex ? 'bg-primary' : 'bg-surface-300 dark:bg-surface-600'"
        :aria-label="$t(p.labelKey)"
        :aria-current="idx === activeIndex ? 'true' : undefined"
        @click="goTo(idx)"
      />
    </div>

    <!-- スクリーンリーダー向けライブ通知 -->
    <span class="sr-only" role="status" aria-live="polite">{{ liveMessage }}</span>
  </div>
</template>
