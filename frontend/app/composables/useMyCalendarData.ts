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
    // F03.19 §4.6: BE が解決済みの表示色（LAYER_USER > SCHEDULE > CATEGORY > LAYER_AUTO の優先順位で決定済み）。
    color?: string | null
  }
  time: { startAt: string; endAt: string; allDay: boolean }
  scope: { scopeType: string; scopeId: string; scopeName: string | null; scopeIconUrl: string | null; scopeSlug?: string | null }
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
  /**
   * レイヤーキー照合用の**数値スコープID**（文字列化）。レイヤー API（`/me/calendar-layers`）の
   * `scopeId` と同じ形式で揃える。詳細API・画面URL用の識別子とは別物 — {@link CalEvent.scopeRouteId} を使うこと
   * （P1修繕: 旧実装はここに slug を格納しており、レイヤーの数値 scopeId と永久に一致しなかった）。
   */
  scopeId?: string
  /** 詳細API・画面URLに渡す公開スコープID（slug があれば slug、無ければ数値IDへフォールバック）。 */
  scopeRouteId?: string
  scopeIconUrl?: string | null
  isTodo?: boolean
}

export interface ScopeOption {
  label: string
  value: string
  scopeType: string
  scopeId: string
}

/** `/me/calendar-layers` 応答を正規化した1レイヤー分の表示情報（F03.19 §4.3）。 */
export interface CalendarLayerView {
  scopeType: string
  scopeId: number
  scopeName: string
  scopeNameKey: string | null
  scopeIconUrl: string | null
  color: string
  colorSource: string
  hidden: boolean
}

interface CalendarLayerRaw {
  scopeType?: string
  scopeId?: number
  scopeName?: string
  scopeNameKey?: string
  scopeIconUrl?: string
  color?: string
  colorSource?: string
  hidden?: boolean
}

function normalizeLayer(raw: CalendarLayerRaw): CalendarLayerView {
  return {
    scopeType: raw.scopeType ?? 'PERSONAL',
    scopeId: raw.scopeId ?? 0,
    scopeName: raw.scopeName ?? '',
    scopeNameKey: raw.scopeNameKey ?? null,
    scopeIconUrl: raw.scopeIconUrl ?? null,
    color: raw.color ?? '#94a3b8',
    colorSource: raw.colorSource ?? 'LAYER_AUTO',
    hidden: raw.hidden ?? false,
  }
}

/** F03.19 §4.3.1: PERSONAL の scopeId は DB・API・URL・FE レイヤーキーのすべてで 0 に統一する。 */
export const PERSONAL_KEY = 'PERSONAL:0'
export const FILTER_OVERFLOW = 5

/** F03.19 §5.3: localStorage の新スキーマ・旧キー2種。 */
const LAYER_STATE_KEY = 'mannschaft:calendar:layerState'
const LEGACY_CALENDAR_KEY = 'mannschaft:calendar:scopeFilter'
const LEGACY_WIDGET_KEY = 'mannschaft:widget:calendar:scopeFilter'
type CalendarViewMode = 'month' | 'week' | 'agenda'

interface LayerStateV2 {
  version: 2
  selected: string[]
  view: CalendarViewMode
  /**
   * P2修繕: これまでに一度でも自動選択したフォールバックチップのキー（§5.2.1）。
   * 「初めて現れたときだけ既定選択にする」の判定に使う。ユーザーが明示的に外した後、
   * 月移動・再取得・リロードのたびに selectedScopes へ強制で戻されるのを防ぐため、
   * selectedScopes とは独立に永続化する（一度知ったキーは知ったままにする）。
   */
  knownFallbackKeys: string[]
}

function isValidView(v: unknown): v is CalendarViewMode {
  return v === 'month' || v === 'week' || v === 'agenda'
}

/** 旧キー値 "PERSONAL" を新形式 "PERSONAL:0" へ読み替える（§4.3.1）。 */
function migrateLegacyScopeValue(value: string): string {
  return value === 'PERSONAL' ? PERSONAL_KEY : value
}

/**
 * 【R15 裁定】旧 localStorage キーから新キーへの移行を「セッション中に1度だけ」実行する。
 * `/calendar` とウィジェットの双方が同じ `useMyCalendarData` を共有するため、
 * モジュールスコープのフラグで多重実行を防ぐ（片方が先に旧キーを消すと、もう片方が
 * 初期化フォールバックに落ちて選択状態を失う事故を防止する・設計書 R15）。
 */
let legacyStorageMigrated = false

