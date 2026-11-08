<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: ['auth', 'admin-console'] })
const route = useRoute()
const slug = computed(() => String(route.params.slug))
</script>

<template>
  <AdminSettingsHub scope-type="team" :slug="slug" />
</template>
