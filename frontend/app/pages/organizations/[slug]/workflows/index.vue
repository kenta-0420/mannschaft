<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const { loadPermissions } = useRoleAccess('organization', orgSlug)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSelect(requestId: number) {
  navigateTo(`/organizations/${orgSlug}/workflows/${requestId}`)
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
        <NuxtLink :to="`/organizations/${orgSlug}/workflows/templates`">
          <Button label="テンプレート管理" icon="pi pi-cog" outlined />
        </NuxtLink>
        <Button label="新規申請" icon="pi pi-plus" @click="showCreateDialog = true" />
      </div>
    </div>

    <WorkflowRequestList
      ref="listRef"
      scope-type="organization"
      :scope-id="orgSlug"
      @select="onSelect"
    />

    <WorkflowRequestForm
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgSlug"
      @saved="onSaved"
    />
  </div>
</template>
