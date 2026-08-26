<script setup lang="ts">
/**
 * DashboardWidgetList — ウィジェット設定ページ用の一覧コンポーネント。
 *
 * useDashboardWidgets composable を setup 内で安全に使用するため、
 * 設定ページから切り出したコンポーネントとして実装する。
 * スコープ（personal/team/organization）またはscopeIdが変化した際は、
 * 親コンポーネントが `:key` でリマウントすることで composable を再初期化する。
 */
import { useDashboardWidgets } from '~/composables/useDashboardWidgets'
import type { ViewerRole } from '~/types/dashboard'

const props = withDefaults(
  defineProps<{
    scopeType: 'personal' | 'team' | 'organization'
    scopeId?: string
    /** 設定ページではすべてのウィジェットを表示するため ADMIN を渡す */
    viewerRole?: ViewerRole
  }>(),
  {
    scopeId: undefined,
    viewerRole: 'ADMIN',
  },
)

// setup 内で安全に composable を呼び出す（フックのルール遵守）
const { sortedWidgets, isVisible, toggleWidget, reorder } = useDashboardWidgets(
  props.scopeType,
  props.scopeId,
  props.viewerRole,
)

// ドラッグ&ドロップ状態
const dragIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)

function onDragStart(index: number, e: DragEvent) {
  dragIndex.value = index
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
  }
}

function onDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) {
    e.dataTransfer.dropEffect = 'move'
  }
  dropTargetIndex.value = index
}

function onDragLeave() {
  dropTargetIndex.value = null
}

function onDrop(index: number) {
  if (dragIndex.value !== null && dragIndex.value !== index) {
    reorder(dragIndex.value, index)
  }
  dragIndex.value = null
  dropTargetIndex.value = null
}

function onDragEnd() {
  dragIndex.value = null
  dropTargetIndex.value = null
}
</script>

<template>
  <div>
    <div
      v-if="sortedWidgets.length === 0"
      class="rounded-lg border border-surface-200 p-8 text-center text-sm text-surface-400 dark:border-surface-600"
    >
      {{ $t('dashboard.widget_settings.no_widgets_message') }}
    </div>

    <div v-else class="space-y-1">
      <div
        v-for="(w, index) in sortedWidgets"
        :key="w.key"
        draggable="true"
        class="flex cursor-grab items-center gap-2 rounded-lg border p-3 transition-colors active:cursor-grabbing"
        :class="[
          dragIndex === index
            ? 'border-primary/40 bg-primary/5 opacity-50'
            : dropTargetIndex === index
              ? 'border-primary bg-primary/10'
              : 'border-surface-200 dark:border-surface-600',
        ]"
        @dragstart="onDragStart(index, $event)"
        @dragover="onDragOver(index, $event)"
        @dragleave="onDragLeave"
        @drop="onDrop(index)"
        @dragend="onDragEnd"
      >
        <i class="pi pi-bars text-sm text-surface-400" />
        <i :class="w.icon" class="text-lg text-primary" />
        <div class="min-w-0 flex-1">
          <p class="text-sm font-medium">{{ $t(w.labelKey) }}</p>
          <p class="text-xs text-surface-500">{{ $t(w.descriptionKey) }}</p>
        </div>
        <ToggleSwitch
          :model-value="isVisible(w.key)"
          @update:model-value="toggleWidget(w.key)"
        />
      </div>
    </div>
  </div>
</template>
