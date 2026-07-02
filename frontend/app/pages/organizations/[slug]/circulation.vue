<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

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
      <PageHeader title="回覧板" />
    </div>
    <CirculationList scope-type="ORGANIZATION" :scope-id="orgSlug" :can-manage="isAdminOrDeputy" />
  </div>
</template>
