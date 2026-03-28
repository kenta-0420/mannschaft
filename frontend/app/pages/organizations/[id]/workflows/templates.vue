<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <NuxtLink
        :to="`/organizations/${orgId}/workflows`"
        class="text-sm text-primary hover:underline"
      >
        <i class="pi pi-arrow-left mr-1" />申請一覧に戻る
      </NuxtLink>
    </div>

    <WorkflowTemplateList scope-type="organization" :scope-id="orgId" :can-edit="isAdminOrDeputy" />
  </div>
</template>
