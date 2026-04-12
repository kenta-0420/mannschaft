<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><PageHeader title="マッチング" /></div>
    <MatchRequestList :team-id="teamId" />
  </div>
</template>
