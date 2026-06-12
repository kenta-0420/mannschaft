<script setup lang="ts">
definePageMeta({
  layout: 'organization',
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const requestId = Number(route.params.requestId)
</script>

<template>
  <div>
    <div class="mb-4">
      <BackButton :to="`/organizations/${orgSlug}/workflows`" label="申請一覧に戻る" />
    </div>

    <WorkflowRequestDetail scope-type="organization" :scope-id="orgSlug" :request-id="requestId" />
  </div>
</template>
