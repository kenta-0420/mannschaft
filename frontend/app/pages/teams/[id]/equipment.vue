<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)
onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <EquipmentList scope-type="team" :scope-id="teamId" :can-manage="isAdminOrDeputy" />
  </div>
</template>
