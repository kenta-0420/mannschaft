import type { CalendarEventItem } from './useCalendarEvents'
import type { MyCalendarTodo } from '~/types/todo'

interface CalendarEntryRaw {
  // reflection 等 UUID 主キードメインの行は id=null（§6.2/AC-21）。
  id: number | null
  // 親 schedules 行の ID（§1.5/AC-07(b)）。schedule 由来は id と同値、reflection 等は null。
  scheduleId: number | null
  content: {
    title: string
    eventType: string
    status: string
    // UUID 主キードメイン（reflection・F06.5 §6.2）の識別子。schedule 行は両者 null。
    referenceUuid?: string | null
    referenceKind?: string | null
  }
  time: { startAt: string; endAt: string; allDay: boolean }
  scope: { scopeType: string; scopeId: string; scopeName: string | null; scopeIconUrl: string | null }
  myAttendanceStatus: string
  targetMode?: 'ALL_MEMBERS' | 'SELECTED_MEMBERS'
  targetCount?: number
  targets?: Array<{
    userId: number
    displayName: string
    avatarUrl: string | null
    calendarColor: string | null
  }>
}

interface PersonalScheduleRaw {
  id: number
  content: { title: string; eventType: string; color: string | null }
  time: { startAt: string; endAt: string; allDay: boolean }
}

export interface CalEvent extends CalendarEventItem {
  scopeId?: string
  scopeIconUrl?: string | null
  isTodo?: boolean
}

export interface ScopeOption {
  label: string
  value: string
  scopeType: string
  scopeId: string
}

export const PERSONAL_KEY = 'PERSONAL'
export const FILTER_OVERFLOW = 5

/**
 * エラーから HTTP ステータスを取り出す（ofetch の FetchError は statusCode と response.status の双方を持つ）。
 * ネットワーク断・タイムアウトなど応答が無い失敗では undefined を返す。
 */
export function extractHttpStatus(error: unknown): number | undefined {
  const e = error as { statusCode?: number; status?: number; response?: { status?: number } } | null
  return e?.statusCode ?? e?.status ?? e?.response?.status
}

/**
 * TODO レイヤの取得失敗について、この層から追加のトーストを出してよいかを判定する（Issue #2637）。
 *
 * <p>useApi の onResponseError は次の status で既にトーストを出している（実コードで確認）:
 * <ul>
 *   <li>429: 即時に severity=warn のレート制限トースト（集約しない）</li>
 *   <li>5xx: 500ms 集約後に severity=error のサーバーエラートースト</li>
 * </ul>
 * これらで TODO 側もトーストを出すと、同じ1回の失敗で警告が2件出る。
 * よって共通ハンドラが出さない失敗（403・404 等の 4xx、応答なしのネットワーク失敗）に限り、
 * この層からユーザーへ提示する。共通側が出す場合でも todosFailed と常設注記は必ず立てる。</p>
 */
export function shouldNotifyTodoLoadFailure(status: number | undefined): boolean {
  if (status === undefined) return true // 応答なし（ネットワーク断・タイムアウト）: 共通ハンドラは何も出さない
  if (status === 429) return false
  if (status >= 500) return false
  return true
}

/**
 * 自己担当TODOの表示可否。リンク先予定が既に見えている場合は予定を正本として抑止する。
 */
export function shouldDisplayMyCalendarTodo(todo: MyCalendarTodo, visibleScheduleIds: Set<number>): boolean {
  return !!todo.dueDate
    && todo.status !== 'COMPLETED'
    && (!todo.linkedScheduleId || !visibleScheduleIds.has(todo.linkedScheduleId))
}

/**
 * API がフィルタ済みであることを正本としつつ、現在ユーザーIDが得られる場面だけ防御的に確認する。
 * ID 未初期化時に予定を消してしまわないため、unknown は常に表示する。
 */
export function shouldDisplayScheduleForCurrentUser(
  targetMode: CalendarEntryRaw['targetMode'],
  targets: CalendarEntryRaw['targets'],
  currentUserId: number | null | undefined,
): boolean {
  if (targetMode !== 'SELECTED_MEMBERS' || currentUserId == null || !targets?.length) return true
  return targets.some(target => target.userId === currentUserId)
}

