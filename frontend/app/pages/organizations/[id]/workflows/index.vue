<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const { loadPermissions } = useRoleAccess('organization', orgId)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSelect(requestId: number) {
  navigateTo(`/organizations/${orgId}/workflows/${requestId}`)
}

function onSaved() {
  listRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="ワークフロー申請" />
      <div class="flex gap-2">
        <NuxtLink :to="`/organizations/${orgId}/workflows/templates`">
          <Button label="テンプレート管理" icon="pi pi-cog" outlined />
        </NuxtLink>
        <Button label="新規申請" icon="pi pi-plus" @click="showCreateDialog = true" />
      </div>
    </div>

    <WorkflowRequestList
      ref="listRef"
      scope-type="organization"
      :scope-id="orgId"
      @select="onSelect"
    />

    <WorkflowRequestForm
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgId"
      @saved="onSaved"
    />
  </div>
</template>
