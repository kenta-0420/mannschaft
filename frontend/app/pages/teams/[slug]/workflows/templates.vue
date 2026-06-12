<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

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
      <BackButton :to="`/teams/${teamSlug}/workflows`" label="申請一覧に戻る" />
    </div>

    <WorkflowTemplateList scope-type="team" :scope-id="teamSlug" :can-edit="isAdminOrDeputy" />
  </div>
</template>
