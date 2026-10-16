<script setup lang="ts">
/** 保存済みの新規デバイス通知が持つ旧 URL を、正規のセキュリティ設定へ転送する。 */
definePageMeta({ middleware: 'auth' })

await navigateTo('/settings/security', { replace: true })
</script>

<template>
  <div />
</template>
