<script setup lang="ts">
/**
 * F22.1 組織パネル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.7
 * - タグ行（組織）+ 検索フォーム（組織）+ 厳選 8 ウィジェット。
 * - store.selectedOrgId のダッシュボードを getOrganizationDashboard で取得し表示。
 */
import type { OrgDashboardResponse } from '~/types/dashboard-scope'

const store = useScopeDashboardStore()
const { getOrganizationDashboard } = useDashboardApi()

const data = ref<OrgDashboardResponse | null>(null)
const loading = ref(false)
const errorKey = ref<string | null>(null)

const selectedOrgId = computed(() => store.selectedOrgId)

async function load(orgId: string) {
  loading.value = true
  errorKey.value = null
  try {
    const res = await getOrganizationDashboard(orgId)
    data.value = res.data
  } catch (e) {
    // 握り潰さない。i18n キーを保持して UI に表示する。
    console.error('[DashboardOrgPanel] getOrganizationDashboard failed', e)
    errorKey.value = 'swipeWidgets.actionRequired.loadError'
    data.value = null
  } finally {
    loading.value = false
  }
}

// 組織パネルが初めてアクティブになるまで組織タグは未ロードのことがあるため、
// 選択中組織が未確定なら ORGANIZATION タグをロードする。
onMounted(async () => {
  if (!store.tabPages.ORGANIZATION) {
    await store.loadTabs('ORGANIZATION', store.orgTabPage)
  }
})

watch(
  selectedOrgId,
  (id) => {
    if (id !== null) load(id)
    else data.value = null
  },
  { immediate: true },
)
</script>

<template>
  <div class="flex flex-col gap-4">
    <ScopeSearchForm scope-type="ORGANIZATION" />
    <ScopeTabBar scope-type="ORGANIZATION" />

    <PageLoading v-if="loading" />

    <Message v-else-if="errorKey" severity="error" :closable="false">
      {{ $t(errorKey) }}
    </Message>

    <DashboardEmptyState
      v-else-if="selectedOrgId === null"
      icon="pi pi-building"
      :message="$t('scopeDashboard.tagBar.empty')"
    />

    <DashboardSwipeWidgetGrid
      v-else-if="data"
      scope-type="ORGANIZATION"
      :scope-id="selectedOrgId"
      :data="data"
    />
  </div>
</template>
