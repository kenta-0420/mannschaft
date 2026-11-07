<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: ['auth', 'admin-console'] })
const route = useRoute()
const slug = computed(() => String(route.params.slug))
</script>

<template>
  <AdminSettingsHub scope-type="organization" :slug="slug" />
</template>
