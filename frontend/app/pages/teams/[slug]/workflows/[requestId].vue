<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const requestId = Number(route.params.requestId)
</script>

<template>
  <div>
    <div class="mb-4">
      <BackButton :to="`/teams/${teamSlug}/workflows`" label="申請一覧に戻る" />
    </div>

    <WorkflowRequestDetail scope-type="team" :scope-id="teamSlug" :request-id="requestId" />
  </div>
</template>
