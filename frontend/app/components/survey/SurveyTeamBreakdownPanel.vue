<script setup lang="ts">
// F05.4 (B) 組織アンケートのチーム別内訳（by_team）表示パネル。
// - 認可: 組織 ADMIN 限定 EP（/api/v1/surveys/{id}/results/team-breakdown）。
//   本コンポーネントは呼び出し側（survey 詳細ページ）で ADMIN かつ組織スコープのときのみ描画する。
//   万一 403 が返った場合は症状を握りつぶさず「権限がありません」を表示する。
// - 5 名未満のチーム（masked=true）は内訳を隠し「集計対象外」と表現する。
// - teamId=null は「組織直接メンバー」グループ（i18n で表示名を決める）。
import type { SurveyTeamBreakdownResponse } from '~/composables/useSurveyApi'

const props = defineProps<{
  surveyId: number
}>()

const { t } = useI18n()
const { getTeamBreakdown } = useSurveyApi()

type Breakdown = SurveyTeamBreakdownResponse
type TeamItem = NonNullable<Breakdown['byTeam']>[number]
type QuestionResult = NonNullable<TeamItem['questionResults']>[number]

const data = ref<Breakdown | null>(null)
const loading = ref(false)
const fetchFailed = ref(false)
const forbidden = ref(false)

async function load() {
  loading.value = true
  fetchFailed.value = false
  forbidden.value = false
  try {
    const res = await getTeamBreakdown(props.surveyId)
    data.value = res.data ?? null
  } catch (e) {
    const status = (e as { statusCode?: number; response?: { status?: number } })
    const code = status.statusCode ?? status.response?.status
    if (code === 403) {
      forbidden.value = true
    } else {
      fetchFailed.value = true
    }
  } finally {
    loading.value = false
  }
}

const teams = computed<TeamItem[]>(() => data.value?.byTeam ?? [])
const totalRespondents = computed(() => data.value?.total?.respondentCount ?? 0)

function teamLabel(team: TeamItem): string {
  return team.teamId == null ? t('surveys.teamBreakdown.directMembers') : (team.teamName ?? '')
}

function optionPercentLabel(count: number, percentage: number): string {
  return t('surveys.teamBreakdown.optionCount', { count, percentage })
}

function questionResults(team: TeamItem): QuestionResult[] {
  return team.questionResults ?? []
}

onMounted(load)
</script>

<template>
  <section class="flex flex-col gap-3" data-testid="survey-team-breakdown-panel">
    <div class="flex items-center justify-between">
      <h2 class="text-lg font-semibold text-surface-800 dark:text-surface-100">
        {{ t('surveys.teamBreakdown.title') }}
      </h2>
      <Button
        :label="t('surveys.teamBreakdown.reload')"
        icon="pi pi-refresh"
        size="small"
        outlined
        :loading="loading"
        data-testid="survey-team-breakdown-refresh"
        @click="load"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <!-- 権限なし（403）: 症状を隠さず明示する -->
    <div
      v-else-if="forbidden"
      class="flex flex-col items-center gap-2 rounded-lg border border-surface-300 bg-surface-50 p-6 text-center dark:border-surface-600 dark:bg-surface-800/60"
      data-testid="survey-team-breakdown-forbidden"
    >
      <i class="pi pi-lock text-2xl text-surface-400" />
      <p class="text-sm text-surface-500 dark:text-surface-300">
        {{ t('surveys.teamBreakdown.forbidden') }}
      </p>
    </div>

    <!-- 取得失敗 -->
    <div
      v-else-if="fetchFailed"
      class="flex flex-col items-center gap-3 rounded-lg border border-red-200 bg-red-50 p-6 text-center dark:border-red-700 dark:bg-red-900/20"
    >
      <i class="pi pi-exclamation-triangle text-2xl text-red-500" />
      <p class="text-sm text-red-700 dark:text-red-200">{{ t('surveys.teamBreakdown.fetchFailed') }}</p>
      <Button :label="t('surveys.teamBreakdown.retry')" icon="pi pi-refresh" size="small" @click="load" />
    </div>

    <!-- 空（トグル OFF / 非組織 / 匿名 などで byTeam なし） -->
    <div
      v-else-if="teams.length === 0"
      class="flex flex-col items-center gap-2 rounded-lg border border-dashed border-surface-300 bg-surface-50 p-8 text-center dark:border-surface-600 dark:bg-surface-800/40"
      data-testid="survey-team-breakdown-empty"
    >
      <i class="pi pi-users text-3xl text-surface-300" />
      <p class="text-sm text-surface-400">{{ t('surveys.teamBreakdown.empty') }}</p>
    </div>

    <!-- チーム別内訳 -->
    <div v-else class="flex flex-col gap-3">
      <p class="text-xs text-surface-500 dark:text-surface-400">
        {{ t('surveys.teamBreakdown.totalRespondents', { count: totalRespondents }) }}
      </p>
      <div
        v-for="(team, ti) in teams"
        :key="team.teamId ?? `direct-${ti}`"
        class="rounded-lg border border-surface-200 p-3 dark:border-surface-700"
        :data-testid="`survey-team-breakdown-team-${team.teamId ?? 'direct'}`"
      >
        <div class="mb-2 flex items-center justify-between">
          <span class="text-sm font-semibold text-surface-700 dark:text-surface-200">
            {{ teamLabel(team) }}
          </span>
          <span class="text-xs text-surface-400">
            {{ t('surveys.teamBreakdown.respondentCount', { count: team.respondentCount ?? 0 }) }}
          </span>
        </div>

        <!-- 5 名未満マスク -->
        <p
          v-if="team.masked"
          class="rounded bg-surface-50 px-3 py-2 text-xs text-surface-400 dark:bg-surface-800"
          :data-testid="`survey-team-breakdown-masked-${team.teamId ?? 'direct'}`"
        >
          <i class="pi pi-eye-slash mr-1" />{{ t('surveys.teamBreakdown.masked') }}
        </p>

        <!-- 設問別集計 -->
        <div v-else class="flex flex-col gap-3">
          <div v-for="q in questionResults(team)" :key="q.questionId" class="text-xs">
            <p class="mb-1 font-medium text-surface-600 dark:text-surface-300">{{ q.questionText }}</p>
            <div
              v-for="o in (q.optionResults ?? [])"
              :key="o.optionId"
              class="flex items-center justify-between py-0.5"
            >
              <span class="text-surface-600 dark:text-surface-300">{{ o.optionText }}</span>
              <span class="text-surface-400">
                {{ optionPercentLabel(o.count ?? 0, o.percentage ?? 0) }}
              </span>
            </div>
            <p v-if="(q.optionResults ?? []).length === 0" class="text-surface-400">
              {{ t('surveys.teamBreakdown.noOptionData') }}
            </p>
          </div>
          <p v-if="questionResults(team).length === 0" class="text-xs text-surface-400">
            {{ t('surveys.teamBreakdown.noQuestionData') }}
          </p>
        </div>
      </div>
    </div>
  </section>
</template>
