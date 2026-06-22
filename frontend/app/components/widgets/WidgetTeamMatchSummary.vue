<script setup lang="ts">
/**
 * F08.10 チーム試合サマリウィジェット（04_frontend_and_ux.md §G.3 / §F.3）。
 *
 * 直近の試合成績（勝/分/敗・得失点・直近フォーム W/D/L 5 件）を
 * コンパクトに表示し、進行中試合があれば「記録を再開する」CTA を出す。
 * 詳細分析ページ（teams/[id]/match-analytics.vue）への導線も提供する。
 *
 * データ:
 *   - チーム統計: useMatchAnalytics.getTeamStats（orgId 解決は useMatchOrgContext）
 *   - 進行中試合: useMatchApi.listMatches（status=IN_PROGRESS・先頭 1 件のみ取得）
 *
 * org コンテキスト解決失敗時（未所属チーム等）は試合記録なしと同等の空状態を出す。
 * エラーは captureQuiet で通知し、ウィジェットとして致命的クラッシュしないようにする。
 */
import type { TeamMatchStatsResponse, MatchSummaryResponse } from '~/types/match'

const props = defineProps<{
  teamId: string
}>()

const { resolveContext } = useMatchOrgContext()
const analytics = useMatchAnalytics()
const matchApi = useMatchApi()
const { captureQuiet } = useErrorReport()

const loading = ref(false)
const orgId = ref<number | null>(null)
const stats = ref<TeamMatchStatsResponse | null>(null)
const inProgressMatch = ref<MatchSummaryResponse | null>(null)

const hasData = computed(() => (stats.value?.totalMatches ?? 0) > 0)

/** 直近フォーム（recentForm 配列・最新 5 件を先頭から表示）*/
const recentForm = computed<string[]>(() => (stats.value?.recentForm ?? []).slice(0, 5))

/** 得失点差の符号付き表示 */
const goalDiffText = computed(() => {
  const gd = stats.value?.goalDifference ?? 0
  return gd > 0 ? `+${gd}` : String(gd)
})

/** W/D/L バッジの色クラス */
function formResultClass(result: string): string {
  const r = result.toUpperCase()
  if (r === 'W') return 'bg-emerald-500 text-white'
  if (r === 'D') return 'bg-surface-400 text-white'
  return 'bg-red-500 text-white'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const ctx = await resolveContext(props.teamId)
    orgId.value = ctx?.orgId ?? null
    if (ctx === null) {
      stats.value = null
      inProgressMatch.value = null
      return
    }
    const [statsResult, inProgressResult] = await Promise.allSettled([
      analytics.getTeamStats(ctx.orgId, ctx.teamId),
      matchApi.listMatches(ctx.orgId, ctx.teamId, { status: 'IN_PROGRESS', size: 1 }),
    ])
    stats.value = statsResult.status === 'fulfilled' ? statsResult.value : null
    const page = inProgressResult.status === 'fulfilled' ? inProgressResult.value : null
    inProgressMatch.value = page?.data?.[0] ?? null
  } catch (err) {
    captureQuiet(err, { context: 'WidgetTeamMatchSummary: データ取得' })
    stats.value = null
    inProgressMatch.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.teamId, load)
</script>