function migrateLegacyStorage(): void {
  if (legacyStorageMigrated) return
  legacyStorageMigrated = true
  try {
    if (localStorage.getItem(LAYER_STATE_KEY)) return // 新キーが既にあれば移行不要

    // 両方の旧キーが併存する場合は必ず `/calendar` 側（LEGACY_CALENDAR_KEY）を採用する（R15）。
    const calendarRaw = localStorage.getItem(LEGACY_CALENDAR_KEY)
    const widgetRaw = localStorage.getItem(LEGACY_WIDGET_KEY)
    const source = calendarRaw ?? widgetRaw
    if (source == null) return

    let parsed: unknown = null
    try { parsed = JSON.parse(source) }
    catch { parsed = null }

    if (Array.isArray(parsed)) {
      const migrated: LayerStateV2 = {
        version: 2,
        selected: parsed.filter((v): v is string => typeof v === 'string').map(migrateLegacyScopeValue),
        view: 'month',
        knownFallbackKeys: [],
      }
      localStorage.setItem(LAYER_STATE_KEY, JSON.stringify(migrated))
    }
    // 併存有無に関わらず両方の旧キーを削除する（両系統が残って再分裂するのを防ぐ）。
    localStorage.removeItem(LEGACY_CALENDAR_KEY)
    localStorage.removeItem(LEGACY_WIDGET_KEY)
  }
  catch {
    // localStorage が使えない環境（プライベートモード等）。移行できないだけで、
    // これは握りつぶしではなく「新規訪問と同じ扱いで進める」という明示的な劣化。
  }
}

/** テスト専用: 「セッション中1度だけ」のモジュールスコープ移行フラグをリセットする（AC-15b3）。 */
export function __resetCalendarLayerMigrationForTest(): void {
  legacyStorageMigrated = false
}

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

/** 共有予定の詳細API・画面URLに渡す公開スコープIDを選ぶ。旧応答は内部IDへフォールバックする。 */
export function resolveCalendarScopeRouteId(scope: CalendarEntryRaw['scope']): string {
  return scope?.scopeSlug ?? String(scope?.scopeId ?? '')
}

