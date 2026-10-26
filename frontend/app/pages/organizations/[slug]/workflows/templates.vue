<script setup lang="ts">
definePageMeta({
  layout: 'organization',
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

const loading = ref(true)

onMounted(async () => {
  try {
    await loadPermissions()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4">
      <BackButton :to="`/organizations/${orgSlug}/workflows`" label="申請一覧に戻る" />
    </div>

    <WorkflowTemplateList scope-type="organization" :scope-id="orgSlug" :can-edit="isAdminOrDeputy" />
  </div>
</template>
