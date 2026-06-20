<script setup lang="ts">
/**
 * F08.10 チーム分析（チームの試合統計）— 04_frontend_and_ux.md §G.3 / §G.9 / §F.3。
 *
 * チームの勝敗・得失点・選手別ランキング・直近フォーム・種別別試合数を可視化する。
 * 集計 API は `/organizations/{orgId}/teams/{teamId}/match-stats`（useMatchAnalytics.getTeamStats）。
 * org コンテキストは useMatchOrgContext で teamSlug から解決する（3 ページ共通化）。
 * 試合記録が無い場合は空状態＋「試合を記録」CTA を出す（§G.8）。
 *
 * [slug] ルート配下に移設済み（旧: teams/[id]/match-analytics.vue）。
 * route.params.slug を読むことで layout が正しくサイドバーを表示できる。
 */
import type { TeamMatchStatsResponse } from '~/types/match'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
// [slug] ルートでは params.slug を使う（params.id は undefined）
const teamSlug = computed(() => String(route.params.slug))
const { t } = useI18n()

const { resolveContext } = useMatchOrgContext()
const analytics = useMatchAnalytics()

const orgId = ref<number | null>(null)
const stats = ref<TeamMatchStatsResponse | null>(null)
const loading = ref(true)

/** 試合記録が 1 件も無い（空状態判定） */
const isEmpty = computed(() => (stats.value?.totalMatches ?? 0) === 0)

async function load(): Promise<void> {
  loading.value = true
  try {
    // resolveContext は tm.slug === 引数 で照合するため slug を渡す（数値 ID 不可）
    const ctx = await resolveContext(teamSlug.value)
    orgId.value = ctx?.orgId ?? null
    if (ctx === null) {
      stats.value = null
      return
    }
    stats.value = await analytics.getTeamStats(ctx.orgId, ctx.teamId)
  } catch {
    // エラーは composable 内で通知済み
    stats.value = null
  } finally {
    loading.value = false
  }
}

watch(teamSlug, () => void load())
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 py-4">
    <div class="mb-1 flex items-center gap-3">
      <PageHeader :title="t('match.analytics.team_title')" size="sm" :back-to="`/teams/${teamSlug}`" />
    </div>
    <p class="mb-6 text-sm text-surface-500">{{ t('match.analytics.team_subtitle') }}</p>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- 組織未解決 -->
      <DashboardEmptyState
        v-if="orgId === null"
        icon="pi pi-building"
        :message="t('match.analytics.empty.no_team')"
      />

      <!-- 試合記録が無い（空状態＋作成 CTA・§G.8） -->
      <div
        v-else-if="isEmpty"
        class="flex flex-col items-center gap-4 py-16 text-center text-surface-500"
      >
        <i class="pi pi-chart-bar text-5xl text-surface-300" />
        <p>{{ t('match.analytics.empty.no_matches') }}</p>
        <NuxtLink
          :to="`/teams/${teamSlug}/matches`"
          class="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 font-semibold text-primary-contrast"
        >
          <i class="pi pi-plus" />
          {{ t('match.analytics.empty.record_cta') }}
        </NuxtLink>
      </div>

      <!-- 分析チャート群 -->
      <TeamMatchAnalyticsCharts v-else-if="stats" :stats="stats" />
    </template>
  </div>
</template>
