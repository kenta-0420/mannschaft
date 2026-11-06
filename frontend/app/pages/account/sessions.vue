<script setup lang="ts">
/** 保存済みの新規デバイス通知が持つ旧 URL を、正規のセキュリティ設定へ転送する。 */
definePageMeta({
  // setup 中の再遷移で NuxtPage の描画が止まるため、認証後・ページ解決前に転送する。
  middleware: [
    'auth',
    () => {
      // auth と同じく認証復元を待つ。SSR の HTTP 転送では元 URL の hash が継承される。
      if (import.meta.server) return
      return navigateTo('/settings/security', { replace: true })
    },
  ],
})
</script>

<template>
  <div />
</template>
