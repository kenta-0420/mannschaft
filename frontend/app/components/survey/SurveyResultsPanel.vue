<script setup lang="ts">
import type { SurveyResultSummary } from '~/types/survey'
import { shouldFetchResultsOnMount } from '~/utils/surveyResults'

const props = defineProps<{
  surveyId: number
  /**
   * 呼び出し側が既に取得済みの集計。
   *
   * 渡された場合は初回取得を省いてこれを使う。詳細ページは配信対象かどうかを確かめるため
   * 結果取得を1回行っており（403 をサーバーの判定として扱う）、それを捨てて本パネルが
   * もう一度取りに行くと、**結果を閲覧できる全ユーザーの全表示で高コストな集計と転送が
   * 必ず2回**走ってしまうため。
   *
   * 省略時（および null）は従来どおり自分で取得する。他画面からの利用を壊さないよう
   * 「渡されなければ自分で取りに行く」を既定に保つこと。
   */
  initialResults?: SurveyResultSummary[] | null
}>()

const { t } = useI18n()
const { getResults } = useSurveyApi()
const { error: showError } = useNotification()

const results = ref<SurveyResultSummary[]>([])
const loading = ref(false)
const fetchFailed = ref(false)
/**
 * サーバーが結果閲覧を拒否した（403）。
 *
 * BE は `ALWAYS` の閲覧範囲を配信母集団に限定しているため、`TARGETED` の名簿外や
 * `includeSupporters=false` で除外された SUPPORTER はここで 403 になる。
 * 呼び出し側（詳細ページ）が先にサーバー判定を仰いで本パネル自体を出さないが、
 * 権限が途中で変わった場合に備えて多層で守る。再試行しても通らないため
 * 「失敗＋再試行」ではなく理由を明示する（症状を握りつぶさない）。
 */
const forbidden = ref(false)

async function loadResults() {
  loading.value = true
  fetchFailed.value = false
  forbidden.value = false
  try {
    const res = await getResults(props.surveyId)
    results.value = res.data ?? []
  } catch (e) {
    const err = e as { statusCode?: number; response?: { status?: number } }
    const code = err.statusCode ?? err.response?.status
    if (code === 403) {
      forbidden.value = true
    } else {
      fetchFailed.value = true
      showError(t('surveys.detail.results.loadFailedToast'))
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 既に取得済みの集計を渡されていればそれを使い、二重取得を避ける。
  // 判定は utils/surveyResults.ts に切り出してある（空配列＝回答ゼロを falsy 扱いして
  // 二重取得が復活する罠を、画面をマウントせずに固定するため）。
  if (!shouldFetchResultsOnMount(props.initialResults)) {
    results.value = props.initialResults ?? []
    return
  }
  return loadResults()
})
</script>

<template>
  <div class="flex flex-col gap-3" data-testid="survey-results-panel">
    <div class="flex items-center justify-between">
      <h2 class="text-lg font-semibold text-surface-800 dark:text-surface-100">{{ t('surveys.detail.results.title') }}</h2>
      <Button
        :label="t('surveys.detail.results.reload')"
        icon="pi pi-refresh"
        size="small"
        outlined
        :loading="loading"
        data-testid="survey-results-refresh"
        @click="loadResults"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <!-- 権限なし（403）。再試行しても通らないため再試行ボタンは出さない -->
    <div
      v-else-if="forbidden"
      class="flex flex-col items-center gap-2 rounded-lg border border-surface-300 bg-surface-50 p-6 text-center dark:border-surface-600 dark:bg-surface-800/60"
      data-testid="survey-results-forbidden"
    >
      <i class="pi pi-lock text-2xl text-surface-400" />
      <p class="text-sm text-surface-500 dark:text-surface-300">
        {{ t('surveys.results.forbidden.title') }}
      </p>
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
      data-testid="survey-results-empty"
    >
      <i class="pi pi-chart-bar text-3xl text-surface-300" />
      <p class="text-sm text-surface-400">{{ t('surveys.detail.results.empty') }}</p>
    </div>

    <!-- 結果一覧 -->
    <div v-else class="flex flex-col gap-2">
      <div v-for="r in results" :key="r.questionId" :data-testid="`result-question-${r.questionId}`">
        <SurveyQuestionChart :result="r" />
      </div>
    </div>
  </div>
</template>
