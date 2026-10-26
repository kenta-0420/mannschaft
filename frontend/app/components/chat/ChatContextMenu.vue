<script setup lang="ts">
export interface ChatContextMenuItem {
  key: string
  label: string
  icon: string
  danger?: boolean
  disabled?: boolean
}

const props = defineProps<{
  items: ChatContextMenuItem[]
  visible: boolean
  x: number
  y: number
}>()

const emit = defineEmits<{
  select: [key: string]
  close: []
}>()

const menuEl = ref<HTMLElement | null>(null)

/** 画面端はみ出し防止クランプ後の実際の座標 */
const clampedPosition = computed(() => {
  if (!menuEl.value) {
    return { x: props.x, y: props.y }
  }
  const menuWidth = menuEl.value.offsetWidth || 200
  const menuHeight = menuEl.value.offsetHeight || 300
  const x = Math.min(props.x, window.innerWidth - menuWidth - 8)
  const y = Math.min(props.y, window.innerHeight - menuHeight - 8)
  return { x: Math.max(0, x), y: Math.max(0, y) }
})

function handleSelect(item: ChatContextMenuItem) {
  if (item.disabled) return
  emit('select', item.key)
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

watch(
  () => props.visible,
  (val) => {
    if (val) {
      document.addEventListener('mousedown', onOutsideMousedown)
      document.addEventListener('keydown', onKeydown)
    } else {
      document.removeEventListener('mousedown', onOutsideMousedown)
      document.removeEventListener('keydown', onKeydown)
    }
  },
)

onUnmounted(() => {
  document.removeEventListener('mousedown', onOutsideMousedown)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible"
      ref="menuEl"
      role="menu"
      data-testid="chat-context-menu"
      class="fixed z-50 min-w-[180px] overflow-hidden rounded-lg border border-surface-200 bg-surface-0 shadow-lg dark:border-surface-700 dark:bg-surface-800"
      :style="{ left: `${clampedPosition.x}px`, top: `${clampedPosition.y}px` }"
    >
      <ul class="py-1">
        <li
          v-for="item in items"
          :key="item.key"
          role="menuitem"
          data-testid="context-menu-item"
          :data-key="item.key"
          :tabindex="item.disabled ? -1 : 0"
          class="flex cursor-pointer items-center gap-2 px-4 py-2 text-sm"
          :class="[
            item.disabled
              ? 'cursor-not-allowed opacity-40'
              : item.danger
                ? 'text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-900/20'
                : 'text-surface-700 hover:bg-surface-100 dark:text-surface-200 dark:hover:bg-surface-700',
          ]"
          @click="handleSelect(item)"
          @keydown.enter.prevent="handleSelect(item)"
          @keydown.space.prevent="handleSelect(item)"
        >
          <i :class="item.icon" class="text-xs" />
          {{ item.label }}
        </li>
      </ul>
    </div>
  </Teleport>
</template>
