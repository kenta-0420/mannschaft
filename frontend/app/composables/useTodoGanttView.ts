import type { GanttResponse, GanttTodo } from '~/types/todo'

/**
 * マイTODOページ内ガントビュー用ロジック。
 * calendar.vue（?tab=gantt）のガント挙動を抽出・汎用化したもの。
 * カレンダー固有の useMyCalendarData には依存せず、スコープ選択肢は
 * team / organization ストアから自前で構築する。
 */
export interface GanttScopeOption {
  label: string
  value: string
  scopeType: 'personal' | 'team' | 'organization'
  scopeId: string
}

export function useTodoGanttView() {
  const ganttApi = useTodoGantt()
  const teamStore = useTeamStore()
  const orgStore = useOrganizationStore()

  const pad = (n: number) => String(n).padStart(2, '0')

  const today = new Date()
  const currentYear = ref(today.getFullYear())
  const currentMonth = ref(today.getMonth() + 1)

  // 表示対象スコープ（初期 = 個人）
  const scopeKey = ref<string>('personal')

  const ganttTodos = ref<GanttTodo[]>([])
  const ganttFromDate = ref('')
  const ganttToDate = ref('')
  const ganttLoading = ref(false)
  // スコープ変更時にフェードで再描画するためのキー
  const ganttKey = ref(0)

  // スコープ選択肢（個人 + 所属チーム + 所属組織）
  const scopeOptions = computed<GanttScopeOption[]>(() => [
    { label: '個人', value: 'personal', scopeType: 'personal', scopeId: '' },
    ...teamStore.myTeams.map(t => ({
      label: t.nickname1 || t.name,
      value: `team:${t.id}`,
      scopeType: 'team' as const,
      scopeId: String(t.id),
    })),
    ...orgStore.myOrganizations.map(o => ({
      label: o.nickname1 || o.name,
      value: `org:${o.id}`,
      scopeType: 'organization' as const,
      scopeId: String(o.id),
    })),
  ])

  function getMonthRange(year: number, month: number): { from: string; to: string } {
    const lastDay = new Date(year, month, 0).getDate()
    return {
      from: `${year}-${pad(month)}-01`,
      to: `${year}-${pad(month)}-${pad(lastDay)}`,
    }
  }

  // ガントデータのキャッシュ（年月×スコープをキーに保持）
  const ganttCache = new Map<string, GanttTodo[]>()

  function ganttCacheKey(year: number, month: number): string {
    return `${year}-${pad(month)}-${scopeKey.value}`
  }

  async function fetchGanttMonth(year: number, month: number): Promise<GanttTodo[]> {
    const key = ganttCacheKey(year, month)
    const cached = ganttCache.get(key)
    if (cached) return cached

    const { from, to } = getMonthRange(year, month)
    const scope = scopeOptions.value.find(o => o.value === scopeKey.value)
    if (!scope) return []

    let res: GanttResponse
    if (scope.scopeType === 'personal') {
      res = await ganttApi.getPersonalGanttTodos(from, to)
    } else {
      res = await ganttApi.getGanttTodos(scope.scopeType, scope.scopeId, from, to)
    }

    ganttCache.set(key, res.data)
    return res.data
  }

  function prefetchAdjacentMonths(year: number, month: number) {
    for (let delta = -2; delta <= 2; delta++) {
      if (delta === 0) continue
      const d = new Date(year, month - 1 + delta, 1)
      const y = d.getFullYear()
      const m = d.getMonth() + 1
      if (!ganttCache.has(ganttCacheKey(y, m))) {
        // 隣接月の先読み（prefetch）。失敗してもユーザーがその月へ移動した際に
        // 再取得されるため、ここでのエラーは非クリティカルとして握りつぶす。
        fetchGanttMonth(y, m).catch(() => {})
      }
    }
  }

  async function loadGantt() {
    const year = currentYear.value
    const month = currentMonth.value
    const { from, to } = getMonthRange(year, month)
    ganttFromDate.value = from
    ganttToDate.value = to

    const cached = ganttCache.get(ganttCacheKey(year, month))
    if (cached) {
      // キャッシュヒット: ローディングなしで即表示
      ganttTodos.value = cached
    } else {
      ganttLoading.value = true
      try {
        ganttTodos.value = await fetchGanttMonth(year, month)
      } catch {
        ganttTodos.value = []
      } finally {
        ganttLoading.value = false
      }
    }

    // 表示後に前後2か月をバックグラウンドでプリフェッチ
    prefetchAdjacentMonths(year, month)
  }

  function onPrevMonth() {
    const d = new Date(currentYear.value, currentMonth.value - 1 - 1, 1)
    currentYear.value = d.getFullYear()
    currentMonth.value = d.getMonth() + 1
    loadGantt()
  }

  function onNextMonth() {
    const d = new Date(currentYear.value, currentMonth.value - 1 + 1, 1)
    currentYear.value = d.getFullYear()
    currentMonth.value = d.getMonth() + 1
    loadGantt()
  }

  // スコープ変更時はキャッシュを破棄して再取得
  watch(scopeKey, () => {
    ganttCache.clear()
    ganttKey.value++
    loadGantt()
  })

  // スコープ選択肢を確保するため、未取得なら所属チーム・組織を取得する
  async function ensureScopes() {
    const tasks: Promise<unknown>[] = []
    if (teamStore.myTeams.length === 0) tasks.push(teamStore.fetchMyTeams())
    if (orgStore.myOrganizations.length === 0) tasks.push(orgStore.fetchMyOrganizations())
    if (tasks.length > 0) await Promise.all(tasks)
  }

  return {
    currentYear,
    currentMonth,
    scopeKey,
    scopeOptions,
    ganttTodos,
    ganttFromDate,
    ganttToDate,
    ganttLoading,
    ganttKey,
    loadGantt,
    onPrevMonth,
    onNextMonth,
    ensureScopes,
  }
}
