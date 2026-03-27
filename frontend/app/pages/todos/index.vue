<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const todoApi = useTodoApi()

interface MyTodo {
  id: number
  scopeType: string
  scopeId: number
  title: string
  status: string
  priority: string
  dueDate: string | null
  daysRemaining: number | null
  assignees: Array<{ userId: number; displayName: string }>
  createdAt: string
}

const todos = ref<MyTodo[]>([])
const loading = ref(true)
const statusFilter = ref('')

const statusOptions = [
  { label: '全て', value: '' },
  { label: '未着手', value: 'OPEN' },
  { label: '進行中', value: 'IN_PROGRESS' },
  { label: '完了', value: 'COMPLETED' },
]

async function loadTodos() {
  loading.value = true
  try {
    const res = await todoApi.getMyTodos()
    todos.value = res.data
  }
  catch { todos.value = [] }
  finally { loading.value = false }
}

const filteredTodos = computed(() => {
  if (!statusFilter.value) return todos.value
  return todos.value.filter(t => t.status === statusFilter.value)
})

function isOverdue(todo: MyTodo): boolean {
  return todo.daysRemaining !== null && todo.daysRemaining < 0 && todo.status !== 'COMPLETED'
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

function scopeLabel(todo: MyTodo): string {
  if (todo.scopeType === 'PERSONAL') return '個人'
  if (todo.scopeType === 'TEAM') return 'チーム'
  return '組織'
}

onMounted(loadTodos)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">マイTODO</h1>
      <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" class="w-36" />
    </div>

    <div v-if="loading" class="space-y-3">
      <Skeleton v-for="i in 5" :key="i" height="4rem" />
    </div>

    <div v-else-if="filteredTodos.length > 0" class="space-y-2">
      <div
        v-for="todo in filteredTodos"
        :key="todo.id"
        class="flex items-center gap-4 rounded-lg border border-surface-200 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
      >
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <p class="truncate font-medium">{{ todo.title }}</p>
            <Tag :value="scopeLabel(todo)" severity="secondary" rounded />
          </div>
          <div class="mt-1 flex items-center gap-3 text-xs text-surface-500">
            <span :class="{ 'font-semibold text-red-500': isOverdue(todo) }">
              期限: {{ formatDate(todo.dueDate) }}
            </span>
          </div>
        </div>
        <TodoPriorityBadge :priority="todo.priority" />
        <TodoStatusBadge :status="todo.status" />
      </div>
    </div>

    <DashboardEmptyState v-else icon="pi pi-check-circle" message="TODOはありません" />
  </div>
</template>
