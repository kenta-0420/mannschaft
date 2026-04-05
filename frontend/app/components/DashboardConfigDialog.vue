<script setup lang="ts">
import type { WidgetDefinition } from '~/composables/useDashboardWidgets'

defineProps<{
  visible: boolean
  widgets: WidgetDefinition[]
  isVisible: (key: string) => boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  toggle: [key: string]
  reorder: [fromIndex: number, toIndex: number]
}>()

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
    emit('reorder', dragIndex.value, index)
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
  <Dialog
    :visible="visible"
    header="ウィジェット設定"
    modal
    :style="{ width: '450px' }"
    @update:visible="emit('update:visible', $event)"
  >
    <p class="mb-4 text-sm text-surface-500">
      ドラッグで並び替え、スイッチで表示・非表示を切り替えられます。
    </p>
    <div class="space-y-1">
      <div
        v-for="(w, index) in widgets"
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
          <p class="text-sm font-medium">{{ w.label }}</p>
          <p class="text-xs text-surface-500">{{ w.description }}</p>
        </div>
        <ToggleSwitch :model-value="isVisible(w.key)" @update:model-value="emit('toggle', w.key)" />
      </div>
    </div>
  </Dialog>
</template>
