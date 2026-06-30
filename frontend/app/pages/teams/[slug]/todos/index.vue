<script setup lang="ts">
definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, isMember, loadPermissions } = useRoleAccess('team', teamSlug)

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
      <PageHeader title="TODO" />
      <Button v-if="isMember" label="TODO作成" icon="pi pi-plus" data-testid="team-todo-create" @click="showCreateDialog = true" />
    </div>

    <TodoListTable
      ref="listRef"
      scope-type="team"
      :scope-id="teamSlug"
      :can-edit="isAdminOrDeputy"
      :can-delete="isAdmin"
      @edit="onEdit"
    />

    <!-- 作成ダイアログ -->
    <TodoForm
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="teamSlug"
      @saved="onSaved"
    />

    <!-- 編集ダイアログ -->
    <TodoForm
      v-model:visible="showEditDialog"
      scope-type="team"
      :scope-id="teamSlug"
      :todo-id="editTodoId"
      @saved="onSaved"
    />
  </div>
</template>
