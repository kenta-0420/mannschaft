<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

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
    <CirculationList scope-type="ORGANIZATION" :scope-id="orgId" :can-manage="isAdminOrDeputy" />
  </div>
</template>
