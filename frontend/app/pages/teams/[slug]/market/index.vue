<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
</script>

<template>
  <ScopeMarketHub scope-type="team" :scope-id="teamSlug" />
</template>
