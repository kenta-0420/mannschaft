<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細親ページ
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1
 *
 * 役割:
 *   - `/villages/{id}` 直アクセス時のデフォルトタブへのリダイレクト
 *   - デフォルト = 掲示板タブ (`/villages/{id}/bulletin`)
 *
 * 認証必須。
 */

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const villageId = String(route.params.id)

// 即時にデフォルトタブ（掲示板）へリダイレクト
// replace: true で履歴を汚さない
await navigateTo(`/villages/${villageId}/bulletin`, { replace: true })
</script>

<template>
  <!-- リダイレクト中の一瞬の表示用フォールバック -->
  <PageLoading />
</template>
