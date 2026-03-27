<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">マッチング</h1></div>
    <MatchRequestList :team-id="teamId" />
  </div>
</template>
