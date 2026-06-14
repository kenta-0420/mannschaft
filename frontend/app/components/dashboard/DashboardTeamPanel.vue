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

/**
 * 選択中 ID が「まだ slug へ移行されていない内部 BIGINT」かどうかを判定する。
 *
 * <p>slug 移行（PR #1413〜）以降、ダッシュボード API の pathVariable は slug（例 `fc-u-18`）。
 * ただし localStorage 復元直後など、store.loadTabs が走る前は selectedTeamId に旧来の
 * 内部 BIGINT（scopeId）が残っていることがある。BIGINT を pathVariable に渡すと 400 になるため、
 * loadTabs が slug へ張り替えるまでスピナーで待機する必要がある。</p>
 *
 * <p>判定方式: ロード済みタグ一覧の scopeId（内部 BIGINT）に一致する値は「移行前」とみなす。
 * 純数値 slug（チーム名が "12345" 等）でも scopeId と値が異なるため誤判定しない。
 * UUID 正規表現に依存しないため、人間可読 slug（fc-u-18 等）が永久スピナーになる不具合を根治する。</p>
 */
function isUnmigratedScopeId(id: string): boolean {
  const items = store.tabPages.TEAM?.items
  if (!items) {
    // タグ未ロード（loadTabs 前）。BIGINT 文字列なら移行待ち、slug なら即ロード。
    return /^\d+$/.test(id)
  }
  return items.some(item => item.scopeId === id && item.slug !== id)
}

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
      loading.value = false
    } else if (isUnmigratedScopeId(id)) {
      // 内部 BIGINT（移行前）: DashboardScopeCarousel.onMounted が loadTabs で slug に
      // 張り替えるまでスピナーを表示し、空白状態（loading=false/data=null/errorKey=null
      // かつ非null id）を防ぐ。
      loading.value = true
    } else {
      // slug（fc-u-18 等）: そのまま表示。404 時は errorKey で顕在化させ握り潰さない。
      load(id)
    }
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
