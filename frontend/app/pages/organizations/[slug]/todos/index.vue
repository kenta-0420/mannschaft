<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'organization',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, isMember, loadPermissions } = useRoleAccess('organization', orgSlug)

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
      <Button v-if="isMember" label="TODO作成" icon="pi pi-plus" @click="showCreateDialog = true" />
    </div>

    <TodoListTable
      ref="listRef"
      scope-type="organization"
      :scope-id="orgSlug"
      :can-edit="isAdminOrDeputy"
      :can-delete="isAdmin"
      @edit="onEdit"
    />

    <TodoForm
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgSlug"
      @saved="onSaved"
    />

    <TodoForm
      v-model:visible="showEditDialog"
      scope-type="organization"
      :scope-id="orgSlug"
      :todo-id="editTodoId"
      @saved="onSaved"
    />
  </div>
</template>
