<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const showCreateDialog = ref(false)
const editTodoId = ref<number | undefined>(undefined)
const showEditDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onEdit(todoId: number) {
  editTodoId.value = todoId
  showEditDialog.value = true
}

function onSaved() {
  listRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <BackButton />
        <PageHeader title="TODO" />
      </div>
      <Button label="TODO作成" icon="pi pi-plus" @click="showCreateDialog = true" />
    </div>

    <TodoListTable
      ref="listRef"
      scope-type="organization"
      :scope-id="orgId"
      :can-edit="isAdminOrDeputy"
      :can-delete="isAdmin"
      @edit="onEdit"
    />

    <TodoForm
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgId"
      @saved="onSaved"
    />

    <TodoForm
      v-model:visible="showEditDialog"
      scope-type="organization"
      :scope-id="orgId"
      :todo-id="editTodoId"
      @saved="onSaved"
    />
  </div>
</template>
