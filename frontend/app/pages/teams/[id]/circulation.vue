<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">回覧板</h1></div>
    <CirculationList scope-type="TEAM" :scope-id="teamId" :can-manage="isAdminOrDeputy" />
  </div>
</template>
