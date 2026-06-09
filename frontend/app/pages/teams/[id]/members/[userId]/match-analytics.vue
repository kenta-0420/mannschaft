<script setup lang="ts">
/**
 * F08.10 個人分析（メンバーの統計）— 04_frontend_and_ux.md §G.3 / §G.9。
 *
 * チームメンバーのチーム別統計（getUserTeamStats・teamId 必須）を可視化する。
 * 閲覧可否は BE 側で「本人 / チーム管理者 / F19.1 公開設定」に応じて制御されるため、
 * FE は 403 を分かりやすく表示する（握りつぶさない・CLAUDE.md 根治治療）。
 * 試合記録が無い場合は空状態を出す（§G.8）。
 */
import type { UserMatchStatsResponse } from '~/types/match'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()

const teamIdStr = computed(() => String(route.params.id))
const userId = computed(() => Number(route.params.userId))

const { resolveOrgId } = useMatchOrgContext()
const analytics = useMatchAnalytics()

const stats = ref<UserMatchStatsResponse | null>(null)
const loading = ref(true)
/** 403（閲覧権限なし）を検出したフラグ */
const forbidden = ref(false)

const isEmpty = computed(() => (stats.value?.totalMatches ?? 0) === 0)

/** ofetch のエラーから HTTP ステータスを取り出す（any 禁止・型ガード） */
function statusOf(err: unknown): number | null {
  if (typeof err === 'object' && err !== null && 'statusCode' in err) {
    const code = (err as { statusCode?: unknown }).statusCode
    return typeof code === 'number' ? code : null
  }
  return null
}

async function load(): Promise<void> {
  loading.value = true
  forbidden.value = false
  try {
    const orgId = await resolveOrgId(teamIdStr.value)
    if (orgId === null || !Number.isFinite(userId.value)) {
      stats.value = null
      return
    }
    stats.value = await analytics.getUserTeamStats(orgId, userId.value, Number(teamIdStr.value))
  } catch (err) {
    if (statusOf(err) === 403) {
      forbidden.value = true
      stats.value = null
    } else {
      // 403 以外は composable 側でトースト済み。再 throw せずページの空表示に留める。
      stats.value = null
    }
  } finally {
    loading.value = false
  }
}

watch([teamIdStr, userId], () => void load())
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-2 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="t('match.analytics.member_title')" />
    </div>
    <p class="mb-6 text-sm text-surface-500">{{ t('match.analytics.member_subtitle') }}</p>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- 閲覧権限なし（403・§G.9 認可連動） -->
      <DashboardEmptyState
        v-if="forbidden"
        icon="pi pi-lock"
        :message="t('match.analytics.forbidden')"
      />

      <!-- 試合記録が無い -->
      <DashboardEmptyState
        v-else-if="isEmpty"
        icon="pi pi-chart-bar"
        :message="t('match.analytics.empty.member_no_matches')"
      />

      <!-- 分析チャート群 -->
      <MatchAnalyticsCharts v-else-if="stats" :stats="stats" />
    </template>
  </div>
</template>
