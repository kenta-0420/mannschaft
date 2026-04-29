<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
</script>

<template>
  <div class="p-4">
    <h1 class="mb-4 text-xl font-bold">活動統計</h1>
    <ActivityStatsPanel scope-type="TEAM" :scope-id="teamId" />
  </div>
</template>
