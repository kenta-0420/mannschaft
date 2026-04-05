export interface MyTodo {
  id: number
  scopeType: string
  scopeId: number | null
  title: string
  description: string | null
  status: string
  priority: string
  dueDate: string | null
  daysRemaining: number | null
  assignees: Array<{ id: number; userId: number; displayName: string; avatarUrl: string | null }>
  createdAt: string
}

export interface ListGroup {
  key: string
  label: string
  icon: string
  color: string
  todos: MyTodo[]
}

export interface KanbanCol {
  status: string
  label: string
  color: string
  headerColor: string
  todos: MyTodo[]
}

export const priorityBorder: Record<string, string> = {
  HIGH: 'border-l-4 border-l-red-400',
  MEDIUM: 'border-l-4 border-l-yellow-400',
  LOW: 'border-l-4 border-l-green-400',
}
export const priorityLabel: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }
export const priorityClass: Record<string, string> = {
  HIGH: 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400',
  MEDIUM: 'bg-yellow-100 text-yellow-600 dark:bg-yellow-900/30 dark:text-yellow-400',
  LOW: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400',
}

export function useTodoList() {
  const todoApi = useTodoApi()
  const teamStore = useTeamStore()
  const orgStore = useOrganizationStore()
  const notification = useNotification()

  const todos = ref<MyTodo[]>([])
  const loading = ref(true)
  const scopeTab = ref<'all' | 'personal' | 'team' | 'organization'>('all')
  const showCompleted = ref(false)

  const teamNameMap = computed(() =>
    Object.fromEntries(teamStore.myTeams.map((t) => [t.id, t.nickname1 || t.name])),
  )
  const orgNameMap = computed(() =>
    Object.fromEntries(orgStore.myOrganizations.map((o) => [o.id, o.nickname1 || o.name])),
  )

  function scopeDisplayName(todo: MyTodo): string {
    if (todo.scopeType === 'PERSONAL') return '個人'
    if (todo.scopeType === 'TEAM' && todo.scopeId) return teamNameMap.value[todo.scopeId] ?? 'チーム'
    if (todo.scopeType === 'ORGANIZATION' && todo.scopeId)
      return orgNameMap.value[todo.scopeId] ?? '組織'
    return ''
  }

  function scopeColor(scopeType: string): string {
    if (scopeType === 'PERSONAL')
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
    if (scopeType === 'TEAM')
      return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
    return 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400'
  }

  const baseTodos = computed(() => {
    let list = todos.value
    if (scopeTab.value !== 'all') {
      list = list.filter((t) => t.scopeType === scopeTab.value.toUpperCase())
    }
    if (!showCompleted.value) {
      list = list.filter((t) => t.status !== 'COMPLETED')
    }
    return list
  })

  const progress = computed(() => {
    const base =
      scopeTab.value === 'all'
        ? todos.value
        : todos.value.filter((t) => t.scopeType === scopeTab.value.toUpperCase())
    const total = base.length
    const completed = base.filter((t) => t.status === 'COMPLETED').length
    return { total, completed, pct: total === 0 ? 0 : Math.round((completed / total) * 100) }
  })

  const listGroups = computed<ListGroup[]>(() => {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    const tomorrow = new Date(today)
    tomorrow.setDate(tomorrow.getDate() + 1)
    const nextWeek = new Date(today)
    nextWeek.setDate(nextWeek.getDate() + 7)

    const active = baseTodos.value.filter((t) => t.status !== 'COMPLETED')

    const overdue = active.filter((t) => t.dueDate && new Date(t.dueDate) < today)
    const todayItems = active.filter((t) => {
      if (!t.dueDate) return false
      const d = new Date(t.dueDate)
      return d >= today && d < tomorrow
    })
    const thisWeek = active.filter((t) => {
      if (!t.dueDate) return false
      const d = new Date(t.dueDate)
      return d >= tomorrow && d < nextWeek
    })
    const later = active.filter((t) => {
      if (!t.dueDate) return false
      return new Date(t.dueDate) >= nextWeek
    })
    const noDue = active.filter((t) => !t.dueDate)
    const completed = showCompleted.value
      ? baseTodos.value.filter((t) => t.status === 'COMPLETED')
      : []

    return [
      {
        key: 'overdue',
        label: '期限切れ',
        icon: 'pi pi-exclamation-circle',
        color: 'text-red-500',
        todos: overdue,
      },
      { key: 'today', label: '今日', icon: 'pi pi-sun', color: 'text-orange-500', todos: todayItems },
      { key: 'week', label: '今週', icon: 'pi pi-calendar', color: 'text-blue-500', todos: thisWeek },
      {
        key: 'later',
        label: 'それ以降',
        icon: 'pi pi-clock',
        color: 'text-surface-500',
        todos: later,
      },
      {
        key: 'nodue',
        label: '期限なし',
        icon: 'pi pi-minus',
        color: 'text-surface-400',
        todos: noDue,
      },
      {
        key: 'done',
        label: '完了済み',
        icon: 'pi pi-check-circle',
        color: 'text-green-500',
        todos: completed,
      },
    ].filter((g) => g.todos.length > 0)
  })

  const kanbanCols = computed<KanbanCol[]>(() => [
    {
      status: 'OPEN',
      label: '未着手',
      color: 'bg-surface-100 dark:bg-surface-700',
      headerColor: 'text-surface-600 dark:text-surface-300',
      todos: baseTodos.value.filter((t) => t.status === 'OPEN'),
    },
    {
      status: 'IN_PROGRESS',
      label: '進行中',
      color: 'bg-blue-50 dark:bg-blue-900/10',
      headerColor: 'text-blue-600',
      todos: baseTodos.value.filter((t) => t.status === 'IN_PROGRESS'),
    },
    {
      status: 'COMPLETED',
      label: '完了',
      color: 'bg-green-50 dark:bg-green-900/10',
      headerColor: 'text-green-600',
      todos: baseTodos.value.filter((t) => t.status === 'COMPLETED'),
    },
  ])

  async function load() {
    loading.value = true
    try {
      const [todosRes] = await Promise.all([
        todoApi.getMyTodos(),
        teamStore.myTeams.length === 0 ? teamStore.fetchMyTeams() : Promise.resolve(),
        orgStore.myOrganizations.length === 0 ? orgStore.fetchMyOrganizations() : Promise.resolve(),
      ])
      todos.value = todosRes.data
    } catch {
      todos.value = []
    } finally {
      loading.value = false
    }
  }

  async function changeStatus(todo: MyTodo, status: string) {
    try {
      await todoApi.changeTodoStatusById(todo.scopeType, todo.scopeId, todo.id, status)
      todo.status = status
    } catch {
      notification.error('ステータスの更新に失敗しました')
    }
  }

  function nextStatus(current: string): string {
    if (current === 'OPEN') return 'IN_PROGRESS'
    if (current === 'IN_PROGRESS') return 'COMPLETED'
    return 'OPEN'
  }

  function nextStatusLabel(current: string): string {
    if (current === 'OPEN') return '進行中にする'
    if (current === 'IN_PROGRESS') return '完了にする'
    return '未着手に戻す'
  }

  function formatDate(d: string | null): string {
    if (!d) return ''
    return new Date(d).toLocaleDateString('ja-JP', { month: 'short', day: 'numeric' })
  }

  function isOverdue(todo: MyTodo): boolean {
    return !!todo.dueDate && (todo.daysRemaining ?? 0) < 0 && todo.status !== 'COMPLETED'
  }

  return {
    todos,
    loading,
    scopeTab,
    showCompleted,
    progress,
    listGroups,
    kanbanCols,
    load,
    changeStatus,
    nextStatus,
    nextStatusLabel,
    scopeDisplayName,
    scopeColor,
    formatDate,
    isOverdue,
  }
}
