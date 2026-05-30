<script setup lang="ts">
/**
 * F22.1 横スワイプ・スコープカルーセル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.6 / §3
 * - 3 パネル（個人/チーム/組織）を同時マウントし transform: translateX(-N*100%) でスライド。
 *   再描画なし（v-show 不使用・常時 DOM 上に存在）。
 * - 自前 touch ハンドラ（swiper ライブラリ不使用）。
 *   閾値 = 画面幅 20% かつ |Δx| > |Δy| * 1.5。慣性（フリック速度）対応。閾値未満はばね戻し。
 * - 循環: 個人(0)→チーム(1)→組織(2)→個人(0)（mod 3）。端ジャンプ時のみ transition を一時無効化。
 * - PC: 上部セグメントトグル + 左右矢印 + キーボード ←→（入力フォーカス時は奪わない）。
 * - モバイル: 下部ドット。
 * - prefers-reduced-motion: アニメ無効・即時切替。
 * - ARIA: role=tablist/tab/tabpanel + aria-selected、切替時 aria-live=polite で通知。
 */
import type { ActivePanel } from '~/stores/useScopeDashboardStore'

const { t } = useI18n()
const store = useScopeDashboardStore()

/** パネル定義（順序が translateX のインデックスに対応）。 */
const PANELS: { panel: ActivePanel; labelKey: string }[] = [
  { panel: 'PERSONAL', labelKey: 'scopeDashboard.tabs.personal' },
  { panel: 'TEAM', labelKey: 'scopeDashboard.tabs.team' },
  { panel: 'ORGANIZATION', labelKey: 'scopeDashboard.tabs.organization' },
]
const PANEL_COUNT = PANELS.length

/** store.activePanel ↔ activeIndex の相互変換。 */
const activeIndex = computed<number>({
  get: () => PANELS.findIndex(p => p.panel === store.activePanel),
  set: (idx) => {
    const target = PANELS[idx]
    if (target) store.setActivePanel(target.panel)
  },
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

/**
 * パネルを指定インデックスへ切り替える。
 * 循環の継ぎ目（端ジャンプ）は transition を 1 フレーム無効化して瞬間移動 → 再有効化する。
 *
 * @param rawIdx - 任意の整数（mod 3 で循環させる）
 * @param wrapped - 端をまたいだ循環ジャンプかどうか（transition 抑制用）
 */
function goTo(rawIdx: number, wrapped = false) {
  const idx = ((rawIdx % PANEL_COUNT) + PANEL_COUNT) % PANEL_COUNT
  if (wrapped && !reducedMotion.value) {
    // 端ジャンプ: transition を一時無効化して瞬間移動 → 次フレームで再有効化。
    transitionEnabled.value = false
    activeIndex.value = idx
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        transitionEnabled.value = true
      })
    })
  } else {
    activeIndex.value = idx
  }
  announce(idx)
}

function next() {
  const cur = activeIndex.value
  // 2 → 0 は端をまたぐ循環。
  goTo(cur + 1, cur === PANEL_COUNT - 1)
}
function prev() {
  const cur = activeIndex.value
  // 0 → 2 は端をまたぐ循環。
  goTo(cur - 1, cur === 0)
}

// --- touch / pointer ハンドラ ---
function onTouchStart(e: TouchEvent) {
  const touch = e.touches[0]
  if (!touch) return
  beginDrag(touch.clientX, touch.clientY)
}

function beginDrag(x: number, y: number) {
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
  const base = `translateX(calc(${-activeIndex.value * 100}% + ${dragOffsetPx.value}px))`
  const transition =
    transitionEnabled.value && !reducedMotion.value
      ? 'transform .28s ease'
      : 'none'
  return { transform: base, transition }
})

onMounted(() => {
  if (import.meta.client) {
    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    reducedMotion.value = motionQuery.matches
    motionQuery.addEventListener('change', onMotionChange)
    window.addEventListener('keydown', onKeydown)
  }
})

onBeforeUnmount(() => {
  motionQuery?.removeEventListener('change', onMotionChange)
  if (import.meta.client) window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="flex flex-col" data-testid="scope-carousel">
    <!-- PC: 上部セグメントトグル + 左右矢印 -->
    <div class="mb-4 flex items-center justify-between gap-2">
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
        class="flex items-center gap-1 rounded-full bg-surface-100 p-1 dark:bg-surface-800"
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
          class="rounded-full px-4 py-1.5 text-sm font-medium transition-colors"
          :class="
            idx === activeIndex
              ? 'bg-primary text-primary-contrast'
              : 'text-surface-600 hover:bg-surface-200 dark:text-surface-300 dark:hover:bg-surface-700'
          "
          @click="goTo(idx)"
        >
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
        <section
          v-for="(p, idx) in PANELS"
          :id="`scope-panel-${p.panel}`"
          :key="p.panel"
          role="tabpanel"
          :aria-labelledby="`scope-tab-${p.panel}`"
          :aria-hidden="idx !== activeIndex"
          class="w-full shrink-0 grow-0 basis-full"
        >
          <DashboardPersonalPanel v-if="p.panel === 'PERSONAL'" />
          <DashboardTeamPanel v-else-if="p.panel === 'TEAM'" />
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
