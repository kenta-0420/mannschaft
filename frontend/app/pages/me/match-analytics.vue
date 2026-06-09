<script setup lang="ts">
/**
 * F08.10 個人分析（自分のキャリア統計）— 04_frontend_and_ux.md §G.3 / §G.8 / §G.9。
 *
 * 自分の横断キャリア統計（getUserStats）を radar/line/doughnut/bar で可視化する。
 * 集計 API は `/organizations/{orgId}/...` 配下のため org コンテキストが要る。
 * 自分の所属チーム（getScopeTabs('TEAM')）から組織を解決し、複数所属時はチーム選択で切替える。
 * 試合記録が無い場合は空状態＋「試合を記録」CTA を出す（§G.8）。
 */
import type { UserMatchStatsResponse } from '~/types/match'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const authStore = useAuthStore()
const scopeTabApi = useScopeTabApi()
const { resolveOrgId } = useMatchOrgContext()
const analytics = useMatchAnalytics()

const currentUserId = computed<number>(() => authStore.user?.id ?? 0)

/** 自分の所属チーム（org 解決の起点・複数所属時は選択肢になる） */
interface TeamOption {
  teamId: string
  label: string
}
const teamOptions = ref<TeamOption[]>([])
const selectedTeamId = ref<string | null>(null)

const orgId = ref<number | null>(null)
const stats = ref<UserMatchStatsResponse | null>(null)

const loadingTeams = ref(true)
const loadingStats = ref(false)

/** 試合記録が 1 件も無い（空状態判定） */
const isEmpty = computed(() => (stats.value?.totalMatches ?? 0) === 0)

async function loadTeams(): Promise<void> {
  loadingTeams.value = true
  try {
    const page = await scopeTabApi.getScopeTabs('TEAM')
    teamOptions.value = page.items.map((i) => ({ teamId: i.scopeId, label: i.name }))
    selectedTeamId.value = teamOptions.value[0]?.teamId ?? null
  } finally {
    loadingTeams.value = false
  }
}

async function loadStats(): Promise<void> {
  if (selectedTeamId.value === null || currentUserId.value === 0) return
  loadingStats.value = true
  try {
    const resolvedOrgId = await resolveOrgId(selectedTeamId.value)
    orgId.value = resolvedOrgId
    if (resolvedOrgId === null) {
      stats.value = null
      return
    }
    stats.value = await analytics.getUserStats(resolvedOrgId, currentUserId.value)
  } finally {
    loadingStats.value = false
  }
}

watch(selectedTeamId, () => {
  // チーム切替時は org キャッシュをリセットして再解決する。
  orgId.value = null
  void loadStats()
})

onMounted(async () => {
  await loadTeams()
  await loadStats()
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex flex-wrap items-end gap-3">
      <PageHeader :title="t('match.analytics.my_title')" />
      <Select
        v-if="teamOptions.length > 1"
        v-model="selectedTeamId"
        :options="teamOptions"
        option-label="label"
        option-value="teamId"
        class="w-56"
        :aria-label="t('match.analytics.team_context')"
      />
    </div>
    <p class="mb-6 text-sm text-surface-500">{{ t('match.analytics.my_subtitle') }}</p>

    <PageLoading v-if="loadingTeams || loadingStats" />

    <template v-else>
      <!-- 所属チームが無い -->
      <DashboardEmptyState
        v-if="teamOptions.length === 0"
        icon="pi pi-users"
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
          v-if="selectedTeamId"
          :to="`/teams/${selectedTeamId}/matches`"
          class="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 font-semibold text-primary-contrast"
        >
          <i class="pi pi-plus" />
          {{ t('match.analytics.empty.record_cta') }}
        </NuxtLink>
      </div>

      <!-- 分析チャート群 -->
      <MatchAnalyticsCharts v-else-if="stats" :stats="stats" />
    </template>
  </div>
</template>
