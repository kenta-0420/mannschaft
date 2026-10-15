<script setup lang="ts">
import type { AiAnalysisResponse } from '~/types/error-report'

const props = defineProps<{
  /** 最新分析（NULL の場合は未実施を表示）。 */
  analysis: AiAnalysisResponse | null
  /** 再分析 / 取得中フラグ。 */
  loading?: boolean
}>()

const emit = defineEmits<{
  reanalyze: []
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

function formatDate(value: string | null | undefined): string {
  if (!value) return '-'
  return formatDateTime(value)
}

function onReanalyze() {
  emit('reanalyze')
}
</script>

<template>
  <section
    class="rounded-xl border border-surface-300 bg-surface-0 p-5 dark:border-surface-600 dark:bg-surface-800"
  >
    <header class="mb-3 flex items-center justify-between gap-2">
      <h3 class="text-base font-semibold">
        {{ t('error_report.detail.ai') }}
      </h3>
      <button
        type="button"
        class="rounded-md border border-surface-300 px-3 py-1 text-sm hover:bg-surface-100 dark:border-surface-600 dark:hover:bg-surface-700 disabled:opacity-50 disabled:cursor-not-allowed"
        :disabled="props.loading"
        @click="onReanalyze"
      >
        <span v-if="props.loading">{{ t('error_report.ai.in_progress') }}</span>
        <span v-else-if="props.analysis?.status === 'FAILED'">{{ t('error_report.ai.retry') }}</span>
        <span v-else>{{ t('error_report.ai.reanalyze') }}</span>
      </button>
    </header>

    <!-- 未実施 -->
    <div
      v-if="!props.analysis && !props.loading"
      class="rounded-md border border-dashed border-surface-300 p-6 text-center text-sm text-surface-500 dark:border-surface-600"
    >
      {{ t('error_report.ai.no_analysis') }}
    </div>

    <!-- 分析中 -->
    <div
      v-else-if="props.loading"
      class="rounded-md border border-dashed border-surface-300 p-6 text-center text-sm text-surface-500 dark:border-surface-600"
    >
      {{ t('error_report.ai.in_progress') }}
    </div>

    <!-- FAILED -->
    <div
      v-else-if="props.analysis && props.analysis.status === 'FAILED'"
      class="rounded-md border border-red-300 bg-red-50 p-4 text-sm text-red-700 dark:border-red-700/50 dark:bg-red-900/20 dark:text-red-300"
    >
      <p class="font-semibold">{{ t('error_report.ai.failed') }}</p>
      <p v-if="props.analysis.errorMessage" class="mt-2 break-words text-xs">
        {{ props.analysis.errorMessage }}
      </p>
    </div>

    <!-- SUCCESS -->
    <div v-else-if="props.analysis" class="space-y-4">
      <div>
        <h4 class="mb-1 text-xs font-semibold text-surface-500">
          {{ t('error_report.ai.estimated_cause') }}
        </h4>
        <p class="whitespace-pre-wrap break-words text-sm">
          {{ props.analysis.estimatedCause }}
        </p>
      </div>

      <div>
        <h4 class="mb-1 text-xs font-semibold text-surface-500">
          {{ t('error_report.ai.fix_proposal') }}
        </h4>
        <p class="whitespace-pre-wrap break-words text-sm">
          {{ props.analysis.fixProposal }}
        </p>
      </div>

      <div>
        <h4 class="mb-1 text-xs font-semibold text-surface-500">
          {{ t('error_report.ai.impact') }}
        </h4>
        <p class="whitespace-pre-wrap break-words text-sm">
          {{ props.analysis.impactAssessment }}
        </p>
      </div>

      <div v-if="props.analysis.suggestedFiles && props.analysis.suggestedFiles.length > 0">
        <h4 class="mb-1 text-xs font-semibold text-surface-500">
          {{ t('error_report.ai.files') }}
        </h4>
        <ul class="list-disc pl-5 text-sm">
          <li
            v-for="(file, idx) in props.analysis.suggestedFiles"
            :key="idx"
            class="font-mono text-xs"
          >
            {{ file }}
          </li>
        </ul>
      </div>

      <footer class="border-t border-surface-200 pt-2 text-xs text-surface-500 dark:border-surface-700">
        <span class="mr-3">{{ t('error_report.ai.model') }}: {{ props.analysis.modelName }}</span>
        <span class="mr-3">
          {{ t('error_report.ai.tokens') }}:
          {{ t('error_report.ai.tokens_summary', {
            prompt: props.analysis.promptTokens,
            completion: props.analysis.completionTokens
          }) }}
        </span>
        <span>{{ t('error_report.ai.last_analyzed') }}: {{ formatDate(props.analysis.createdAt) }}</span>
      </footer>
    </div>
  </section>
</template>
