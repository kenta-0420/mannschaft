<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">アンケート・投票</h1></div>
    <SurveyList scope-type="TEAM" :scope-id="teamId" />
  </div>
</template>
