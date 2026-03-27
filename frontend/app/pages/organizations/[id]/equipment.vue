<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)
onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <EquipmentList scope-type="organization" :scope-id="orgId" :can-manage="isAdminOrDeputy" />
  </div>
</template>
