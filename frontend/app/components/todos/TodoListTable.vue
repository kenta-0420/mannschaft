<script setup lang="ts">
import type { TodoStatusLabelInfo } from '~/types/todoStatusLabel'
import dayjs from 'dayjs'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  canEdit: boolean
  canDelete: boolean
}>()

const emit = defineEmits<{
  edit: [todoId: number]
  refresh: []
}>()

const todoApi = useTodoApi()
const notification = useNotification()
const { showUndoToast } = useUndoToast()
const { t } = useI18n()
const { userTimezone } = useDatetime()

/** Wave 1 DTO刷新: ネスト構造 */
interface Todo {
  id: number
  content?: {
    title?: string
    description?: string | null
    startDate?: string | null
    progressRate?: number | null
  }
  schedule?: {
    dueDate?: string | null
    dueTime?: string | null
    daysRemaining?: number | null
  }
  audit?: {
    createdAt?: string
    createdBy?: { id: number; displayName: string }
  }
  /** @deprecated 旧フラットフィールド互換 — ステータスバケット */
  status?: string
  /** @deprecated 旧フラットフィールド互換 */
  priority?: string
  /** @deprecated 旧フラットフィールド互換 */
  statusLabel?: TodoStatusLabelInfo | null
  /** @deprecated 旧フラットフィールド互換 */
  daysRemaining?: number | null
  assignees: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
}

const todos = ref<Todo[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const rows = ref(20)
const selectedTodos = ref<Todo[]>([])

// ソート種別（RECENT=新着順 / PRIORITY=優先度順）
const sortType = ref<'RECENT' | 'PRIORITY'>('RECENT')

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
      sort: sortType.value,
    })
    todos.value = res.data
    totalRecords.value = res.meta.totalElements
  }
  catch { todos.value = [] }
  finally { loading.value = false }
}

async function onStatusChange(
  todoId: number,
  payload: string | { status?: string; statusLabelId?: number },
) {
  try {
    const body = typeof payload === 'string' ? { status: payload } : payload
    await todoApi.changeTodoStatus(props.scopeType, props.scopeId, todoId, body)
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

// ADHD 配慮 AC-16: 確認ダイアログを廃止し、即時削除 + Undo Toast に置換する。
// TODO は論理削除（soft delete）なので、Undo で restore EP を叩けば一覧に復活する。
async function onDelete(todoId: number) {
  try {
    await todoApi.deleteTodo(props.scopeType, props.scopeId, todoId)
    await loadTodos()
    emit('refresh')
    showUndoToast({
      summary: t('todo.list.deletedToast'),
      undoLabel: t('button.undo'),
      severity: 'info',
      onUndo: async () => {
        try {
          await todoApi.restoreTodo(props.scopeType, props.scopeId, todoId)
          notification.success(t('todo.list.restoredToast'))
          await loadTodos()
          emit('refresh')
        }
        catch { notification.error(t('todo.list.restoreFailed')) }
      },
    })
  }
  catch { notification.error(t('todo.list.deleteFailed')) }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadTodos()
}

function isOverdue(todo: Todo): boolean {
  return (
    todo.schedule?.daysRemaining !== null &&
    (todo.schedule?.daysRemaining ?? 0) < 0 &&
    todo.status !== 'COMPLETED'
  )
}

function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return '—'
  return dayjs.tz(dateStr, userTimezone.value).format('YYYY/MM/DD')
}

watch([statusFilter, priorityFilter, sortType], () => {
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
        <label class="mb-1 block text-xs font-medium">{{ $t('todo.field.status') }}</label>
        <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" class="w-full" />
      </div>
      <div class="w-36">
        <label class="mb-1 block text-xs font-medium">{{ $t('todo.field.priority') }}</label>
        <Select v-model="priorityFilter" :options="priorityOptions" option-label="label" option-value="value" class="w-full" />
      </div>
      <!-- 並び順トグル -->
      <div>
        <label class="mb-1 block text-xs font-medium">{{ $t('todo.list.sortLabel') }}</label>
        <SelectButton
          v-model="sortType"
          :options="[
            { label: $t('todo.list.sortRecent'), value: 'RECENT' },
            { label: $t('todo.list.sortPriority'), value: 'PRIORITY' },
          ]"
          option-label="label"
          option-value="value"
          data-testid="todo-sort-toggle"
        />
      </div>
      <!-- 一括操作（バケット単位の一括変更のみ。ラベル変更は詳細ページで行う） -->
      <div v-if="selectedTodos.length > 0" class="flex items-center gap-2">
        <span class="text-sm text-surface-500">{{ selectedTodos.length }}件選択中</span>
        <Button label="完了にする" size="small" severity="success" @click="onBulkStatusChange('COMPLETED')" />
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
      <Column header="タイトル" field="content.title" style="min-width: 200px">
        <template #body="{ data }">
          <div :data-testid="`team-todo-row-${data.id}`">
            <NuxtLink
              :to="`/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.scopeId}/todos/${data.id}`"
              class="font-medium hover:text-primary"
              :data-testid="`team-todo-title-${data.id}`"
            >
              {{ data.content?.title }}
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
      <Column header="ステータス" field="status" style="width: 140px">
        <template #body="{ data }">
          <TodoStatusLabelBadge :label="data.statusLabel" :fallback-bucket="data.status" />
        </template>
      </Column>
      <Column header="優先度" field="priority" style="width: 100px">
        <template #body="{ data }">
          <TodoPriorityBadge :priority="data.priority" />
        </template>
      </Column>
      <Column header="期限" field="schedule.dueDate" style="width: 120px">
        <template #body="{ data }">
          <span :class="{ 'font-semibold text-red-500': isOverdue(data) }">
            {{ formatDate(data.schedule?.dueDate) }}
          </span>
        </template>
      </Column>
      <Column v-if="canEdit || canDelete" header="操作" style="width: 100px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button v-if="canEdit" icon="pi pi-pencil" text rounded size="small" :data-testid="`team-todo-edit-${data.id}`" @click="emit('edit', data.id)" />
            <Button v-if="canDelete" icon="pi pi-trash" text rounded size="small" severity="danger" :data-testid="`team-todo-delete-${data.id}`" @click="onDelete(data.id)" />
          </div>
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-check-circle" message="TODOはありません" />
      </template>
    </DataTable>
  </div>
</template>
