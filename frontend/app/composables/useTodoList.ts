import type { TodoStatusLabelInfo } from '~/types/todoStatusLabel'

/** Wave 1 DTO刷新: MyTodo はネスト構造の TodoResponse を反映 */
export interface MyTodo {
  id: number
  /** @deprecated 旧フラットフィールド互換 — scope?.scopeType を優先使用 */
  scopeType: string
  /** @deprecated 旧フラットフィールド互換 — scope?.scopeId を優先使用 */
  scopeId: string | null
  /** TEAM / ORGANIZATION の slug（URL遷移用）。PERSONAL は null。 */
  scopeSlug: string | null
  scope?: {
    scopeType?: string
    scopeId?: string | null
    projectId?: number | null
    milestoneId?: number | null
    /** TEAM / ORGANIZATION の slug（URL遷移用）。PERSONAL は null。 */
    scopeSlug?: string | null
  }
  content?: {
    title?: string
    description?: string | null
    startDate?: string | null
    progressRate?: number | null
    progressManual?: boolean
    sortOrder?: number
  }
  schedule?: {
    dueDate?: string | null
    dueTime?: string | null
    daysRemaining?: number | null
    linkedScheduleId?: number | null
  }
  /** @deprecated 旧フラットフィールド互換 — ステータスバケット */
  status: string
  /** F02.3.1 — カスタムステータスラベル（NULL の場合は SYSTEM 既定にフォールバック） — @deprecated 旧フラットフィールド互換 */
  statusLabel: TodoStatusLabelInfo | null
  /** @deprecated 旧フラットフィールド互換 */
  priority: string
  /** @deprecated 旧フラットフィールド互換 — schedule?.dueDate を優先使用 */
  dueDate: string | null
  /** @deprecated 旧フラットフィールド互換 — schedule?.daysRemaining を優先使用 */
  daysRemaining: number | null
  /** @deprecated 旧フラットフィールド互換 — content?.startDate を優先使用 */
  startDate: string | null
  assignees: Array<{ id: number; userId: number; displayName: string; avatarUrl: string | null }>
  createdAt: string
}

export interface ListGroup {
  key: string
  label: string
  icon: string
  color: string
  /** ヘッダー帯の背景色（看板ビューの列ヘッダーと揃える） */
  headerBg: string
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
  const { showUndoToast } = useUndoToast()
  const { t } = useI18n()
  const { formatDate } = useDatetime()

  const todos = ref<MyTodo[]>([])
  const loading = ref(true)
  const scopeTab = ref<'all' | 'personal' | 'team' | 'organization'>('all')

  // localStorage で状態を永続化（ブラウザ再起動後も維持）
  const SHOW_COMPLETED_KEY = 'mannschaft:todo:showCompleted'
  const showCompleted = ref<boolean>(
    typeof localStorage !== 'undefined'
      ? localStorage.getItem(SHOW_COMPLETED_KEY) === 'true'
      : false,
  )

