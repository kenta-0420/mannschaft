<script setup lang="ts">
const { getPersonalTodos, toggleTodoComplete } = useDashboardApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

interface TodoItem {
  id: number
  title: string
  completed: boolean
  dueDate: string | null
  priority: string
  scopeType: string
  scopeId: number | null
}

const todos = ref<TodoItem[]>([])
const overdueCount = ref(0)
const loading = ref(true)

// スコープ名マップ
const teamNameMap = computed(() =>
  Object.fromEntries(teamStore.myTeams.map((t) => [t.id, t.nickname1 || t.name])),
)
const orgNameMap = computed(() =>
  Object.fromEntries(orgStore.myOrganizations.map((o) => [o.id, o.nickname1 || o.name])),
)

function scopeLabel(todo: TodoItem): string | null {
  if (todo.scopeType === 'PERSONAL') return null // 個人は表示不要
  if (todo.scopeType === 'TEAM' && todo.scopeId) return teamNameMap.value[todo.scopeId] ?? 'チーム'
  if (todo.scopeType === 'ORGANIZATION' && todo.scopeId)
    return orgNameMap.value[todo.scopeId] ?? '組織'
  return null
}

function scopeColor(scopeType: string): string {
  if (scopeType === 'TEAM')
    return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
  return 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400'
}

async function load() {
  loading.value = true
  try {
    const [res] = await Promise.all([
      getPersonalTodos(),
      teamStore.myTeams.length === 0 ? teamStore.fetchMyTeams() : Promise.resolve(),
      orgStore.myOrganizations.length === 0 ? orgStore.fetchMyOrganizations() : Promise.resolve(),
    ])
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    todos.value = res.data
      .filter((t) => t.status !== 'COMPLETED')
      .map((t) => ({
        id: t.id,
        title: t.title,
        completed: false,
        dueDate: t.dueDate,
        priority: t.priority,
        scopeType: t.scopeType,
        scopeId: t.scopeId,
      }))
      .slice(0, 10) // ウィジェットは最大10件
    overdueCount.value = todos.value.filter(
      (t) => t.dueDate !== null && new Date(t.dueDate) < today,
    ).length
  } catch (error) {
    captureQuiet(error, { context: 'WidgetPersonalTodo: TODO一覧取得' })
    todos.value = []
  } finally {
    loading.value = false
  }
}

async function onToggle(todo: TodoItem) {
  try {
    await toggleTodoComplete(todo.id, !todo.completed)
    todo.completed = !todo.completed
    if (todo.completed) notification.success('TODO完了！')
  } catch (error) {
    captureQuiet(error, { context: 'WidgetPersonalTodo: TODO完了切り替え' })
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
  <DashboardWidgetCard
    title="TODO"
    icon="pi pi-check-square"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <template #action>
      <NuxtLink to="/todos" class="text-xs text-primary hover:underline">すべて表示</NuxtLink>
    </template>

    <div v-if="todos.length > 0">
      <div v-if="overdueCount > 0" class="mb-2">
        <Tag :value="`期限切れ: ${overdueCount}件`" severity="danger" rounded />
      </div>
      <div class="space-y-1.5">
        <div
          v-for="todo in todos"
          :key="todo.id"
          class="flex items-start gap-2.5 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
        >
          <Checkbox
            :model-value="todo.completed"
            binary
            class="mt-0.5 shrink-0"
            @update:model-value="onToggle(todo)"
          />
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-1.5">
              <p class="text-sm" :class="{ 'text-surface-400 line-through': todo.completed }">
                {{ todo.title }}
              </p>
              <!-- チーム・組織から割り当てられたTODOにはバッジ表示 -->
              <span
                v-if="scopeLabel(todo)"
                class="rounded-full px-1.5 py-0.5 text-[10px] font-medium"
                :class="scopeColor(todo.scopeType)"
              >
                {{ scopeLabel(todo) }}
              </span>
            </div>
            <p
              v-if="todo.dueDate"
              class="text-xs"
              :class="isOverdue(todo.dueDate) ? 'text-red-500 font-medium' : 'text-surface-400'"
            >
              期限: {{ new Date(todo.dueDate).toLocaleDateString('ja-JP') }}
            </p>
          </div>
          <i :class="priorityIcon[todo.priority] ?? ''" class="mt-0.5 shrink-0 text-xs" />
        </div>
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-check-circle" message="TODOはすべて完了しています" />
  </DashboardWidgetCard>
</template>
