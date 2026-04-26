<script setup lang="ts">
import type { ChatTab } from '~/types/chat'

// -----------------------------------------------------------------------
// Props / Emits
// -----------------------------------------------------------------------
const props = defineProps<{
  tab: ChatTab
  isActive: boolean
  hasDraft?: boolean
}>()

const emit = defineEmits<{
  close: [tabId: string]
  select: [tabId: string]
  contextmenu: [event: { tabId: string; x: number; y: number }]
}>()

// -----------------------------------------------------------------------
// チャンネル種別アイコン（絵文字）
// -----------------------------------------------------------------------
const channelIcon = computed<string>(() => {
  const ch = props.tab.channel
  const type = ch.channelType
  if (type === 'CROSS_TEAM') return '🔗'
  // Zimmer（グループDM）: DIRECT かつ dmPartner が null（1:1 ではない複数人 DM）
  if (type === 'DIRECT' && ch.dmPartner === null) return '👥'
  if (type === 'DIRECT') return '👤'
  return '#'
})

/** タブに表示するチャンネル名（DIRECT は相手ユーザー名） */
const channelLabel = computed<string>(() => {
  const ch = props.tab.channel
  if (ch.channelType === 'DIRECT' && ch.dmPartner) {
    return ch.dmPartner.displayName
  }
  return ch.name ?? `#${ch.id}`
})

/** aria-label 用の完全ラベル */
const ariaLabel = computed<string>(() => `${channelLabel.value} のタブ`)

// -----------------------------------------------------------------------
// 未読バッジ
// -----------------------------------------------------------------------
const unreadCount = computed<number>(() => props.tab.channel.unreadCount ?? 0)

/** バッジ表示文字列（9+ 打ち切り） */
const badgeLabel = computed<string>(() =>
  unreadCount.value > 9 ? '9+' : String(unreadCount.value),
)

/**
 * @mention 系かどうかの判定。
 * 現時点では ChatChannelResponse に mention フラグがないため、
 * unreadCount > 0 && isPinned をメンション扱いの暫定判定とする（将来拡張可）。
 * ストアまたは API でメンションフラグが追加された場合はここを差し替えること。
 */
const isMentionUnread = computed<boolean>(
  () => unreadCount.value > 0 && props.tab.channel.isPinned,
)

// -----------------------------------------------------------------------
// スタイル計算
// -----------------------------------------------------------------------
const tabClasses = computed<string[]>(() => {
  const base = [
    'group',
    'relative',
    'flex',
    'items-center',
    'gap-1',
    'px-3',
    'py-2',
    'text-sm',
    'cursor-pointer',
    'select-none',
    'shrink-0',
    'transition-all',
    'duration-150',
    'outline-none',
    'focus-visible:ring-2',
    'focus-visible:ring-primary',
  ]

  if (props.isActive) {
    base.push(
      'border-b-2',
      'border-primary',
      'bg-surface-0',
      'dark:bg-surface-900',
      'font-medium',
      'text-surface-900',
      'dark:text-surface-0',
      'max-w-[240px]',
      'min-w-[120px]',
    )
  } else {
    base.push(
      'border-b-2',
      'border-transparent',
      'bg-surface-50',
      'dark:bg-surface-800',
      'hover:bg-surface-100',
      'dark:hover:bg-surface-700',
      'text-surface-600',
      'dark:text-surface-400',
      'max-w-[200px]',
      'min-w-[120px]',
    )
  }

  return base
})

// -----------------------------------------------------------------------
// キーボードハンドラ
// -----------------------------------------------------------------------
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    emit('select', props.tab.id)
  }
}

// -----------------------------------------------------------------------
// 右クリック（PC）
// -----------------------------------------------------------------------
function onContextmenu(event: MouseEvent): void {
  event.preventDefault()
  emit('contextmenu', { tabId: props.tab.id, x: event.clientX, y: event.clientY })
}

// -----------------------------------------------------------------------
// 長押し（タッチデバイス）— 500ms で contextmenu 発火
// -----------------------------------------------------------------------
let longPressTimer: ReturnType<typeof setTimeout> | null = null
let touchStartX = 0
let touchStartY = 0

function onTouchstart(event: TouchEvent): void {
  const touch = event.touches[0]
  if (!touch) return
  touchStartX = touch.clientX
  touchStartY = touch.clientY

  longPressTimer = setTimeout(() => {
    // 触覚フィードバック（対応端末のみ）
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      navigator.vibrate(20)
    }
    emit('contextmenu', { tabId: props.tab.id, x: touchStartX, y: touchStartY })
    longPressTimer = null
  }, 500)
}

function onTouchmove(event: TouchEvent): void {
  if (!longPressTimer) return
  const touch = event.touches[0]
  if (!touch) return
  const dx = Math.abs(touch.clientX - touchStartX)
  const dy = Math.abs(touch.clientY - touchStartY)
  // 10px 超の移動はスクロール意図 → 長押しキャンセル
  if (dx > 10 || dy > 10) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

function onTouchend(): void {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

onUnmounted(() => {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
  }
})

// -----------------------------------------------------------------------
// × ボタンクリック
// -----------------------------------------------------------------------
function onCloseClick(event: MouseEvent): void {
  event.stopPropagation()
  emit('close', props.tab.id)
}
</script>

<template>
  <div
    :class="tabClasses"
    role="tab"
    :aria-selected="isActive"
    :aria-label="ariaLabel"
    :title="channelLabel"
    tabindex="0"
    @click="emit('select', tab.id)"
    @keydown="onKeydown"
    @contextmenu="onContextmenu"
    @touchstart.passive="onTouchstart"
    @touchmove.passive="onTouchmove"
    @touchend.passive="onTouchend"
  >
    <!-- チャンネル種別アイコン -->
    <span class="shrink-0 text-xs" aria-hidden="true">{{ channelIcon }}</span>

    <!-- チャンネル名（ellipsis 省略） -->
    <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap leading-none">
      {{ channelLabel }}
    </span>

    <!-- ドラフトインジケータ（入力中: 黄色ドット） -->
    <span
      v-if="hasDraft"
      :title="$t('chat.tab.draftIndicator')"
      class="inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-yellow-400"
      aria-hidden="true"
    />

    <!-- 未読バッジ -->
    <span
      v-if="unreadCount > 0"
      :aria-label="$t('chat.tab.unreadBadge', { count: unreadCount })"
      :class="[
        'shrink-0 rounded-full px-1 py-0.5 text-[10px] font-bold leading-none text-white',
        isMentionUnread ? 'bg-red-500' : 'bg-primary',
      ]"
    >
      {{ badgeLabel }}
    </span>

    <!-- × 閉じるボタン（ホバー時のみ視覚表示） -->
    <button
      type="button"
      :aria-label="$t('chat.tab.close')"
      class="ml-0.5 shrink-0 rounded p-0.5 opacity-0 transition-opacity duration-150 hover:bg-surface-200 dark:hover:bg-surface-600 group-hover:opacity-100 focus:opacity-100"
      @click="onCloseClick"
    >
      <i class="pi pi-times text-[10px]" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped>
/* iOS Safari: ネイティブ長押しメニュー抑制 */
div {
  -webkit-touch-callout: none;
  user-select: none;
}

/* Android: ダブルタップズーム等の干渉抑制 */
div {
  touch-action: manipulation;
}
</style>
