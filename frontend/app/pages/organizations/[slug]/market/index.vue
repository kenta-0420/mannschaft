<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))
</script>

<template>
  <ScopeMarketHub scope-type="organization" :scope-id="orgSlug" />
</template>
