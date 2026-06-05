<script setup lang="ts">
definePageMeta({
  layout: 'organization',
  middleware: 'auth',
})

const route = useRoute()
const orgId = String(route.params.id)
const requestId = Number(route.params.requestId)
</script>

<template>
  <div>
    <div class="mb-4">
      <BackButton :to="`/organizations/${orgId}/workflows`" label="申請一覧に戻る" />
    </div>

    <WorkflowRequestDetail scope-type="organization" :scope-id="orgId" :request-id="requestId" />
  </div>
</template>