export function useMyCalendarData(_options?: { storageKey?: string }) {
  const scheduleApi = useScheduleApi()
  const ganttApi = useTodoGantt()
  const { buildDayStartStr, buildDayEndStr } = useDatetime()
  const errorHandler = useErrorHandler()
  const errorReport = useErrorReport()
  const authStore = useAuthStore()
  const { t } = useI18n()

  const extendedEvents = ref<CalEvent[]>([])
  /** `/me/calendar-layers` 由来のレイヤー一覧。events とは独立に取得し、月移動では再取得しない（AC-03）。 */
  const layers = ref<CalendarLayerView[]>([])
  const layersLoaded = ref(false)
  /** P2修繕: レイヤー一覧の取得に失敗したか（部分失敗をユーザーと利用側に見せる。todosFailed と同じ流儀）。 */
  const layersFailed = ref(false)
  const view = ref<CalendarViewMode>('month')
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
        // F03.19 §4.6/§5.2: BE が解決済みの色を使う（FE でハッシュ計算しない）。
        // 未デプロイ環境等で content.color が来ない場合のみ null へフォールバックする（明示的な劣化）。
        color: e.content?.color ?? null,
        isPersonal: false,
        scopeType: e.scope?.scopeType ?? '',
        // P1修繕: scopeId は必ずレイヤー API と同じ「数値ID文字列」にする（フィルタ照合用）。
        // slug が要るのは詳細API・画面URLの経路のみで、それは scopeRouteId 側に分離した。
        scopeId: e.scope?.scopeId != null ? String(e.scope.scopeId) : undefined,
        // 詳細API・画面URLは内部数値IDではなく公開slugを要求する。
        scopeRouteId: resolveCalendarScopeRouteId(e.scope),
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
        isPersonal: t.scopeType === 'PERSONAL',
        scopeType: t.scopeType,
        // TODO は元々レイヤー API と同じ数値IDを持つ（MyCalendarTodo.scopeId: number）。
        scopeId: t.scopeId == null ? undefined : String(t.scopeId),
        scopeRouteId: t.scopeSlug ?? (t.scopeId == null ? undefined : String(t.scopeId)),
        scopeName: t.scopeName,
        scopeIconUrl: null,
        isTodo: true,
      }))

    const merged = [...personalEvents, ...sharedEvents, ...reflectionEvents, ...todoEvents]
    extendedEvents.value = merged
    return merged
  }

  const { currentYear, currentMonth, events, loading, calendarLoading, loadEvents, refresh, onPrevMonth, onNextMonth } =
    useCalendarEvents(fetcher, { cacheHalfMonths: 0 })

  /**
   * レイヤー一覧の取得（§5.1）。events とは独立。月移動では呼ばない（AC-03）。
   *
   * P2修繕: 失敗しても re-throw しない。設計書 §5.1/AC-03 は layers と events を独立取得する
   * 方針であり、レイヤー取得の失敗で予定の取得（loadEvents）まで止まってはならない
   * （呼び出し元 initStorage が reject すると calendar.vue の onMounted が後続の
   * loadEvents を一度も呼ばずに終わり、取得できるはずの予定まで空になっていた）。
   * ただし握りつぶしはしない — errorHandler へ委譲して報告し、layersFailed で
   * 利用側（画面）に部分失敗を明示できるようにする（todosFailed と同じ流儀）。
   */
  async function loadLayers(): Promise<void> {
    try {
      const res = await scheduleApi.getMyCalendarLayers()
      layers.value = ((res.data ?? []) as unknown as CalendarLayerRaw[]).map(normalizeLayer)
      layersLoaded.value = true
      layersFailed.value = false
    }
    catch (e) {
      layersFailed.value = true
      errorHandler.handleApiError(e, 'useMyCalendarData.getMyCalendarLayers')
      // layersLoaded は false のまま。layers.value は空配列のまま（初期値）で、
      // 呼び出し元（initStorage）はレイヤー情報無しの前提で続行できる。
    }
  }

  function layerKey(scopeType: string, scopeId: number | string): string {
    return `${scopeType}:${scopeId}`
  }

  function layerLabel(l: CalendarLayerView): string {
    if (l.scopeType === 'PERSONAL' && l.scopeNameKey) return t(l.scopeNameKey)
    return l.scopeName || t('schedule.calendar.layer.unknown')
  }

  /**
   * 作成スコープ選択 UI（`createScopeOptions`）向けの候補。
   *
   * P1修繕: レイヤー API（`/me/calendar-layers`）は slug を持たないため、ここをレイヤー由来に
   * すると `scheduleApi.createSchedule`/`updateSchedule` が要求する公開スコープID（slug）と
   * 数値IDが混同され、予定作成・編集が壊れる。よって従来どおり events 由来の scopeRouteId を
   * 使う（§5.2 の「レイヤー一覧を BE から取る」対象はフィルタ用の allScopeOptions のみで、
   * 作成スコープ選択の候補源はこの戦役の変更対象外）。
   */
  const availableScopes = computed<ScopeOption[]>(() => {
    const seen = new Set<string>()
    const result: ScopeOption[] = []
    for (const e of extendedEvents.value) {
      if (!e.scopeType || e.scopeType === 'PERSONAL' || !e.scopeRouteId) continue
      const key = `${e.scopeType}:${e.scopeRouteId}`
      if (seen.has(key)) continue
      seen.add(key)
      result.push({ label: e.scopeName ?? `${e.scopeType} ${e.scopeRouteId}`, value: key, scopeType: e.scopeType, scopeId: e.scopeRouteId })
    }
    return result
  })

  /**
   * レイヤー一覧に存在しないスコープの予定を集めたフォールバックチップ（§5.2.1・AC-23）。
   * 「イベントが1件でも存在するスコープには必ず対応するチップが存在する」不変条件を守る。
   */
  const layerKeySet = computed(() => new Set(layers.value.map((l) => layerKey(l.scopeType, l.scopeId))))

  function eventLayerKey(ext: CalEvent): string {
    if (ext.isPersonal || ext.scopeType === 'PERSONAL') return PERSONAL_KEY
    return layerKey(ext.scopeType ?? '', ext.scopeId ?? '')
  }

  const fallbackScopeOptions = computed<ScopeOption[]>(() => {
    if (!layersLoaded.value) return []
    const seen = new Set<string>()
    const result: ScopeOption[] = []
    for (const e of extendedEvents.value) {
      const key = eventLayerKey(e)
      if (key === PERSONAL_KEY || layerKeySet.value.has(key) || seen.has(key)) continue
      seen.add(key)
      result.push({
        label: e.scopeName ?? t('schedule.calendar.layer.unknown'),
        value: key,
        scopeType: e.scopeType ?? '',
        scopeId: e.scopeId ?? '',
      })
    }
    return result
  })

  /** レイヤー一覧（BE 由来・予定の有無に依存しない）＋フォールバックチップ（§5.1/§5.2.1）。 */
  const allScopeOptions = computed<ScopeOption[]>(() => [
    ...layers.value.map((l) => ({ label: layerLabel(l), value: layerKey(l.scopeType, l.scopeId), scopeType: l.scopeType, scopeId: String(l.scopeId) })),
    ...fallbackScopeOptions.value,
  ])

  const selectedScopes = ref<string[]>([])
  /** P2修繕: 自動選択済みフォールバックキーの記録（§5.2.1）。initStorage で永続化状態から復元する。 */
  const knownFallbackKeys = ref<Set<string>>(new Set())

  // §5.2: extendedEvents を uniqueKey で索引化し、filteredEvents は O(N) の Map 参照にする
  // （旧実装はイベント1件ごとに extendedEvents を線形探索する O(N²) だった）。
  // extendedEvents の配列自体は既存利用箇所（CalendarGrid 等）のため残す。
  const eventsByUniqueKey = computed(() => {
    const map = new Map<string, CalEvent>()
    for (const e of extendedEvents.value) map.set(e.uniqueKey, e)
    return map
  })

  const filteredEvents = computed(() =>
    events.value.filter((e) => {
      // §6.2/AC-21: id は reflection 行で衝突する（id=null/-1）ため uniqueKey でルックアップする。
      const ext = eventsByUniqueKey.value.get(e.uniqueKey)
      if (!ext) return false
      return selectedScopes.value.includes(eventLayerKey(ext))
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

  function persistLayerState(): void {
    try {
      const payload: LayerStateV2 = {
        version: 2,
        selected: selectedScopes.value,
        view: view.value,
        knownFallbackKeys: [...knownFallbackKeys.value],
      }
      localStorage.setItem(LAYER_STATE_KEY, JSON.stringify(payload))
    }
    catch { /* localStorage 不可時は永続化を諦めるが、選択状態そのものはメモリ上で機能し続ける */ }
  }

  watch(selectedScopes, persistLayerState, { deep: true })
  watch(view, persistLayerState)

  // §5.2.1: レイヤー一覧に無いフォールバックチップは「初めて現れたときだけ」既定選択にする（AC-23）。
  // P2修繕: knownFallbackKeys に無いキーだけを新規とみなす。一度選択したキーをユーザーが
  // 明示的に外した後、月移動・再取得のたびに selectedScopes へ戻すと「外せない」バグになる。
  watch(fallbackScopeOptions, (opts) => {
    const newlySeen = opts.map((o) => o.value).filter((v) => !knownFallbackKeys.value.has(v))
    if (!newlySeen.length) return
    for (const v of newlySeen) knownFallbackKeys.value.add(v)
    selectedScopes.value = [...selectedScopes.value, ...newlySeen]
    persistLayerState() // knownFallbackKeys の更新も即座に永続化する（selectedScopes の watch と二重書きにはなるが冪等）
  })

  /**
   * localStorage 初期化（§5.3）。レイヤー一覧を（未取得なら）取得したうえで、
   * 1) 新キーがあればそれを使う 2) 無ければ旧キーから移行する 3) どちらも無ければ
   * `hidden=false` のレイヤーを全選択する（AC-15c）。移行そのものは
   * migrateLegacyStorage 内でセッション中1度だけに限定される（R15）。
   */
  async function initStorage(): Promise<void> {
    if (!layersLoaded.value) await loadLayers()

    migrateLegacyStorage()

    try {
      const raw = localStorage.getItem(LAYER_STATE_KEY)
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<LayerStateV2> | string[]
        // 移行直後を含め、新形式 { version, selected, view } を前提とする。
        const selected = Array.isArray(parsed) ? parsed : parsed.selected
        if (Array.isArray(selected)) {
          selectedScopes.value = selected.map(migrateLegacyScopeValue)
          const parsedView = Array.isArray(parsed) ? undefined : parsed.view
          view.value = isValidView(parsedView) ? parsedView : 'month'
          // P2修繕: 永続化済みの knownFallbackKeys を復元する。復元し損ねると、リロードのたびに
          // 「初めて見た」扱いになり、ユーザーが外したフォールバックチップが復活してしまう。
          const parsedKnown = Array.isArray(parsed) ? undefined : parsed.knownFallbackKeys
          knownFallbackKeys.value = new Set(Array.isArray(parsedKnown) ? parsedKnown.filter((v): v is string => typeof v === 'string') : [])
          return
        }
      }
    }
    catch { /* 壊れたデータは初期化フォールバックへ */ }

    // 初回訪問、または localStorage が壊れている場合: hidden=false のレイヤーを全選択（P1・AC-15c）。
    selectedScopes.value = layers.value.filter((l) => !l.hidden).map((l) => layerKey(l.scopeType, l.scopeId))
    view.value = 'month'
  }

  return {
    currentYear, currentMonth, events, loading, calendarLoading, loadEvents, refresh, onPrevMonth, onNextMonth,
    extendedEvents, todosFailed, availableScopes, allScopeOptions, selectedScopes, filteredEvents,
    toggleScope, multiSelectScopes, initStorage,
    layers, layersLoaded, layersFailed, loadLayers, view,
  }
}
