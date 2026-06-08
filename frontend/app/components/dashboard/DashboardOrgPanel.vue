<script setup lang="ts">
/**
 * F22.1 組織パネル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.7
 * - タグ行（組織）+ 検索フォーム（組織）+ 厳選 8 ウィジェット。
 * - store.selectedOrgId のダッシュボードを getOrganizationDashboard で取得し表示。
 */
import type { OrgDashboardResponse } from '~/types/dashboard-scope'

/** UUID v4/v7 形式判定。BIGINT 文字列（"1" 等）を除外して 400 を防ぐ。 */
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

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
// ※ DashboardScopeCarousel.onMounted で TEAM/ORGANIZATION 両方をロードするため、
//   このガードは補助的な役割（Carousel 経由以外でマウントされた場合に備える）。
onMounted(async () => {
  if (!store.tabPages.ORGANIZATION) {
    await store.loadTabs('ORGANIZATION', store.orgTabPage)
  }
})

watch(
  selectedOrgId,
  (id) => {
    if (id === null) {
      data.value = null
      loading.value = false
    } else if (UUID_REGEX.test(id)) {
      load(id)
    } else {
      // BIGINT 形式: onMounted の loadTabs が UUID に移行するまでスピナーを表示し
      // 空白状態（loading=false/data=null/errorKey=null かつ非null id）を防ぐ
      loading.value = true
    }
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
