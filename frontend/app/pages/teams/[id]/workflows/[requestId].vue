<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = Number(route.params.id)
const requestId = Number(route.params.requestId)
</script>

<template>
  <div>
    <div class="mb-4">
      <BackButton :to="`/teams/${teamId}/workflows`" label="申請一覧に戻る" />
    </div>

    <WorkflowRequestDetail scope-type="team" :scope-id="teamId" :request-id="requestId" />
  </div>
</template>
