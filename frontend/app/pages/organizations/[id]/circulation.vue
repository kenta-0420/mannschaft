<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">回覧板</h1></div>
    <CirculationList scope-type="ORGANIZATION" :scope-id="orgId" :can-manage="isAdminOrDeputy" />
  </div>
</template>
