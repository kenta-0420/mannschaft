<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

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
    <EquipmentList scope-type="team" :scope-id="teamId" :can-manage="isAdminOrDeputy" />
  </div>
</template>
