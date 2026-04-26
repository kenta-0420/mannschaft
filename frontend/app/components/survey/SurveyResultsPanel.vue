<script setup lang="ts">
import type { SurveyResultSummary } from '~/types/survey'

const props = defineProps<{
  surveyId: number
}>()

const { t } = useI18n()
const { getResults } = useSurveyApi()
const { error: showError } = useNotification()

const results = ref<SurveyResultSummary[]>([])
const loading = ref(false)
const fetchFailed = ref(false)

async function loadResults() {
  loading.value = true
  fetchFailed.value = false
  try {
    const res = await getResults(props.surveyId)
    results.value = res.data ?? []
  } catch {
    fetchFailed.value = true
    showError(t('surveys.detail.results.loadFailedToast'))
  } finally {
    loading.value = false
  }
}

onMounted(loadResults)
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="flex items-center justify-between">
      <h2 class="text-lg font-semibold text-surface-800 dark:text-surface-100">{{ t('surveys.detail.results.title') }}</h2>
      <Button
        :label="t('surveys.detail.results.reload')"
        icon="pi pi-refresh"
        size="small"
        outlined
        :loading="loading"
        @click="loadResults"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <!-- 失敗時の再試行 -->
    <div
      v-else-if="fetchFailed"
      class="flex flex-col items-center gap-3 rounded-lg border border-red-200 bg-red-50 p-6 text-center dark:border-red-700 dark:bg-red-900/20"
    >
      <i class="pi pi-exclamation-triangle text-2xl text-red-500" />
      <p class="text-sm text-red-700 dark:text-red-200">{{ t('surveys.detail.results.fetchFailed') }}</p>
      <Button :label="t('surveys.detail.results.retry')" icon="pi pi-refresh" size="small" @click="loadResults" />
    </div>

    <!-- 空状態 -->
    <div
      v-else-if="results.length === 0"
      class="flex flex-col items-center gap-2 rounded-lg border border-dashed border-surface-300 bg-surface-50 p-8 text-center dark:border-surface-600 dark:bg-surface-800/40"
    >
      <i class="pi pi-chart-bar text-3xl text-surface-300" />
      <p class="text-sm text-surface-400">{{ t('surveys.detail.results.empty') }}</p>
    </div>

    <!-- 結果一覧 -->
    <div v-else class="flex flex-col gap-2">
      <SurveyQuestionChart v-for="r in results" :key="r.questionId" :result="r" />
    </div>
  </div>
</template>
