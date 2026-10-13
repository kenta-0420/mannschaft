<script setup lang="ts">
/**
 * 旧 URL `/teams/{slug}/friends` の救済リダイレクト。
 * フレンドチームの実体は「フレンドチーム」タブ（`nav.vue`）に統合済み。
 * 使い方・フォロー・SUPPORTER 注意バナーはすべて nav.vue 側にある。
 */
definePageMeta({ middleware: 'auth' })

const route = useRoute()
await navigateTo(`/teams/${String(route.params.slug)}/nav`, { replace: true })
</script>

<template>
  <div />
</template>
