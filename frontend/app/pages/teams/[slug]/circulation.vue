<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

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
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="回覧板" />
    </div>
    <CirculationList scope-type="TEAM" :scope-id="teamSlug" :can-manage="isAdminOrDeputy" />
  </div>
</template>
