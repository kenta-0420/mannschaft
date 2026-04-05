<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = Number(route.params.id)
const { loadPermissions } = useRoleAccess('team', teamId)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSelect(requestId: number) {
  navigateTo(`/teams/${teamId}/workflows/${requestId}`)
}

function onSaved() {
  listRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">ワークフロー申請</h1>
      <div class="flex gap-2">
        <NuxtLink :to="`/teams/${teamId}/workflows/templates`">
          <Button label="テンプレート管理" icon="pi pi-cog" outlined />
        </NuxtLink>
        <Button label="新規申請" icon="pi pi-plus" @click="showCreateDialog = true" />
      </div>
    </div>

    <WorkflowRequestList ref="listRef" scope-type="team" :scope-id="teamId" @select="onSelect" />

    <WorkflowRequestForm
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="teamId"
      @saved="onSaved"
    />
  </div>
</template>
