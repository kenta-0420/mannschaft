<script setup lang="ts">
import type { InboxItem, InboxLabel } from '~/types/inbox'

/**
 * F04.11 Phase 2 — ラベル付与/解除 Popover。
 *
 * 1 タップで付与・再タップで解除するドロップダウン形式。
 * 上限（1 アイテムあたり最大 10 ラベル）に達した場合は警告を表示。
 * 手本: TagPicker.vue
 *
 * 設計書: docs/features/F04.11_notification_inbox/02_api_design.md §3.4
 */

const props = defineProps<{
  item: InboxItem
}>()

const { t } = useI18n()
const inboxStore = useInboxStore()
const notification = useNotification()
const { captureQuiet } = useErrorReport()

const MAX_LABELS_PER_ITEM = 10

const isOpen = ref(false)
const pickerRef = ref<HTMLDivElement | null>(null)

const assignedLabelIds = computed<Set<string>>(
  () => new Set(props.item.labels.map((l) => l.id)),
)

const canAddMore = computed(
  () => props.item.labels.length < MAX_LABELS_PER_ITEM,
)

/** ラベル一覧（ストアから取得）。 */
const labels = computed<InboxLabel[]>(() => inboxStore.labels)

function toggle() {
  isOpen.value = !isOpen.value
}

function close() {
  isOpen.value = false
}

/**
 * ラベルをクリック: 付与済みなら解除、未付与なら付与。
 */
async function onToggleLabel(label: InboxLabel) {
  if (assignedLabelIds.value.has(label.id)) {
    // 解除
    try {
      const ok = await inboxStore.unassignLabel(props.item.sourceType, props.item.sourceId, label.id)
      if (ok) {
        notification.success(t('inbox.label.removed'))
      }
    } catch (error) {
      captureQuiet(error, { context: 'InboxLabelPicker: ラベル解除' })
    }
  } else {
    // 付与
    if (!canAddMore.value) {
      notification.warn(t('inbox.label.perItemLimit'))
      return
    }
    try {
      const ok = await inboxStore.assignLabel(props.item.sourceType, props.item.sourceId, label.id)
      if (ok) {
        notification.success(t('inbox.label.assigned'))
      }
    } catch (error) {
      captureQuiet(error, { context: 'InboxLabelPicker: ラベル付与' })
    }
  }
}

/** ピッカー外クリックで閉じる。 */
function onClickOutside(event: MouseEvent) {
  if (pickerRef.value && !pickerRef.value.contains(event.target as Node)) {
    close()
  }
}

onMounted(async () => {
  document.addEventListener('click', onClickOutside)
  if (inboxStore.labels.length === 0) {
    await inboxStore.fetchLabels()
  }
})

onUnmounted(() => {
  document.removeEventListener('click', onClickOutside)
})
</script>

<template>
  <div ref="pickerRef" class="relative" data-testid="inbox-label-picker">
    <!-- トリガーボタン（親から slot で上書き可能） -->
    <Button
      icon="pi pi-tag"
      text
      size="small"
      :title="t('inbox.action.label')"
      :aria-label="t('inbox.action.label')"
      :data-testid="`inbox-label-picker-btn-${item.id}`"
      @click.stop="toggle"
    />

    <!-- ドロップダウン -->
    <div
      v-if="isOpen"
      class="absolute right-0 top-full z-20 min-w-[14rem] rounded-lg border border-surface-200 bg-white p-2 shadow-lg dark:border-surface-700 dark:bg-surface-900"
    >
      <p class="mb-1.5 px-2 text-xs font-semibold text-surface-500">
        {{ t('inbox.label.title') }}
      </p>

      <!-- 上限警告 -->
      <p
        v-if="!canAddMore"
        class="mb-1 px-2 text-xs text-amber-600 dark:text-amber-400"
        data-testid="inbox-label-picker-limit"
      >
        {{ t('inbox.label.perItemLimit') }}
      </p>

      <!-- ラベルが空 -->
      <p
        v-if="labels.length === 0 && !inboxStore.labelsLoading"
        class="px-2 py-1 text-xs text-surface-400"
        data-testid="inbox-label-picker-empty"
      >
        {{ t('inbox.label.empty') }}
      </p>

      <!-- ローディング -->
      <div v-if="inboxStore.labelsLoading" class="flex justify-center py-2">
        <i class="pi pi-spin pi-spinner text-sm text-primary" />
      </div>

      <!-- ラベル一覧 -->
      <button
        v-for="label in labels"
        :key="label.id"
        type="button"
        class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-surface-50 dark:hover:bg-surface-800"
        :data-testid="`inbox-label-picker-option-${label.id}`"
        @click="onToggleLabel(label)"
      >
        <!-- チェックマーク（付与済み） -->
        <i
          v-if="assignedLabelIds.has(label.id)"
          class="pi pi-check text-xs text-primary"
        />
        <span v-else class="inline-block w-[0.875rem]" />
        <!-- カラードット -->
        <span
          class="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
          :style="{ backgroundColor: label.color ?? '#94a3b8' }"
        />
        <span class="flex-1 truncate">{{ label.name }}</span>
      </button>
    </div>
  </div>
</template>
