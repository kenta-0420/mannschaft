<script setup lang="ts">
import AdminSettingsHub from '~/components/admin/AdminSettingsHub.vue'

definePageMeta({ layout: 'organization', middleware: ['auth', 'admin-console'] })

const route = useRoute()
const slug = computed(() => String(route.params.slug))
</script>

<template>
  <!-- URLのslugは本人所属と照合し、内部IDを既存resolverで確定する。 -->
  <AdminSettingsHub
    :key="slug"
    scope-type="organization"
    :slug="slug"
  />
</template>
