<script setup lang="ts">
import AdminSettingsHub from '~/components/admin/AdminSettingsHub.vue'
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({ layout: 'team', middleware: ['auth', 'admin-console'] })

const route = useRoute()
const slug = computed(() => String(route.params.slug))
const { team } = useTeamShellContext()
</script>

<template>
  <!-- slug ごとの実体を分け、旧団体の遅延応答を新団体の権限・同期結果に使わない。 -->
  <AdminSettingsHub
    :key="slug"
    scope-type="team"
    :slug="slug"
    :resolved-slug="team?.slug"
    :numeric-id="team?.numericId"
  />
</template>
