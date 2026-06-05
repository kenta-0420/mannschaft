<script setup lang="ts">
/**
 * F22.1 チームパネル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.7
 * - タグ行（チーム）+ 検索フォーム（チーム）+ 厳選 8 ウィジェット。
 * - store.selectedTeamId のダッシュボードを getTeamDashboard で取得し表示。
 */
import type { TeamDashboardResponse } from '~/types/dashboard-scope'

const store = useScopeDashboardStore()
const { getTeamDashboard } = useDashboardApi()

const data = ref<TeamDashboardResponse | null>(null)
const loading = ref(false)
const errorKey = ref<string | null>(null)

const selectedTeamId = computed(() => store.selectedTeamId)

async function load(teamId: string) {
  loading.value = true
  errorKey.value = null
  try {
    const res = await getTeamDashboard(teamId)
    data.value = res.data
  } catch (e) {
    // 握り潰さない。i18n キーを保持して UI に表示する。
    console.error('[DashboardTeamPanel] getTeamDashboard failed', e)
    errorKey.value = 'swipeWidgets.actionRequired.loadError'
    data.value = null
  } finally {
    loading.value = false
  }
}

watch(
  selectedTeamId,
  (id) => {
    if (id !== null) load(id)
    else data.value = null
  },
  { immediate: true },
)
</script>

<template>
  <div class="flex flex-col gap-4">
    <ScopeSearchForm scope-type="TEAM" />
    <ScopeTabBar scope-type="TEAM" />

    <PageLoading v-if="loading" />

    <Message v-else-if="errorKey" severity="error" :closable="false">
      {{ $t(errorKey) }}
    </Message>

    <DashboardEmptyState
      v-else-if="selectedTeamId === null"
      icon="pi pi-users"
      :message="$t('scopeDashboard.tagBar.empty')"
    />

    <DashboardSwipeWidgetGrid
      v-else-if="data"
      scope-type="TEAM"
      :scope-id="selectedTeamId"
      :data="data"
    />
  </div>
</template>
