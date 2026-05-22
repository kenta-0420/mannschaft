<script setup lang="ts">
import type { AiAnalysisResponse } from '~/types/error-report'

const props = defineProps<{
  items: AiAnalysisResponse[]
  loading?: boolean
  hasMore?: boolean
}>()

const emit = defineEmits<{
  'load-more': []
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

const expanded = ref(false)

function formatDate(value: string): string {
  return formatDateTime(value)
}

function toggle() {
  expanded.value = !expanded.value
}

function loadMore() {
  emit('load-more')
}
</script>

<template>
  <section
    class="rounded-xl border border-surface-300 bg-surface-0 dark:border-surface-600 dark:bg-surface-800"
  >
    <button
      type="button"
      class="flex w-full items-center justify-between px-5 py-3 text-sm font-semibold hover:bg-surface-50 dark:hover:bg-surface-700"
      @click="toggle"
    >
      <span>{{ t('error_report.ai.history_title') }} ({{ props.items.length }})</span>
      <span class="text-xs">{{ expanded ? '▲' : '▼' }}</span>
    </button>

    <div v-if="expanded" class="border-t border-surface-200 px-5 py-3 dark:border-surface-700">
      <ol v-if="props.items.length > 0" class="space-y-3">
        <li
          v-for="item in props.items"
          :key="item.id"
          class="rounded-md border border-surface-200 p-3 text-xs dark:border-surface-700"
        >
          <header class="mb-1 flex items-center justify-between gap-2">
            <span
              class="rounded-full px-2 py-0.5 font-semibold"
              :class="item.status === 'SUCCESS'
                ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
                : 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'"
            >
              {{ item.status }}
            </span>
            <span class="text-surface-500">{{ formatDate(item.createdAt) }}</span>
          </header>
          <p v-if="item.status === 'SUCCESS' && item.estimatedCause" class="break-words">
            {{ item.estimatedCause }}
          </p>
          <p v-else-if="item.status === 'FAILED' && item.errorMessage" class="break-words text-red-600 dark:text-red-300">
            {{ item.errorMessage }}
          </p>
          <p class="mt-1 text-surface-500">
            {{ t('error_report.ai.model') }}: {{ item.modelName }} ·
            {{ t('error_report.ai.tokens') }}:
            {{ t('error_report.ai.tokens_summary', {
              prompt: item.promptTokens,
              completion: item.completionTokens
            }) }}
          </p>
        </li>
      </ol>
      <p v-else class="text-center text-sm text-surface-500">
        {{ t('error_report.ai.no_analysis') }}
      </p>

      <div v-if="props.hasMore" class="mt-3 text-center">
        <button
          type="button"
          class="rounded-md border border-surface-300 px-3 py-1 text-sm hover:bg-surface-100 dark:border-surface-600 dark:hover:bg-surface-700 disabled:opacity-50"
          :disabled="props.loading"
          @click="loadMore"
        >
          {{ t('error_report.timeline.load_more') }}
        </button>
      </div>
    </div>
  </section>
</template>
