<script setup lang="ts">
import AdminSettingsHub from '~/components/admin/AdminSettingsHub.vue'

definePageMeta({ layout: 'team', middleware: ['auth', 'admin-console'] })

const route = useRoute()
// slugごとの実体を分け、旧団体の遅延応答を新団体の権限・同期結果に使わない。
const slug = computed(() => String(route.params.slug))
</script>

<template>
  <AdminSettingsHub
    :key="slug"
    scope-type="team"
    :slug="slug"
  />
</template>
