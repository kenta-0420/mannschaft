<script setup lang="ts">
import type { HandoffResponse, HandoffLabelInfo } from '~/types/todoHandoff'

/**
 * F02.3.1 Phase 2 — TODO キャッチボール履歴タイムライン。
 *
 * 指定 TODO の引き渡し履歴を新しい順でカード一覧表示する。
 * 削除済みラベルはスナップショット名 +「（削除済み）」表示にフォールバック。
 */
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  todoId: number
}>()

const { t } = useI18n()
const todoApi = useTodoApi()
const notification = useNotification()
const { formatDateTime: formatDateTimeTz } = useDatetime()

const history = ref<HandoffResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await todoApi.getHandoffHistory(props.scopeType, props.scopeId, props.todoId)
    history.value = res.data
  } catch {
    notification.error(t('handoff.error.loadHistory'))
  } finally {
    loading.value = false
  }
}

defineExpose({ reload: load })

onMounted(() => {
  void load()
})

watch(() => [props.scopeType, props.scopeId, props.todoId], () => {
  void load()
})

function formatDateTime(s: string): string {
  return formatDateTimeTz(s)
}

function labelColor(info: HandoffLabelInfo | null): string {
  if (!info) return '#94a3b8'
  return info.color ?? '#94a3b8'
}

function labelDisplayName(info: HandoffLabelInfo | null): string {
  if (!info) return '—'
  const base = info.name ?? '—'
  return info.deleted ? `${base} ${t('handoff.timeline.deletedLabel')}` : base
}
</script>

<template>
  <div>
    <h3 class="mb-3 text-base font-semibold">{{ t('handoff.timeline.title') }}</h3>

    <div v-if="loading" class="space-y-2">
      <Skeleton height="4rem" />
      <Skeleton height="4rem" />
    </div>

    <div v-else-if="history.length === 0" class="rounded-md border border-dashed border-surface-300 p-6 text-center text-sm text-surface-400 dark:border-surface-600">
      {{ t('handoff.timeline.empty') }}
    </div>

    <ol v-else class="space-y-3">
      <li
        v-for="row in history"
        :key="row.id"
        class="rounded-lg border border-surface-300 bg-surface-0 p-3 shadow-sm dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="mb-2 flex items-center justify-between text-xs text-surface-500">
          <span><i class="pi pi-calendar mr-1" />{{ formatDateTime(row.createdAt) }}</span>
        </div>

        <!-- from → to のアバター列 -->
        <div class="mb-2 flex flex-wrap items-center gap-1 text-sm">
          <span class="font-medium">{{ row.fromUser.displayName }}</span>
          <i class="pi pi-arrow-right mx-1 text-surface-400" />
          <span
            v-for="(to, idx) in row.toAssignees"
            :key="to.userId"
            class="rounded-full bg-surface-100 px-2 py-0.5 text-xs dark:bg-surface-700"
          >
            {{ to.displayName }}<span v-if="idx < row.toAssignees.length - 1">,</span>
          </span>
          <span v-if="row.toAssignees.length === 0" class="text-xs text-surface-400">—</span>
        </div>

        <!-- 旧ラベル → 新ラベル -->
        <div class="mb-2 flex flex-wrap items-center gap-2 text-xs">
          <span
            class="rounded-md px-2 py-0.5"
            :style="{ backgroundColor: labelColor(row.previousStatusLabel) + '22', color: labelColor(row.previousStatusLabel) }"
          >
            {{ labelDisplayName(row.previousStatusLabel) }}
          </span>
          <i class="pi pi-arrow-right text-surface-400" />
          <span
            class="rounded-md px-2 py-0.5"
            :style="{ backgroundColor: labelColor(row.newStatusLabel) + '22', color: labelColor(row.newStatusLabel) }"
          >
            {{ labelDisplayName(row.newStatusLabel) }}
          </span>
        </div>

        <!-- メッセージ -->
        <p
          v-if="row.message"
          class="mt-2 whitespace-pre-wrap rounded-md border border-surface-200 bg-surface-50 p-2 text-xs text-surface-700 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-300"
        >
          💬 {{ row.message }}
        </p>
      </li>
    </ol>
  </div>
</template>
