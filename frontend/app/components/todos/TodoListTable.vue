<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  canEdit: boolean
  canDelete: boolean
}>()

const emit = defineEmits<{
  edit: [todoId: number]
  refresh: []
}>()

const todoApi = useTodoApi()
const notification = useNotification()

interface Todo {
  id: number
  title: string
  status: string
  priority: string
  dueDate: string | null
  daysRemaining: number | null
  assignees: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
  createdBy: { id: number; displayName: string }
  createdAt: string
}

const todos = ref<Todo[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const rows = ref(20)
const selectedTodos = ref<Todo[]>([])

// フィルター
const statusFilter = ref('')
const priorityFilter = ref('')

const statusOptions = [
  { label: '全て', value: '' },
  { label: '未着手', value: 'OPEN' },
  { label: '進行中', value: 'IN_PROGRESS' },
  { label: '完了', value: 'COMPLETED' },
]

const priorityOptions = [
  { label: '全て', value: '' },
  { label: '低', value: 'LOW' },
  { label: '中', value: 'MEDIUM' },
  { label: '高', value: 'HIGH' },
  { label: '緊急', value: 'URGENT' },
]

async function loadTodos() {
  loading.value = true
  try {
    const res = await todoApi.listTodos(props.scopeType, props.scopeId, {
      status: statusFilter.value || undefined,
      priority: priorityFilter.value || undefined,
      page: page.value,
      size: rows.value,
    })
    todos.value = res.data
    totalRecords.value = res.meta.totalElements
  }
  catch { todos.value = [] }
  finally { loading.value = false }
}

async function onStatusChange(todoId: number, newStatus: string) {
  try {
    await todoApi.changeTodoStatus(props.scopeType, props.scopeId, todoId, newStatus)
    notification.success('ステータスを変更しました')
    await loadTodos()
  }
  catch { notification.error('ステータス変更に失敗しました') }
}

async function onBulkStatusChange(status: string) {
  const ids = selectedTodos.value.map(t => t.id)
  if (ids.length === 0) return
  try {
    await todoApi.bulkChangeTodoStatus(props.scopeType, props.scopeId, ids, status)
    notification.success(`${ids.length}件のステータスを変更しました`)
    selectedTodos.value = []
    await loadTodos()
  }
  catch { notification.error('一括変更に失敗しました') }
}

async function onDelete(todoId: number) {
  if (!confirm('このTODOを削除しますか？')) return
  try {
    await todoApi.deleteTodo(props.scopeType, props.scopeId, todoId)
    notification.success('TODOを削除しました')
    await loadTodos()
    emit('refresh')
  }
  catch { notification.error('削除に失敗しました') }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadTodos()
}

function isOverdue(todo: Todo): boolean {
  return todo.daysRemaining !== null && todo.daysRemaining < 0 && todo.status !== 'COMPLETED'
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

watch([statusFilter, priorityFilter], () => {
  page.value = 0
  loadTodos()
})

onMounted(loadTodos)

defineExpose({ refresh: loadTodos, changeStatus: onStatusChange })
</script>

<template>
  <div>
    <!-- フィルター -->
    <div class="mb-4 flex flex-wrap items-end gap-3">
      <div class="w-36">
        <label class="mb-1 block text-xs font-medium">ステータス</label>
        <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" class="w-full" />
      </div>
      <div class="w-36">
        <label class="mb-1 block text-xs font-medium">優先度</label>
        <Select v-model="priorityFilter" :options="priorityOptions" option-label="label" option-value="value" class="w-full" />
      </div>
      <!-- 一括操作 -->
      <div v-if="selectedTodos.length > 0" class="flex items-center gap-2">
        <span class="text-sm text-surface-500">{{ selectedTodos.length }}件選択中</span>
        <Button label="完了にする" size="small" severity="success" @click="onBulkStatusChange('COMPLETED')" />
        <Button label="進行中にする" size="small" severity="info" @click="onBulkStatusChange('IN_PROGRESS')" />
      </div>
    </div>

    <!-- テーブル -->
    <DataTable
      v-model:selection="selectedTodos"
      :value="todos"
      :loading="loading"
      lazy
      paginator
      :rows="rows"
      :total-records="totalRecords"
      :rows-per-page-options="[10, 20, 50]"
      data-key="id"
      row-hover
      @page="onPage"
    >
      <Column selection-mode="multiple" header-style="width: 3rem" />
      <Column header="タイトル" field="title" style="min-width: 200px">
        <template #body="{ data }">
          <div>
            <NuxtLink
              :to="`/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.scopeId}/todos/${data.id}`"
              class="font-medium hover:text-primary"
            >
              {{ data.title }}
            </NuxtLink>
            <div v-if="data.assignees.length > 0" class="mt-1 flex -space-x-1">
              <Avatar
                v-for="a in data.assignees.slice(0, 3)"
                :key="a.userId"
                :image="a.avatarUrl"
                :label="a.avatarUrl ? undefined : a.displayName.charAt(0)"
                shape="circle"
                size="small"
                class="border-2 border-surface-0 dark:border-surface-800"
              />
              <span v-if="data.assignees.length > 3" class="flex h-6 w-6 items-center justify-center rounded-full bg-surface-200 text-xs dark:bg-surface-600">
                +{{ data.assignees.length - 3 }}
              </span>
            </div>
          </div>
        </template>
      </Column>
      <Column header="ステータス" field="status" style="width: 120px">
        <template #body="{ data }">
          <TodoStatusBadge :status="data.status" />
        </template>
      </Column>
      <Column header="優先度" field="priority" style="width: 100px">
        <template #body="{ data }">
          <TodoPriorityBadge :priority="data.priority" />
        </template>
      </Column>
      <Column header="期限" field="dueDate" style="width: 120px">
        <template #body="{ data }">
          <span :class="{ 'font-semibold text-red-500': isOverdue(data) }">
            {{ formatDate(data.dueDate) }}
          </span>
        </template>
      </Column>
      <Column v-if="canEdit || canDelete" header="操作" style="width: 100px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button v-if="canEdit" icon="pi pi-pencil" text rounded size="small" @click="emit('edit', data.id)" />
            <Button v-if="canDelete" icon="pi pi-trash" text rounded size="small" severity="danger" @click="onDelete(data.id)" />
          </div>
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-check-circle" message="TODOはありません" />
      </template>
    </DataTable>
  </div>
</template>
