<script setup lang="ts">
type ContextMenuAction = 'openInNewWindow' | 'closeOthers' | 'closeRight' | 'close'

interface Props {
  tabId: string
  channelId: number
  position: { x: number; y: number }
}

const props = defineProps<Props>()

const emit = defineEmits<{
  action: [event: { tabId: string; action: ContextMenuAction }]
  close: []
}>()

const { t } = useI18n()
const { warn } = useNotification()

// メニューのDOM参照（座標クランプ用）
const menuEl = ref<HTMLElement | null>(null)

// 画面端はみ出し防止クランプ後の実際の座標
const clampedPosition = computed(() => {
  if (!menuEl.value) {
    return { x: props.position.x, y: props.position.y }
  }
  const menuWidth = menuEl.value.offsetWidth || 220
  const menuHeight = menuEl.value.offsetHeight || 160
  const x = Math.min(props.position.x, window.innerWidth - menuWidth - 8)
  const y = Math.min(props.position.y, window.innerHeight - menuHeight - 8)
  return { x: Math.max(0, x), y: Math.max(0, y) }
})

function handleAction(action: ContextMenuAction) {
  if (action === 'openInNewWindow') {
    // §7.3 セキュリティ要件: 同一オリジンパスのみ、noopener,noreferrer 必須
    const newWin = window.open(
      `/chat?channel=${props.channelId}`,
      '_blank',
      'noopener,noreferrer',
    )
    if (newWin === null) {
      // ポップアップブロック時: warn トースト + console.warn
      console.warn('[ChatTabContextMenu] popup blocked for channelId:', props.channelId)
      warn(t('chat.tab.popupBlocked'))
    }
  }
  emit('action', { tabId: props.tabId, action })
  emit('close')
}

function onOutsideMousedown(event: MouseEvent) {
  if (menuEl.value && !menuEl.value.contains(event.target as Node)) {
    emit('close')
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    emit('close')
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onOutsideMousedown)
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('mousedown', onOutsideMousedown)
  document.removeEventListener('keydown', onKeydown)
})

const menuItems: { label: string; action: ContextMenuAction }[] = [
  { label: t('chat.tab.openInNewWindow'), action: 'openInNewWindow' },
  { label: t('chat.tab.closeOthers'), action: 'closeOthers' },
  { label: t('chat.tab.closeRight'), action: 'closeRight' },
  { label: t('chat.tab.close'), action: 'close' },
]
</script>

<template>
  <div
    ref="menuEl"
    role="menu"
    class="fixed z-50 min-w-[180px] overflow-hidden rounded-lg border border-surface-200 bg-surface-0 shadow-lg dark:border-surface-700 dark:bg-surface-800"
    :style="{ left: `${clampedPosition.x}px`, top: `${clampedPosition.y}px` }"
  >
    <ul class="py-1">
      <li
        v-for="item in menuItems"
        :key="item.action"
        role="menuitem"
        tabindex="0"
        class="cursor-pointer px-4 py-2 text-sm text-surface-700 hover:bg-surface-100 dark:text-surface-200 dark:hover:bg-surface-700"
        @click="handleAction(item.action)"
        @keydown.enter.prevent="handleAction(item.action)"
        @keydown.space.prevent="handleAction(item.action)"
      >
        {{ item.label }}
      </li>
    </ul>
  </div>
</template>
