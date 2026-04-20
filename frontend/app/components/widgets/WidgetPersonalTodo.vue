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

const teamNameMap = computed(() =>
  Object.fromEntries(teamStore.myTeams.map((t) => [t.id, t.nickname1 || t.name])),
)
const orgNameMap = computed(() =>
  Object.fromEntries(orgStore.myOrganizations.map((o) => [o.id, o.nickname1 || o.name])),
)

function scopeLabel(todo: TodoItem): string | null {
  if (todo.scopeType === 'PERSONAL') return null
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
      .slice(0, 15)
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

const priorityColor: Record<string, string> = {
  HIGH: 'text-red-500',
  MEDIUM: 'text-yellow-500',
  LOW: 'text-green-500',
  URGENT: 'text-red-600',
}

const priorityIcon: Record<string, string> = {
  HIGH: 'pi pi-exclamation-triangle',
  MEDIUM: 'pi pi-minus',
  LOW: 'pi pi-chevron-down',
  URGENT: 'pi pi-exclamation-circle',
}

function isOverdue(dueDate: string | null): boolean {
  if (!dueDate) return false
  return new Date(dueDate) < new Date()
}

const todosWithDue = computed(() =>
  todos.value.filter((t): t is TodoItem & { dueDate: string } => t.dueDate !== null),
)
const todosNoDue = computed(() => todos.value.filter((t) => t.dueDate === null))

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

    <div v-if="todos.length > 0" class="space-y-3">
      <div v-if="overdueCount > 0">
        <Tag :value="`期限切れ: ${overdueCount}件`" severity="danger" rounded />
      </div>

      <!-- 期限あり -->
      <div v-if="todosWithDue.length > 0">
        <p class="mb-1.5 text-[11px] font-medium uppercase tracking-wide text-surface-400">
          期限あり
        </p>
        <div class="grid grid-cols-3 gap-1.5 md:grid-cols-4 lg:grid-cols-5">
          <div
            v-for="todo in todosWithDue"
            :key="todo.id"
            class="flex cursor-pointer flex-col gap-1 rounded-lg border border-surface-200 p-2 transition-colors hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-700/50"
            @click="onToggle(todo)"
          >
            <div class="flex items-center justify-between gap-1">
              <Checkbox
                :model-value="todo.completed"
                binary
                class="shrink-0"
                @click.stop
                @update:model-value="onToggle(todo)"
              />
              <i
                v-if="priorityIcon[todo.priority]"
                :class="[priorityIcon[todo.priority], priorityColor[todo.priority]]"
                class="shrink-0 text-[11px]"
              />
            </div>
            <p
              class="line-clamp-2 text-xs leading-tight"
              :class="{ 'text-surface-400 line-through': todo.completed }"
            >
              {{ todo.title }}
            </p>
            <p
              class="text-[10px] font-medium"
              :class="isOverdue(todo.dueDate) ? 'text-red-500' : 'text-surface-400'"
            >
              {{ new Date(todo.dueDate).toLocaleDateString('ja-JP', { month: 'numeric', day: 'numeric' }) }}
            </p>
            <span
              v-if="scopeLabel(todo)"
              class="w-fit rounded-full px-1 py-0.5 text-[9px] font-medium"
              :class="scopeColor(todo.scopeType)"
            >
              {{ scopeLabel(todo) }}
            </span>
          </div>
        </div>
      </div>

      <!-- 期限なし -->
      <div v-if="todosNoDue.length > 0">
        <p class="mb-1.5 text-[11px] font-medium uppercase tracking-wide text-surface-400">
          期限なし
        </p>
        <div class="grid grid-cols-3 gap-1.5 md:grid-cols-4 lg:grid-cols-5">
          <div
            v-for="todo in todosNoDue"
            :key="todo.id"
            class="flex cursor-pointer flex-col gap-1 rounded-lg border border-surface-200 p-2 transition-colors hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-700/50"
            @click="onToggle(todo)"
          >
            <div class="flex items-center justify-between gap-1">
              <Checkbox
                :model-value="todo.completed"
                binary
                class="shrink-0"
                @click.stop
                @update:model-value="onToggle(todo)"
              />
              <i
                v-if="priorityIcon[todo.priority]"
                :class="[priorityIcon[todo.priority], priorityColor[todo.priority]]"
                class="shrink-0 text-[11px]"
              />
            </div>
            <p
              class="line-clamp-2 text-xs leading-tight"
              :class="{ 'text-surface-400 line-through': todo.completed }"
            >
              {{ todo.title }}
            </p>
            <span
              v-if="scopeLabel(todo)"
              class="w-fit rounded-full px-1 py-0.5 text-[9px] font-medium"
              :class="scopeColor(todo.scopeType)"
            >
              {{ scopeLabel(todo) }}
            </span>
          </div>
        </div>
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-check-circle" message="TODOはすべて完了しています" />
  </DashboardWidgetCard>
</template>
