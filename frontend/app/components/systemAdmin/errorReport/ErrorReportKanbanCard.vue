<script setup lang="ts">
/**
 * F12.5 Phase 2-E — Kanban カード（1 件のエラーレポートを表示）。
 *
 * <p>severity 色帯 + メッセージ要約 + 発生回数/影響ユーザー + 担当者 +
 * 最終発生時刻 + GitHub/AI バッジを表示する。クリックで詳細ページへ遷移。</p>
 */
import type { KanbanCard } from '~/types/error-report'

const props = defineProps<{
  card: KanbanCard
}>()

const emit = defineEmits<{
  open: [id: number]
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

function severityBarClass(severity: string): string {
  switch (severity) {
    case 'CRITICAL':
      return 'bg-red-500'
    case 'HIGH':
      return 'bg-orange-500'
    case 'MEDIUM':
      return 'bg-yellow-500'
    default:
      return 'bg-surface-300 dark:bg-surface-600'
  }
}

function formatRelative(value: string): string {
  return formatDateTime(value)
}

function onClick() {
  emit('open', props.card.id)
}
</script>

<template>
  <article
    class="group relative cursor-grab overflow-hidden rounded-lg border border-surface-200 bg-surface-0 shadow-sm transition hover:shadow-md active:cursor-grabbing dark:border-surface-700 dark:bg-surface-800"
    role="button"
    tabindex="0"
    @click="onClick"
    @keydown.enter="onClick"
  >
    <!-- severity 色帯 -->
    <div :class="['h-1 w-full', severityBarClass(card.severity)]" aria-hidden="true" />

    <div class="space-y-2 p-3">
      <!-- メッセージ -->
      <p class="line-clamp-2 break-all text-sm font-medium text-surface-900 dark:text-surface-100">
        {{ card.errorMessage }}
      </p>

      <!-- ページ URL -->
      <p
        v-if="card.pageUrl"
        class="truncate font-mono text-xs text-surface-500 dark:text-surface-400"
        :title="card.pageUrl"
      >
        {{ card.pageUrl }}
      </p>

      <!-- 発生回数 / 影響ユーザー -->
      <div class="flex flex-wrap items-center gap-2 text-xs text-surface-600 dark:text-surface-300">
        <span class="inline-flex items-center gap-1">
          <i class="pi pi-bolt" aria-hidden="true" />
          {{ t('error_report.kanban.card.occurrences', { count: card.occurrenceCount }) }}
        </span>
        <span class="inline-flex items-center gap-1">
          <i class="pi pi-users" aria-hidden="true" />
          {{ t('error_report.kanban.card.affected', { count: card.affectedUserCount }) }}
        </span>
      </div>

      <!-- 担当者 + 最終発生 -->
      <div class="flex items-center justify-between gap-2 text-xs">
        <span
          v-if="card.assigneeName"
          class="inline-flex items-center gap-1 rounded-full bg-surface-100 px-2 py-0.5 dark:bg-surface-700"
        >
          <i class="pi pi-user" aria-hidden="true" />
          {{ card.assigneeName }}
        </span>
        <span v-else class="text-surface-400">—</span>
        <span class="font-mono text-surface-500 dark:text-surface-400">
          {{ formatRelative(card.lastOccurredAt) }}
        </span>
      </div>

      <!-- バッジ -->
      <div v-if="card.hasGithubIssue || card.hasAiAnalysis" class="flex flex-wrap gap-1">
        <span
          v-if="card.hasGithubIssue"
          class="inline-flex items-center gap-1 rounded-full bg-blue-100 px-2 py-0.5 text-xs text-blue-700 dark:bg-blue-900/30 dark:text-blue-300"
        >
          <i class="pi pi-github" aria-hidden="true" />
          {{ t('error_report.kanban.card.has_github') }}
        </span>
        <span
          v-if="card.hasAiAnalysis"
          class="inline-flex items-center gap-1 rounded-full bg-purple-100 px-2 py-0.5 text-xs text-purple-700 dark:bg-purple-900/30 dark:text-purple-300"
        >
          <i class="pi pi-sparkles" aria-hidden="true" />
          {{ t('error_report.kanban.card.has_ai') }}
        </span>
      </div>
    </div>
  </article>
</template>
