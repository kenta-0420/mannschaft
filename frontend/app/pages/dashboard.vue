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
</script>

<template>
  <DashboardScopeCarousel />
</template>
