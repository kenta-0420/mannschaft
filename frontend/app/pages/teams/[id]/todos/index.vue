<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

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
      scope-type="team"
      :scope-id="teamId"
      :can-edit="isAdminOrDeputy"
      :can-delete="isAdmin"
      @edit="onEdit"
    />

    <!-- 作成ダイアログ -->
    <TodoForm
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="teamId"
      @saved="onSaved"
    />

    <!-- 編集ダイアログ -->
    <TodoForm
      v-model:visible="showEditDialog"
      scope-type="team"
      :scope-id="teamId"
      :todo-id="editTodoId"
      @saved="onSaved"
    />
  </div>
</template>