export function useMyCalendarData(options?: { storageKey?: string }) {
  const SCOPE_FILTER_KEY = options?.storageKey ?? 'mannschaft:calendar:scopeFilter'
  const scheduleApi = useScheduleApi()
  const ganttApi = useTodoGantt()
  const { buildDayStartStr, buildDayEndStr } = useDatetime()
  const errorHandler = useErrorHandler()
  const errorReport = useErrorReport()
  const authStore = useAuthStore()

  const extendedEvents = ref<CalEvent[]>([])
  /** TODO レイヤの取得に失敗したか（Issue #2637: 部分失敗をユーザーと利用側に見せる） */
  const todosFailed = ref(false)

  const fetcher = async (from: string, to: string): Promise<CalendarEventItem[]> => {
    // TODO 取得だけは部分失敗として扱う（カレンダー本体＝個人予定・共有予定・reflection は描画を続ける）。
    // ただし失敗は握りつぶさず、必ずエラー報告に載せ、失敗状態（todosFailed）を立てる（Issue #2637）。
    const fetchTodos = async (): Promise<{ data: MyCalendarTodo[] }> => {
      try {
        const res = await ganttApi.getMyCalendarTodos(from, to)
        todosFailed.value = false
        return res
      }
      catch (e) {
        const context = `useMyCalendarData.getMyCalendarTodos(${from}..${to})`
        if (shouldNotifyTodoLoadFailure(extractHttpStatus(e))) {
          // 共通ハンドラが提示しない失敗（4xx・応答なし）。共通エラーハンドラへ委譲し、
          // errorReport への記録とユーザー提示の双方を任せる。
          errorHandler.handleApiError(e, context)
        }
        else {
          // 429 / 5xx は useApi の onResponseError が既にトーストを出しているため、
          // ここで重ねない（二重トースト防止）。ただし「TODO レイヤの失敗」という文脈は
          // 共通側の記録に残らないため、調査情報として静かに追加報告する（握りつぶさない）。
          errorReport.captureQuiet(e, { context })
        }
        todosFailed.value = true
        return { data: [] }
      }
    }

    const [personal, shared, todosRes] = await Promise.all([
      scheduleApi.listPersonalSchedules({ from, to }),
      scheduleApi.getCalendarRange(from, to),
      fetchTodos(),
    ])

    const personalEvents = ((personal.data ?? []) as unknown as PersonalScheduleRaw[]).map((e): CalEvent => ({
      id: e.id,
      scheduleId: e.id,
      uniqueKey: `personal:${e.id}`,
      title: e.content?.title ?? '',
      startAt: e.time?.startAt ?? '',
      endAt: e.time?.endAt ?? '',
      allDay: e.time?.allDay ?? false,
      color: e.content?.color ?? '#22c55e',
      isPersonal: true,
      scopeType: 'PERSONAL',
      scopeId: undefined,
      scopeName: null,
    }))

    const sharedRaw = (shared.data as unknown as CalendarEntryRaw[]) ?? []

    // reflection 等 UUID 主キードメインの印（referenceKind を持つ・id=null）。
    // §6.2/AC-21: 既存の id 依存ルックアップが id=null で壊れるのを防ぐため、ここで分岐して
    // 一意キーを referenceUuid+referenceKind から作る。reflection 行は scopeType=PERSONAL で来るため
    // 下の PERSONAL 除外フィルタより先に拾う。
    const reflectionEvents = sharedRaw
      .filter((e) => !!e.content?.referenceKind && !!e.content?.referenceUuid)
      .map((e): CalEvent => ({
        id: -1, // UUID 主キーゆえ数値 id は持たない（ルックアップ/描画は uniqueKey を使う）
        scheduleId: null, // reflection 行は親 schedules を持たない
        uniqueKey: `ref:${e.content.referenceKind}:${e.content.referenceUuid}`,
        title: e.content?.title ?? '',
        startAt: e.time?.startAt ?? '',
        endAt: e.time?.endAt ?? '',
        allDay: e.time?.allDay ?? true,
        // 想起予定=橙、振り返り記入=藍。CalendarGrid の凡例と整合。
        color: e.content?.referenceKind === 'REFLECTION_RECALL' ? '#f59e0b' : '#6366f1',
        isPersonal: true,
        isReflection: true,
        referenceUuid: e.content?.referenceUuid ?? null,
        referenceKind: e.content?.referenceKind ?? null,
        eventType: e.content?.eventType ?? undefined,
        scopeType: 'PERSONAL',
        scopeId: undefined,
        scopeName: null,
      }))

    const sharedEvents = sharedRaw
      // reflection 等 UUID ドメイン行は上で別途処理済み。残りの PERSONAL 行は除外（重複防止）。
      .filter((e) => !e.content?.referenceKind && e.scope?.scopeType !== 'PERSONAL')
      .filter(e => shouldDisplayScheduleForCurrentUser(e.targetMode, e.targets, authStore.currentUser?.id))
      .map((e): CalEvent => ({
        id: e.id as number,
        scheduleId: e.scheduleId ?? null,
        uniqueKey: `shared:${e.id}`,
        title: e.content?.title ?? '',
        startAt: e.time?.startAt ?? '',
        endAt: e.time?.endAt ?? '',
        allDay: e.time?.allDay ?? false,
        color: null,
        isPersonal: false,
        scopeType: e.scope?.scopeType ?? '',
        scopeId: e.scope?.scopeId,
        scopeName: e.scope?.scopeName ?? null,
        scopeIconUrl: e.scope?.scopeIconUrl ?? null,
        targetMode: e.targetMode,
        targetCount: e.targetCount,
        targets: e.targets,
      }))

    // 期限付き TODO をカレンダーに追加（完了済みは除外）
    // ID は負数にしてスケジュール ID と衝突しないようにする
    const visibleScheduleIds = new Set([
      ...personalEvents.map(event => event.scheduleId),
      ...sharedEvents.map(event => event.scheduleId),
    ].filter((id): id is number => id !== null && id !== undefined))
    const todos = todosRes.data ?? []
    const todoEvents: CalEvent[] = todos
      .filter(t => shouldDisplayMyCalendarTodo(t, visibleScheduleIds))
      .map((t) => ({
        id: -(t.id + 1),
        uniqueKey: `todo:${t.id}`,
        title: t.title,
        // TODO の期限は LocalDate。ユーザーTZの 00:00:00 / 23:59:59 としてオフセット付きで組む
        // （ナイーブ連結だと表示時に再度 TZ 変換されて日がずれる。Issue #2508 Phase 2）
        startAt: buildDayStartStr(t.startDate ?? t.dueDate),
        endAt: buildDayEndStr(t.dueDate),
        allDay: true,
        color: t.priority === 'HIGH' ? '#f97316'
          : t.priority === 'LOW' ? '#22c55e'
          : '#3b82f6',
        isPersonal: t.scope.scopeType === 'PERSONAL',
        scopeType: t.scope.scopeType,
        scopeId: t.scope.scopeId ?? undefined,
        scopeName: t.scope.scopeName,
        scopeIconUrl: t.scope.scopeIconUrl,
        isTodo: true,
      }))

    const merged = [...personalEvents, ...sharedEvents, ...reflectionEvents, ...todoEvents]
    extendedEvents.value = merged
    return merged
  }

  const { currentYear, currentMonth, events, loading, calendarLoading, loadEvents, refresh, onPrevMonth, onNextMonth } =
    useCalendarEvents(fetcher, { cacheHalfMonths: 0 })

  const availableScopes = computed<ScopeOption[]>(() => {
    const seen = new Set<string>()
    const result: ScopeOption[] = []
    for (const e of extendedEvents.value) {
      if (!e.scopeType || e.scopeType === 'PERSONAL' || !e.scopeId) continue
      const key = `${e.scopeType}:${e.scopeId}`
      if (!seen.has(key)) {
        seen.add(key)
        result.push({ label: e.scopeName ?? `${e.scopeType} ${e.scopeId}`, value: key, scopeType: e.scopeType, scopeId: e.scopeId as string })
      }
    }
    return result
  })

  const allScopeOptions = computed<ScopeOption[]>(() => [
    { label: '個人', value: PERSONAL_KEY, scopeType: 'PERSONAL', scopeId: '' },
    ...availableScopes.value,
  ])

  const selectedScopes = ref<string[]>([])

  const filteredEvents = computed(() =>
    events.value.filter((e) => {
      // §6.2/AC-21: id は reflection 行で衝突する（id=null/-1）ため uniqueKey でルックアップする。
      const ext = extendedEvents.value.find((x) => x.uniqueKey === e.uniqueKey)
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
    currentYear, currentMonth, events, loading, calendarLoading, loadEvents, refresh, onPrevMonth, onNextMonth,
    extendedEvents, todosFailed, availableScopes, allScopeOptions, selectedScopes, filteredEvents,
    toggleScope, multiSelectScopes, initStorage,
  }
}
