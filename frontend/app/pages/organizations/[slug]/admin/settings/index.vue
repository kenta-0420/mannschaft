<script setup lang="ts">
import AdminSettingsHub from '~/components/admin/AdminSettingsHub.vue'
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({ layout: 'organization', middleware: ['auth', 'admin-console'] })

const route = useRoute()
const slug = computed(() => String(route.params.slug))
const { org } = useOrgShellContext()
</script>

<template>
  <!-- OrgDetail.id は URL の slug。内部 ID と取り違えず、両方を確定してから同期する。 -->
  <AdminSettingsHub
    :key="slug"
    scope-type="organization"
    :slug="slug"
    :resolved-slug="org?.id"
    :numeric-id="org?.numericId"
  />
</template>
