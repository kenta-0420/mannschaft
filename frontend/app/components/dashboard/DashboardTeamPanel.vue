<script setup lang="ts">
/**
 * F22.1 チームパネル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.7
 * - タグ行（チーム）+ 検索フォーム（チーム）+ 厳選 8 ウィジェット。
 * - store.selectedTeamId のダッシュボードを getTeamDashboard で取得し表示。
 */
import type { TeamDashboardResponse } from '~/types/dashboard-scope'

/** UUID v4/v7 形式判定。BIGINT 文字列（"92" 等）を除外して 400 を防ぐ。 */
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

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
    if (id === null) {
      data.value = null
    } else if (UUID_REGEX.test(id)) {
      load(id)
    }
    // UUID 形式でない（旧 BIGINT）場合は loadTabs 完了後に UUID に更新されるため待機
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
