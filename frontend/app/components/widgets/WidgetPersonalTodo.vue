<script setup lang="ts">
const { getPersonalTodos, toggleTodoComplete } = useDashboardApi()
const notification = useNotification()

interface TodoItem {
  id: number
  title: string
  completed: boolean
  dueDate: string | null
  priority: string
  scopeType: string
  scopeName: string
}

const todos = ref<TodoItem[]>([])
const overdueCount = ref(0)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await getPersonalTodos()
    todos.value = res.data.items
    overdueCount.value = res.data.overdueCount
  }
  catch { todos.value = [] }
  finally { loading.value = false }
}

async function onToggle(todo: TodoItem) {
  try {
    await toggleTodoComplete(todo.id, !todo.completed)
    todo.completed = !todo.completed
    if (todo.completed) notification.success('TODO完了！')
  }
  catch {
    notification.error('更新に失敗しました')
  }
}

const priorityIcon: Record<string, string> = {
  HIGH: 'pi pi-exclamation-triangle text-red-500',
  MEDIUM: 'pi pi-minus text-yellow-500',
  LOW: 'pi pi-chevron-down text-green-500',
}

function isOverdue(dueDate: string | null): boolean {
  if (!dueDate) return false
  return new Date(dueDate) < new Date()
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard title="TODO" icon="pi pi-check-square" :loading="loading" refreshable @refresh="load">
    <div v-if="todos.length > 0">
      <div v-if="overdueCount > 0" class="mb-2">
        <Tag :value="`期限切れ: ${overdueCount}件`" severity="danger" rounded />
      </div>
      <div class="space-y-2">
        <div
          v-for="todo in todos"
          :key="todo.id"
          class="flex items-center gap-3 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
        >
          <Checkbox :model-value="todo.completed" binary @update:model-value="onToggle(todo)" />
          <div class="min-w-0 flex-1">
            <p class="text-sm" :class="{ 'text-surface-400 line-through': todo.completed }">{{ todo.title }}</p>
            <p v-if="todo.dueDate" class="text-xs" :class="isOverdue(todo.dueDate) ? 'text-red-500' : 'text-surface-400'">
              期限: {{ new Date(todo.dueDate).toLocaleDateString('ja-JP') }}
            </p>
          </div>
          <i :class="priorityIcon[todo.priority] ?? ''" />
        </div>
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-check-circle" message="TODOはすべて完了しています" />
  </DashboardWidgetCard>
</template>