<template>
  <div @click.stop>
    <!-- Skeleton ローディング -->
    <div v-if="loading" class="space-y-2 py-4">
      <Skeleton height="2rem" />
      <Skeleton height="2rem" />
      <Skeleton height="3rem" />
    </div>

    <!-- 空状態（試合記録なし・または org 未解決） -->
    <div
      v-else-if="!hasData"
      class="flex flex-col items-center gap-3 py-8 text-center text-surface-400"
    >
      <i class="pi pi-flag text-3xl text-surface-300" />
      <p class="text-sm">{{ $t('match.analytics.widget.summary.no_matches') }}</p>
      <NuxtLink
        :to="`/teams/${teamId}/matches`"
        class="inline-flex items-center gap-1 rounded-md bg-primary px-3 py-1.5 text-xs font-semibold text-primary-contrast"
        @click.stop
      >
        <i class="pi pi-plus text-xs" />
        {{ $t('match.analytics.empty.record_cta') }}
      </NuxtLink>
    </div>

    <!-- データあり -->
    <div v-else class="space-y-3">
      <!-- 進行中試合 CTA -->
      <div
        v-if="inProgressMatch"
        class="flex items-center justify-between rounded-lg border border-orange-200 bg-orange-50 px-3 py-2 dark:border-orange-800 dark:bg-orange-950"
      >
        <div class="flex items-center gap-2 text-sm font-semibold text-orange-700 dark:text-orange-300">
          <span class="inline-block size-2 animate-pulse rounded-full bg-orange-500" />
          <span>{{ $t('match.analytics.widget.summary.in_progress_label') }}</span>
          <span v-if="inProgressMatch.opponentName" class="text-xs font-normal">
            vs {{ inProgressMatch.opponentName }}
          </span>
        </div>
        <NuxtLink
          :to="`/teams/${teamId}/matches/${inProgressMatch.id}/live`"
          class="inline-flex items-center gap-1 rounded-md bg-orange-500 px-2.5 py-1 text-xs font-semibold text-white hover:bg-orange-600"
          @click.stop
        >
          <i class="pi pi-play text-xs" />
          {{ $t('match.analytics.widget.summary.resume_recording') }}
        </NuxtLink>
      </div>

      <!-- 通算成績サマリ（勝/分/敗・得点/失点・得失点差） -->
      <div class="grid grid-cols-3 gap-2 text-center">
        <div class="rounded-lg bg-surface-50 p-2 dark:bg-surface-800">
          <div class="text-[10px] text-surface-500">{{ $t('match.analytics.widget.summary.wdl_label') }}</div>
          <div class="text-sm font-semibold">
            {{ stats?.wins ?? 0 }}/{{ stats?.draws ?? 0 }}/{{ stats?.losses ?? 0 }}
          </div>
        </div>
        <div class="rounded-lg bg-surface-50 p-2 dark:bg-surface-800">
          <div class="text-[10px] text-surface-500">{{ $t('match.analytics.widget.summary.goals_label') }}</div>
          <div class="text-sm font-semibold">
            {{ stats?.totalGoalsFor ?? 0 }}/{{ stats?.totalGoalsAgainst ?? 0 }}
          </div>
        </div>
        <div class="rounded-lg bg-surface-50 p-2 dark:bg-surface-800">
          <div class="text-[10px] text-surface-500">{{ $t('match.analytics.team.metric.goal_difference') }}</div>
          <div
            class="text-sm font-semibold"
            :class="{
              'text-emerald-600 dark:text-emerald-400': (stats?.goalDifference ?? 0) > 0,
              'text-red-600 dark:text-red-400': (stats?.goalDifference ?? 0) < 0,
            }"
          >
            {{ goalDiffText }}
          </div>
        </div>
      </div>

      <!-- 直近フォーム（W/D/L バッジ 5 件） -->
      <div v-if="recentForm.length > 0">
        <div class="mb-1 text-[10px] text-surface-500">{{ $t('match.analytics.widget.summary.recent_form') }}</div>
        <div class="flex gap-1">
          <span
            v-for="(r, i) in recentForm"
            :key="i"
            class="inline-flex size-6 items-center justify-center rounded text-xs font-bold"
            :class="formResultClass(r)"
          >
            {{ r.toUpperCase() }}
          </span>
        </div>
      </div>

      <!-- 詳細分析導線 -->
      <div class="pt-1">
        <NuxtLink
          :to="`/teams/${teamId}/match-analytics`"
          class="flex items-center gap-1 text-xs text-primary hover:underline"
          @click.stop
        >
          <i class="pi pi-chart-bar text-xs" />
          {{ $t('match.analytics.widget.summary.view_analytics') }}
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