  // 変更時に保存
  watch(showCompleted, (val) => {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(SHOW_COMPLETED_KEY, String(val))
    }
  })

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
        headerBg: 'bg-red-50 dark:bg-red-900/10',
        todos: overdue,
      },
      {
        key: 'today',
        label: '今日',
        icon: 'pi pi-sun',
        color: 'text-orange-500',
        headerBg: 'bg-orange-50 dark:bg-orange-900/10',
        todos: todayItems,
      },
      {
        key: 'week',
        label: '今週',
        icon: 'pi pi-calendar',
        color: 'text-blue-500',
        headerBg: 'bg-blue-50 dark:bg-blue-900/10',
        todos: thisWeek,
      },
      {
        key: 'later',
        label: 'それ以降',
        icon: 'pi pi-clock',
        color: 'text-surface-500',
        headerBg: 'bg-surface-100 dark:bg-surface-700',
        todos: later,
      },
      {
        key: 'nodue',
        label: '期限なし',
        icon: 'pi pi-minus',
        color: 'text-surface-400',
        headerBg: 'bg-surface-100 dark:bg-surface-700',
        todos: noDue,
      },
      {
        key: 'done',
        label: '完了済み',
        icon: 'pi pi-check-circle',
        color: 'text-green-500',
        headerBg: 'bg-green-50 dark:bg-green-900/10',
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

  /**
   * TODO 一覧を取得する。
   *
   * @param options.silent true の場合、`loading.value` をトグルせず、
   *   背後でデータだけを差し替える。`PageLoading` の発動による全画面ちらつきを避けたい
   *   場面（ラベル変更後の同期取得など）で使用する。
   */
  async function load(options: { silent?: boolean } = {}) {
    const silent = options.silent === true
    if (!silent) {
      loading.value = true
    }
    try {
      const [todosRes] = await Promise.all([
        todoApi.getMyTodos(),
        teamStore.myTeams.length === 0 ? teamStore.fetchMyTeams() : Promise.resolve(),
        orgStore.myOrganizations.length === 0 ? orgStore.fetchMyOrganizations() : Promise.resolve(),
      ])
      // Wave 1 DTO刷新: ネスト構造から旧フラットフィールドへ正規化マッピング（後方互換）
      // slug 根治: scope.scopeSlug を scopeSlug フラットフィールドに引き継ぐ
      todos.value = (todosRes.data as unknown as MyTodo[]).map((item) => ({
        ...item,
        scopeType: item.scope?.scopeType ?? item.scopeType ?? '',
        scopeId: item.scope?.scopeId ?? item.scopeId ?? null,
        scopeSlug: item.scope?.scopeSlug ?? item.scopeSlug ?? null,
        status: item.status ?? '',
        priority: item.priority ?? '',
        statusLabel: item.statusLabel ?? null,
        dueDate: item.schedule?.dueDate ?? item.dueDate ?? null,
        daysRemaining: item.schedule?.daysRemaining ?? item.daysRemaining ?? null,
        startDate: item.content?.startDate ?? item.startDate ?? null,
      }))
    } catch {
      // silent モードのときは既存の todos を維持し、画面のちらつきを避ける
      if (!silent) {
        todos.value = []
      }
    } finally {
      if (!silent) {
        loading.value = false
      }
    }
  }

  /**
   * ステータス変更
   *
   * F02.3.1: status または statusLabelId（あるいは両方）で更新できる。
   * 後方互換のため文字列を受け取った場合は status として扱う。
   *
   * マイ TODO 一覧 UX 補強: statusLabelId 指定時は完全な statusLabel オブジェクトを
   * 即時に楽観更新できないため、API 成功後にリストを再取得して同期する。
   */
  async function changeStatus(
    todo: MyTodo,
    payload: string | { status?: string; statusLabelId?: number },
  ) {
    try {
      const body = typeof payload === 'string' ? { status: payload } : payload
      await todoApi.changeTodoStatusById(todo.scopeType, todo.scopeId, todo.id, body)
      // 楽観更新: ローカル状態を即時更新（厳密な statusLabel 同期はリロード時）
      if (body.status) {
        todo.status = body.status
      }
      // statusLabelId 指定時はラベル情報を新しく取り直す必要があるため再ロード。
      // silent=true で再取得することで PageLoading が発動せず、行単位の差し替えになる。
      if (body.statusLabelId !== undefined) {
        await load({ silent: true })
      }
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

  function isOverdue(todo: MyTodo): boolean {
    return !!todo.dueDate && (todo.daysRemaining ?? 0) < 0 && todo.status !== 'COMPLETED'
  }

  // ADHD 配慮 AC-16: 確認ダイアログを廃止し、即時削除 + Undo Toast に置換する。
  // 個人 TODO は論理削除なので、Undo で restore EP を叩けば一覧に復活する。
  async function deleteTodo(todo: MyTodo) {
    if (todo.scopeType !== 'PERSONAL' && todo.scopeId) {
      // チーム・組織TODOの削除はスコープ別APIを使用（現状は個人のみ対応）
      notification.error('チーム・組織TODOの削除はTODO詳細画面から行ってください')
      return
    }
    const snapshot = todos.value
    try {
      await todoApi.deletePersonalTodo(todo.id)
      // 楽観更新: ローカルから即時除去
      todos.value = todos.value.filter((t) => t.id !== todo.id)
      showUndoToast({
        summary: t('todo.list.deletedToast'),
        undoLabel: t('button.undo'),
        severity: 'info',
        onUndo: async () => {
          try {
            await todoApi.restorePersonalTodo(todo.id)
            notification.success(t('todo.list.restoredToast'))
            await load({ silent: true })
          } catch {
            notification.error(t('todo.list.restoreFailed'))
          }
        },
      })
    } catch {
      // 失敗時は楽観更新を巻き戻す（対処療法でなく状態整合を保つ）
      todos.value = snapshot
      notification.error(t('todo.list.deleteFailed'))
    }
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
    deleteTodo,
    nextStatus,
    nextStatusLabel,
    scopeDisplayName,
    scopeColor,
    formatDate,
    isOverdue,
  }
}
