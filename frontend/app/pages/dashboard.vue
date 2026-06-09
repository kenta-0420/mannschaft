<script setup lang="ts">
/**
 * F22.1 ダッシュボードシェル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.7
 * - ルートは /dashboard のまま（URL 変更なし）。
 * - 横スワイプカルーセルをマウントし、個人パネルスロットに従来の個人ダッシュボードを移設。
 *   従来の中身は DashboardPersonalPanel.vue（個人パネル）へ移動済み。
 */
definePageMeta({
  middleware: 'auth',
})

const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

onMounted(async () => {
  // teams/index.vue や organizations/index.vue を経由せずに直接 /dashboard に来た場合も
  // ウィジェットが正しく表示されるようにストアを初期化する
  await Promise.allSettled([
    teamStore.myTeams.length === 0 ? teamStore.fetchMyTeams() : Promise.resolve(),
    orgStore.myOrganizations.length === 0 ? orgStore.fetchMyOrganizations() : Promise.resolve(),
  ])
})
</script>

<template>
  <DashboardScopeCarousel />
</template>
