<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <NuxtLink :to="`/teams/${teamId}/workflows`" class="text-sm text-primary hover:underline">
        <i class="pi pi-arrow-left mr-1" />申請一覧に戻る
      </NuxtLink>
    </div>

    <WorkflowTemplateList scope-type="team" :scope-id="teamId" :can-edit="isAdminOrDeputy" />
  </div>
</template>
