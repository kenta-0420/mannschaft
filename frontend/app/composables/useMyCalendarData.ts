import type { CalendarEventItem } from './useCalendarEvents'
import type { GanttTodo } from '~/types/todo'

export interface CalEvent extends CalendarEventItem {
  scopeId?: number
  scopeIconUrl?: string | null
  isTodo?: boolean
}

export interface ScopeOption {
  label: string
  value: string
  scopeType: string
  scopeId: number
}

export const PERSONAL_KEY = 'PERSONAL'
export const FILTER_OVERFLOW = 5

export function useMyCalendarData(options?: { storageKey?: string }) {
  const SCOPE_FILTER_KEY = options?.storageKey ?? 'mannschaft:calendar:scopeFilter'
  const scheduleApi = useScheduleApi()
  const ganttApi = useTodoGantt()

  const extendedEvents = ref<CalEvent[]>([])

  const fetcher = async (from: string, to: string): Promise<CalendarEventItem[]> => {
    const year = parseInt(from.slice(0, 4), 10)
    const month = parseInt(from.slice(5, 7), 10)
    const [personal, shared, todosRes] = await Promise.all([
      scheduleApi.listPersonalSchedules({ from, to }),
      scheduleApi.getCalendarMonth(year, month),
      ganttApi.getPersonalGanttTodos(from, to).catch(() => ({ data: [] as GanttTodo[] })),
    ])

    const personalEvents = ((personal.data ?? []) as CalEvent[]).map((e) => ({
      ...e,
      allDay: e.allDay ?? false,
      color: e.color ?? '#22c55e',
      isPersonal: true,
      scopeType: 'PERSONAL',
      scopeId: undefined,
      scopeName: null,
    }))

    const sharedEvents = ((shared.data as unknown as CalEvent[]) ?? [])
      .filter((e) => e.scopeType !== 'PERSONAL')
      .map((e) => ({
        ...e,
        allDay: e.allDay ?? false,
        color: e.color ?? null,
        isPersonal: false,
        scopeType: e.scopeType ?? '',
        scopeId: e.scopeId,
        scopeName: e.scopeName ?? null,
        scopeIconUrl: e.scopeIconUrl ?? null,
      }))

    // 期限付き TODO をカレンダーに追加（完了済みは除外）
    // ID は負数にしてスケジュール ID と衝突しないようにする
    const todos = (todosRes.data ?? []) as GanttTodo[]
    const todoEvents: CalEvent[] = todos
      .filter((t) => t.dueDate && t.status !== 'COMPLETED')
      .map((t) => ({
        id: -(t.id + 1),
        title: t.title,
        startAt: `${t.startDate || t.dueDate}T00:00:00`,
        endAt: `${t.dueDate}T23:59:59`,
        allDay: true,
        color: t.priority === 'HIGH' ? '#f97316'
          : t.priority === 'LOW' ? '#22c55e'
          : '#3b82f6',
        isPersonal: true,
        scopeType: 'PERSONAL',
        scopeId: undefined,
        scopeName: null,
        isTodo: true,
      }))

    const merged = [...personalEvents, ...sharedEvents, ...todoEvents]
    extendedEvents.value = merged
    return merged
  }

  const { currentYear, currentMonth, events, loading, loadEvents, refresh, onPrevMonth, onNextMonth } =
    useCalendarEvents(fetcher, { cacheHalfMonths: 0 })

  const availableScopes = computed<ScopeOption[]>(() => {
    const seen = new Set<string>()
    const result: ScopeOption[] = []
    for (const e of extendedEvents.value) {
      if (!e.scopeType || e.scopeType === 'PERSONAL' || !e.scopeId) continue
      const key = `${e.scopeType}:${e.scopeId}`
      if (!seen.has(key)) {
        seen.add(key)
        result.push({ label: e.scopeName ?? `${e.scopeType} ${e.scopeId}`, value: key, scopeType: e.scopeType, scopeId: e.scopeId })
      }
    }
    return result
  })

  const allScopeOptions = computed<ScopeOption[]>(() => [
    { label: '個人', value: PERSONAL_KEY, scopeType: 'PERSONAL', scopeId: 0 },
    ...availableScopes.value,
  ])

  const selectedScopes = ref<string[]>([])

  const filteredEvents = computed(() =>
    events.value.filter((e) => {
      const ext = extendedEvents.value.find((x) => x.id === e.id)
      if (!ext) return false
      if (ext.isPersonal || ext.scopeType === 'PERSONAL') return selectedScopes.value.includes(PERSONAL_KEY)
      return selectedScopes.value.includes(`${ext.scopeType}:${ext.scopeId}`)
    }),
  )

  function toggleScope(value: string) {
    const idx = selectedScopes.value.indexOf(value)
    if (idx >= 0) selectedScopes.value = selectedScopes.value.filter((_, i) => i !== idx)
    else selectedScopes.value = [...selectedScopes.value, value]
  }

  const multiSelectScopes = computed({
    get: () => [...selectedScopes.value],
    set: (vals: string[]) => { selectedScopes.value = vals },
  })

  let hasSavedFilter = false
  let scopesInitialized = false

  function initStorage() {
    try {
      const saved = localStorage.getItem(SCOPE_FILTER_KEY)
      if (saved) {
        selectedScopes.value = JSON.parse(saved)
        hasSavedFilter = true
      }
    }
    catch { /* ignore */ }
  }

  watch(selectedScopes, (val) => {
    try { localStorage.setItem(SCOPE_FILTER_KEY, JSON.stringify(val)) }
    catch { /* ignore */ }
  }, { deep: true })

  watch(allScopeOptions, (opts) => {
    if (!scopesInitialized && opts.length > 1) {
      scopesInitialized = true
      if (!hasSavedFilter) selectedScopes.value = opts.map((s) => s.value)
    }
  })

  return {
    currentYear, currentMonth, events, loading, loadEvents, refresh, onPrevMonth, onNextMonth,
    extendedEvents, availableScopes, allScopeOptions, selectedScopes, filteredEvents,
    toggleScope, multiSelectScopes, initStorage,
  }
}
