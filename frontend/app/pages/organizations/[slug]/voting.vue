<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const slug = computed(() => String(route.params.slug))
</script>

<template>
  <VotingSessionList scope-type="ORGANIZATION" :slug="slug" />
</template>