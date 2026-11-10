<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const slug = computed(() => String(route.params.slug))
</script>

<template>
  <VotingSessionList scope-type="TEAM" :slug="slug" />
</template>