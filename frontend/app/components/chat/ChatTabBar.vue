<script setup lang="ts">
import type { ChatTab } from '~/types/chat'

// -----------------------------------------------------------------------
// Props / Emits
// -----------------------------------------------------------------------
defineProps<{
  tabs: ChatTab[]
  activeTabId: string | null
  isMaxReached: boolean
}>()

const emit = defineEmits<{
  switch: [tabId: string]
  close: [tabId: string]
  openAdd: []
  contextmenu: [event: { tabId: string; x: number; y: number }]
}>()

// -----------------------------------------------------------------------
// ハンドラ
// -----------------------------------------------------------------------
function onTabSelect(tabId: string): void {
  emit('switch', tabId)
}

function onTabClose(tabId: string): void {
  emit('close', tabId)
}

function onTabContextmenu(event: { tabId: string; x: number; y: number }): void {
  emit('contextmenu', event)
}

function onAddClick(): void {
  emit('openAdd')
}
</script>

<template>
  <div
    class="flex items-stretch border-b border-surface-200 dark:border-surface-700 bg-surface-50 dark:bg-surface-800"
    role="tablist"
    :aria-label="$t('chat.tab.addNew')"
  >
    <!-- タブ横スクロール領域 -->
    <div
      class="flex flex-1 snap-x snap-mandatory overflow-x-auto scrollbar-thin scrollbar-thumb-surface-300 dark:scrollbar-thumb-surface-600"
      role="presentation"
    >
      <ChatTabItem
        v-for="tab in tabs"
        :key="tab.id"
        :tab="tab"
        :is-active="tab.id === activeTabId"
        class="snap-start"
        @select="onTabSelect"
        @close="onTabClose"
        @contextmenu="onTabContextmenu"
      />
    </div>

    <!-- + 追加ボタン（sticky: タブスクロール対象外） -->
    <div class="sticky right-0 flex shrink-0 items-center border-l border-surface-200 bg-surface-50 px-1 dark:border-surface-700 dark:bg-surface-800">
      <button
        type="button"
        :disabled="isMaxReached"
        :aria-label="$t('chat.tab.addNew')"
        :aria-disabled="isMaxReached"
        :title="isMaxReached ? $t('chat.tab.maxReached', { max: 10 }) : $t('chat.tab.addNew')"
        :class="[
          'flex h-7 w-7 items-center justify-center rounded transition-colors duration-150',
          isMaxReached
            ? 'cursor-not-allowed opacity-40'
            : 'cursor-pointer hover:bg-surface-200 dark:hover:bg-surface-700 text-surface-500 dark:text-surface-400 hover:text-surface-800 dark:hover:text-surface-100',
        ]"
        @click="onAddClick"
      >
        <i class="pi pi-plus text-sm" aria-hidden="true" />
      </button>
    </div>
  </div>
</template>
