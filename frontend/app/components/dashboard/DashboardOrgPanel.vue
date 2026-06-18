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

/**
 * 選択中 ID が「まだ slug へ移行されていない内部 BIGINT」かどうかを判定する。
 *
 * <p>slug 移行（PR #1413〜）以降、ダッシュボード API の pathVariable は slug（例 `team-000017`）。
 * ただし localStorage 復元直後など、store.loadTabs が走る前は selectedOrgId に旧来の
 * 内部 BIGINT（scopeId）が残っていることがある。BIGINT を pathVariable に渡すと 400 になるため、
 * loadTabs が slug へ張り替えるまでスピナーで待機する必要がある。</p>
 *
 * <p>判定方式: ロード済みタグ一覧の scopeId（内部 BIGINT）に一致する値は「移行前」とみなす。
 * 純数値 slug（組織名が "12345" 等）でも scopeId と値が異なるため誤判定しない。
 * UUID 正規表現に依存しないため、人間可読 slug（team-000017 等）が永久スピナーになる不具合を根治する。</p>
 */
function isUnmigratedScopeId(id: string): boolean {
  const items = store.tabPages.ORGANIZATION?.items
  if (!items) {
    // タグ未ロード（loadTabs 前）。BIGINT 文字列なら移行待ち、slug なら即ロード。
    return /^\d+$/.test(id)
  }
  return items.some(item => item.scopeId === id && item.slug !== id)
}

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
    } else if (isUnmigratedScopeId(id)) {
      // 内部 BIGINT（移行前）: onMounted の loadTabs が slug に張り替えるまでスピナーを
      // 表示し、空白状態（loading=false/data=null/errorKey=null かつ非null id）を防ぐ。
      loading.value = true
    } else {
      // slug（team-000017 等）: そのまま表示。404 時は errorKey で顕在化させ握り潰さない。
      load(id)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="flex flex-col gap-4">
    <ScopeSearchForm scope-type="ORGANIZATION" />

    <!-- タグ行右端に管理者レンズトグル（ADMIN/DEPUTY のみ描画・§1.2/§1.3）。 -->
    <div class="flex items-center justify-between gap-2">
      <ScopeTabBar scope-type="ORGANIZATION" class="min-w-0 flex-1" />
      <DashboardScopeLensToggle
        v-if="selectedOrgId"
        scope-type="ORGANIZATION"
        :slug="selectedOrgId"
      />
    </div>

    <PageLoading v-if="loading" />

    <Message v-else-if="errorKey" severity="error" :closable="false">
      {{ $t(errorKey) }}
    </Message>

    <DashboardEmptyState
      v-else-if="selectedOrgId === null"
      icon="pi pi-building"
      :message="$t('scopeDashboard.tagBar.empty')"
    />

    <!-- 管理者レンズ ON: 管理者グリッドへシート差替（§1.2）。 -->
    <DashboardAdminWidgetGrid
      v-else-if="data && store.isAdminLensOn('ORGANIZATION', selectedOrgId)"
      scope-type="ORGANIZATION"
      :slug="selectedOrgId"
    />

    <!-- 既定: メンバー向け厳選 8 ウィジェット（F22.1 既存挙動・差替前）。 -->
    <DashboardSwipeWidgetGrid
      v-else-if="data"
      scope-type="ORGANIZATION"
      :scope-id="selectedOrgId"
      :data="data"
    />
  </div>
</template>
