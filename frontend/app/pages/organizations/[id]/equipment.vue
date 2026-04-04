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
    <EquipmentList scope-type="organization" :scope-id="orgId" :can-manage="isAdminOrDeputy" />
  </div>
</template>
