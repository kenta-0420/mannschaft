<script setup lang="ts">
import type { SurveyResponse, SurveyResultSummary } from '~/types/survey'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const { getSurveys, getResults } = useSurveyApi()
const { captureQuiet } = useErrorReport()

const scopeTypeUpper = computed(() =>
  props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION',
)

const surveys = ref<SurveyResponse[]>([])
const loading = ref(false)
const expandedId = ref<number | null>(null)
const resultsMap = ref<Record<number, SurveyResultSummary[]>>({})
const resultsLoading = ref<Record<number, boolean>>({})

async function load() {
  loading.value = true
  surveys.value = []
  try {
    const [pubRes, closedRes] = await Promise.allSettled([
      getSurveys(scopeTypeUpper.value, props.scopeId, { status: 'PUBLISHED', size: 20 }),
      getSurveys(scopeTypeUpper.value, props.scopeId, { status: 'CLOSED', size: 20 }),
    ])
    const pub = pubRes.status === 'fulfilled' ? pubRes.value.data : []
    const closed = closedRes.status === 'fulfilled' ? closedRes.value.data : []
    // 受付中を先頭、次に締切（作成日降順）
    surveys.value = [
      ...pub.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()),
      ...closed.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()),
    ]
  } catch (err) {
    captureQuiet(err, { context: 'WidgetSurveyResults: アンケート取得' })
  } finally {
    loading.value = false
  }
}

async function toggleExpand(survey: SurveyResponse) {
  if (expandedId.value === survey.id) {
    expandedId.value = null
    return
  }
  expandedId.value = survey.id
  if (!resultsMap.value[survey.id]) {
    resultsLoading.value = { ...resultsLoading.value, [survey.id]: true }
    try {
      const res = await getResults(survey.id)
      resultsMap.value = { ...resultsMap.value, [survey.id]: res.data }
    } catch (err) {
      captureQuiet(err, { context: `WidgetSurveyResults: 結果取得 surveyId=${survey.id}` })
      resultsMap.value = { ...resultsMap.value, [survey.id]: [] }
    } finally {
      resultsLoading.value = { ...resultsLoading.value, [survey.id]: false }
    }
  }
}

function responseRate(survey: SurveyResponse): number {
  if (!survey.targetCount) return 0
  return Math.min(100, Math.round((survey.responseCount / survey.targetCount) * 100))
}

function rateColor(rate: number): string {
  if (rate >= 80) return '#22c55e'
  if (rate >= 50) return '#f59e0b'
  return '#6366f1'
}

onMounted(load)
</script>

<template>
  <div @click.stop>
    <!-- ヘッダー -->
    <div class="mb-3 flex items-center justify-between">
      <span class="text-xs text-surface-400">{{ surveys.length }}件のアンケート</span>
      <Button icon="pi pi-refresh" text rounded size="small" :loading="loading" @click="load" />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 3" :key="i" height="3.5rem" />
    </div>

    <!-- 一覧 -->
    <div v-else-if="surveys.length > 0" class="space-y-2">
      <div
        v-for="survey in surveys"
        :key="survey.id"
        class="overflow-hidden rounded-lg border border-surface-300 dark:border-surface-600"
      >
        <!-- サマリー行 -->
        <button
          class="flex w-full items-center gap-3 bg-surface-0 px-3 py-2.5 text-left transition-colors hover:bg-surface-50 dark:bg-surface-800 dark:hover:bg-surface-700/60"
          @click.stop="toggleExpand(survey)"
        >
          <!-- ステータスバッジ -->
          <span
            class="shrink-0 rounded px-1.5 py-0.5 text-xs font-medium"
            :class="survey.status === 'PUBLISHED' ? 'bg-green-100 text-green-700' : 'bg-surface-100 text-surface-500'"
          >
            {{ survey.status === 'PUBLISHED' ? '受付中' : '締切' }}
          </span>

          <!-- タイトル -->
          <span class="min-w-0 flex-1 truncate text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ survey.title }}
          </span>

          <!-- 回答率 -->
          <div class="shrink-0 text-right">
            <div class="mb-0.5 flex items-center justify-end gap-1.5">
              <span class="text-xs font-semibold" :style="{ color: rateColor(responseRate(survey)) }">
                {{ survey.responseCount }}{{ survey.targetCount ? `/${survey.targetCount}` : '' }}
              </span>
              <span class="text-xs text-surface-400">件</span>
            </div>
            <!-- 進捗バー -->
            <div v-if="survey.targetCount" class="h-1.5 w-20 overflow-hidden rounded-full bg-surface-200 dark:bg-surface-600">
              <div
                class="h-full rounded-full transition-all"
                :style="{ width: `${responseRate(survey)}%`, backgroundColor: rateColor(responseRate(survey)) }"
              />
            </div>
          </div>

          <!-- 展開アイコン -->
          <i
            class="pi shrink-0 text-xs text-surface-400 transition-transform"
            :class="expandedId === survey.id ? 'pi-chevron-up' : 'pi-chevron-down'"
          />
        </button>

        <!-- 展開: 質問ごとの結果グラフ -->
        <div
          v-if="expandedId === survey.id"
          class="border-t border-surface-200 bg-surface-50 px-3 py-3 dark:border-surface-600 dark:bg-surface-700/20"
          @click.stop
        >
          <!-- ローディング -->
          <div v-if="resultsLoading[survey.id]" class="space-y-2 py-2">
            <Skeleton v-for="i in 2" :key="i" height="6rem" />
          </div>

          <!-- 結果なし -->
          <div
            v-else-if="!resultsMap[survey.id] || resultsMap[survey.id].length === 0"
            class="py-4 text-center text-sm text-surface-400"
          >
            <i class="pi pi-chart-bar mb-2 block text-2xl" />
            {{ survey.responseCount === 0 ? 'まだ回答がありません' : '結果を表示できません' }}
          </div>

          <!-- 質問ごとのグラフ -->
          <div v-else>
            <SurveyQuestionChart
              v-for="result in resultsMap[survey.id]"
              :key="result.questionId"
              :result="result"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 空状態 -->
    <div v-else class="py-8 text-center">
      <i class="pi pi-chart-bar mb-2 text-3xl text-surface-300" />
      <p class="text-sm text-surface-400">アンケートがありません</p>
    </div>
  </div>
</template>
