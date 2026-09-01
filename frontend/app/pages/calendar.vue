<script setup lang="ts">
import type { GanttResponse, GanttTodo } from '~/types/todo'
import { useMyCalendarData, PERSONAL_KEY, FILTER_OVERFLOW } from '~/composables/useMyCalendarData'
import type { CalendarViewMode } from '~/composables/useMyCalendarData'
import { dateToOrdinal, shiftDate, todayInTimezone, weekStartOf } from '~/utils/calendarWeek'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
// 週ビューの「今日」判定はユーザー設定タイムゾーンで行う（Codex 検分 [3]）。
const { userTimezone } = useDatetime()
const router = useRouter()
const scheduleApi = useScheduleApi()
const ganttApi = useTodoGantt()
const notification = useNotification()

type CalendarTab = 'calendar' | 'gantt'
const route = useRoute()
const activeTab = ref<CalendarTab>(route.query.tab === 'gantt' ? 'gantt' : 'calendar')

// スコープ変更時にガントビューをフェードで再描画するためのキー
const ganttKey = ref(0)

const showCreateDialog = ref(false)
const showEditDialog = ref(false)
const showGuide = ref(false)
const selectedDate = ref<string | undefined>(undefined)
// F03.19 §6.6.5: 週ビューのグリッド選択で確定した開始・終了（ISO 8601・ユーザー TZ の
// オフセット付き）。日付クリック経路では undefined に戻す（前回のドラッグ値が漏れない・AC-22c）。
const selectedStartAt = ref<string | undefined>(undefined)
const selectedEndAt = ref<string | undefined>(undefined)

// サイドパネル用
const selectedDay = ref<string | null>(null)
const selectedEventId = ref<number | null>(null)
const selectedEventIsPersonal = ref(false)
const showDayPanel = ref(false)
const showEventPanel = ref(false)

// 是正1: 通知リンク（?scheduleId=&commentId=）からの遷移先ハイライト対象（設計書 §6.4）。
const linkedCommentId = ref<string | null>(null)

interface EventDetail {
  id: number
  /** 親 schedules 行の ID（BE CalendarEntryResponse.scheduleId・設計書 §1.5 / AC-07(b)）。null ならコメント欄非表示。 */
  scheduleId?: number | null
  title: string
  description: string | null
  location: string | null
  startAt: string
  endAt: string
  allDay: boolean
  color?: string | null
  scopeType?: string
  scopeId?: string
  scopeName?: string | null
  scopeIconUrl?: string | null
  attendanceRequired?: boolean
  myAttendance?: string | null
  attendanceStats?: { yes: number; no: number; maybe: number; pending: number; total: number } | null
  createdBy?: { displayName: string }
  status?: string
  categoryName?: string | null
  categoryColor?: string | null
  targetMode?: 'ALL_MEMBERS' | 'SELECTED_MEMBERS'
  targetCount?: number
  targets?: Array<{ userId: number; displayName: string; avatarUrl: string | null; calendarColor: string | null }>
}

interface PersonalScheduleRaw {
  id: number
  content: { title: string; description: string | null; eventType: string; color: string | null; location: string | null }
  time: { startAt: string; endAt: string; allDay: boolean }
  status: { status: string; isException: boolean; parentScheduleId: number | null; recurrenceRule: unknown; googleSynced: boolean }
  reminders: number[]
  audit: { createdAt: string; updatedAt: string; createdByDisplayName: string | null }
}

const selectedEvent = ref<EventDetail | null>(null)

const ganttTodos = ref<GanttTodo[]>([])
const ganttFromDate = ref('')
const ganttToDate = ref('')
const ganttLoading = ref(false)

const pad = (n: number) => String(n).padStart(2, '0')

const {
  currentYear, currentMonth, loading, calendarLoading, loadEvents, refresh,
  onPrevMonth: calPrevMonth, onNextMonth: calNextMonth, goToToday, navigateTo,
  extendedEvents, todosFailed, layersFailed, layers, availableScopes, allScopeOptions, selectedScopes,
  filteredEvents, toggleScope, multiSelectScopes, initStorage, view,
} = useMyCalendarData()

// F03.19 §6.8（Wave 3-c）: モバイル（<768px）では常にリスト表示のため md 以上の `view`
// 切り替え UI（週／アジェンダビュー本体）とは別軸だが、「モバイルでは既定ビューをアジェンダ
// とする」という状態の既定値だけはここで切り替える（アジェンダビュー本体の描画自体は
// W3-b の担当で本ファイルではまだ未着手・範囲外）。initStorage() が localStorage に永続化済みの
// 選択（ユーザーが明示的に選んだ view）を復元した場合はそれを尊重し、上書きしない。
const MOBILE_MEDIA_QUERY = '(max-width: 767px)'

/** モバイルのリストビュー用: 表示中の月のイベントを実際の時系列（瞬間）昇順に並べる。
 * ISO 文字列のまま localeCompare すると、時差の異なる予定（例: +09:00 と Z）が
 * 文字列としての大小関係で並んでしまい、実際の前後関係と食い違う（Codex 検分指摘）。
 * 必ず Date.parse で瞬間へ変換してから比較する。 */
const sortedFilteredEvents = computed(() =>
  [...filteredEvents.value].sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt)),
)

// 「今日」ボタン（§6.3・AC-12d）: 月グリッド本体の DOM 操作（フォーカス）は CalendarGrid に委譲する。
const calendarGridRef = ref<{ focusToday: () => void } | null>(null)

async function onToday() {
  goToToday()
  // 週ビューでは「今日」は今週へ戻すことを意味する（§6.5.3）。
  if (isWeekView.value) weekStart.value = weekStartOf(todayDateStr())
  if (activeTab.value === 'gantt') await loadGantt()
  // 月移動時は年月 props が変わってから DOM が再描画されるまで待つ必要がある
  // （既に当月表示中でも focusToday は必ず呼ぶ＝無反応にしない）。
  await nextTick()
  await nextTick()
  if (isWeekView.value) calendarWeekGridRef.value?.focusToday()
  else calendarGridRef.value?.focusToday()
}

// #49-B: 日別一覧
const dayEvents = computed(() => {
  if (!selectedDay.value) return []
  return extendedEvents.value.filter((e) => {
    const start = e.startAt.slice(0, 10)
    const end = e.endAt.slice(0, 10)
    return selectedDay.value! >= start && selectedDay.value! <= end
  })
})

// 日付クリック — チーム・組織カレンダーと同様、常に作成フォームを開く
function onDateClick(date: string) {
  selectedDay.value = date
  selectedDate.value = date
  // AC-22c: 直前に週ビューでドラッグしていても、その時刻を日付クリックへ持ち越さない。
  selectedStartAt.value = undefined
  selectedEndAt.value = undefined
  showEventPanel.value = false
  showDayPanel.value = false
  showCreateDialog.value = true
}

/**
 * 週ビューのグリッド選択確定（§6.6.5）。受け取った時刻をそのまま作成ダイアログへ流し込む。
 * 作成先スコープは既存の `selectedCreateScope`（＋予定追加ボタンと同じ）を使い、導線によって
 * 作成先を変えない。表示フィルタ（`selectedScopes`）には一切触れない（P2）。
 */
function onRangeSelect(startAt: string, endAt: string) {
  selectedStartAt.value = startAt
  selectedEndAt.value = endAt
  selectedDate.value = undefined
  showEventPanel.value = false
  showDayPanel.value = false
  showCreateDialog.value = true
}

/**
 * reflection 印クリック（§6.2/AC-21・id 非依存）。
 * - REFLECTION_RECALL（SPACED 間隔反復）: recall 画面（entry_id 指定）へ遷移。
 * - REFLECTION_PRE_EXAM（考査前総まとめ）: テーマ詳細画面（theme_id 指定）へ遷移。
 * - それ以外（REFLECTION_ENTRY 等）: エントリ詳細へ遷移。
 */
async function onReflectionClick(referenceUuid: string, referenceKind: string) {
  if (referenceKind === 'REFLECTION_RECALL') {
    await router.push(`/reflections/recall?entry=${referenceUuid}`)
  }
  else if (referenceKind === 'REFLECTION_PRE_EXAM') {
    await router.push(`/reflections/themes/${referenceUuid}`)
  }
  else {
    await router.push(`/reflections/entries/${referenceUuid}`)
  }
}

// イベントクリック
async function onEventClick(eventId: number, isPersonal: boolean) {
  // TODO イベントは負数 ID（-(todoId + 1) で格納）
  if (eventId < 0) {
    await router.push(`/todos/${-(eventId + 1)}`)
    return
  }
  try {
    selectedEventId.value = eventId
    selectedEventIsPersonal.value = isPersonal
    if (isPersonal) {
      const res = await scheduleApi.getMyScheduleDetail(eventId)
      const d = res.data as PersonalScheduleRaw
      selectedEvent.value = {
        id: d.id,
        // 個人予定はコメント機能の対象外（設計書 §AC-17。本人からの全 API も 404）。
        // scheduleId を渡すとコメント欄の表示ガードを通過し、空のコメント欄とエラー通知が出る。
        scheduleId: null,
        title: d.content?.title ?? '',
        description: d.content?.description ?? null,
        location: d.content?.location ?? null,
        startAt: d.time?.startAt ?? '',
        endAt: d.time?.endAt ?? '',
        allDay: d.time?.allDay ?? false,
        color: d.content?.color ?? null,
        status: d.status?.status ?? undefined,
        createdBy: d.audit?.createdByDisplayName
          ? { displayName: d.audit.createdByDisplayName }
          : undefined,
      }
    }
    else {
      const ext = extendedEvents.value.find(e => e.id === eventId && !e.isPersonal)
      if (!ext) return
      const st = (ext.scopeType ?? '').toLowerCase() as 'team' | 'organization'
      // P1修繕: 詳細API・画面URLは公開スコープID（slug）を要求する。ext.scopeId は
      // レイヤーキー照合用の数値IDに変わったため、詳細取得には ext.scopeRouteId を使う。
      const sid = ext.scopeRouteId ?? ''
      const res = await scheduleApi.getSchedule(st, sid, eventId)
      const d = res.data as EventDetail & { createdByDisplayName?: string; myAttendanceStatus?: string }
      selectedEvent.value = {
        ...d,
        scheduleId: ext.scheduleId ?? null,
        scopeType: ext.scopeType,
        scopeId: ext.scopeRouteId,
        scopeName: (d as EventDetail).scopeName ?? ext.scopeName,
        scopeIconUrl: (d as EventDetail).scopeIconUrl ?? null,
        createdBy: d.createdByDisplayName ? { displayName: d.createdByDisplayName } : d.createdBy,
        myAttendance: d.myAttendanceStatus ?? null,
        targetMode: d.targetMode ?? ext.targetMode,
        targetCount: d.targetCount ?? ext.targetCount,
        targets: d.targets ?? ext.targets,
      }
    }
    showEventPanel.value = true
    showDayPanel.value = false
  }
  catch {
    // エラーは api 側で処理
  }
}

function onEditEvent() {
  showEventPanel.value = false
  showEditDialog.value = true
}

async function onDeleteEvent() {
  if (!selectedEventId.value || !confirm('この予定を削除しますか？')) return
  try {
    if (selectedEventIsPersonal.value) {
      await scheduleApi.deletePersonalSchedule(selectedEventId.value)
    }
    else {
      const ext = extendedEvents.value.find(e => e.id === selectedEventId.value && !e.isPersonal)
      if (!ext) return
      const st = (ext.scopeType ?? '').toLowerCase() as 'team' | 'organization'
      // P1修繕: 削除APIも公開スコープID（slug）が必要（詳細取得と同じ経路）。
      const sid = ext.scopeRouteId ?? ''
      await scheduleApi.deleteSchedule(st, sid, selectedEventId.value)
    }
    showEventPanel.value = false
    selectedEvent.value = null
    await refresh()
  }
  catch {
    // エラーは api 側で処理
  }
}

async function onSaved() {
  await refresh()
  showEventPanel.value = false
}

// #52: 作成スコープ選択
const createScopeKey = ref<string>('personal')

interface CreateScope {
  label: string
  value: string
  isPersonal: boolean
  scopeType: 'team' | 'organization'
  scopeId: string
}

const createScopeOptions = computed<CreateScope[]>(() => [
  { label: '個人の予定', value: 'personal', isPersonal: true, scopeType: 'team', scopeId: '' },
  ...availableScopes.value.map(sc => ({
    label: sc.label,
    value: sc.value,
    isPersonal: false,
    scopeType: sc.scopeType.toLowerCase() as 'team' | 'organization',
    scopeId: sc.scopeId,
  })),
])

const selectedCreateScope = computed(
  () => createScopeOptions.value.find(o => o.value === createScopeKey.value) ?? createScopeOptions.value[0]!,
)

/**
 * §6.6.6: 週ビューの選択ハイライト色は**作成スコープ**のレイヤー色。
 * 表示フィルタ（`selectedScopes`）は参照しない（(d) との整合・P2）。
 * レイヤーが未取得／該当なしのときは undefined（週ビュー側の既定色にフォールバック）。
 */
const createScopeColor = computed<string | undefined>(() => {
  const key = selectedCreateScope.value.isPersonal
    ? PERSONAL_KEY
    : availableScopes.value.find(sc => sc.value === createScopeKey.value)?.filterKey
  if (!key) return undefined
  return layers.value.find(l => `${l.scopeType}:${l.scopeId}` === key)?.color
})

// AC-11b（§5.4）: 表示フィルタで非表示のレイヤーへ予定を作成すると、作った予定が何の説明も
// 無く現れない（無言で消える＝P3違反）。作成完了時にだけ判定し、案内＋「表示する」ボタンを出す。
// 勝手にフィルタを書き換えない（P2）のが AC-11 の結合切りと表裏一体の要件であり、
// ここでも selectedScopes への代入はボタン押下時（onShowHiddenLayer）のみに限定する。
//
// [P2是正・検分三巡目] 判定対象は「実際に保存されたスコープ」（ScheduleEventForm の
// `saved` イベントが返す値）であって、ページ上部の作成スコープ Select（selectedCreateScope）
// ではない。ScheduleEventForm はフォーム内でもスコープを変更できるため、上部の選択と
// 実際の保存先が食い違いうる（上部=個人のままフォーム内でチームへ変更する等）。
// scopeKey だけ ref に保持し、実際に非表示かどうかは computed で毎回 selectedScopes と
// 突き合わせる（[P3是正] ユーザーがレイヤーチップ等で後から自分で表示に戻したら、
// selectedScopes に含まれた時点で自動的に案内が消える＝「非表示です」と言い続けない）。
const hiddenLayerNoticeScopeKey = ref<string | null>(null)

interface SavedScope {
  isPersonal: boolean
  scopeType: 'team' | 'organization'
  scopeId: string
}

/**
 * 実際に保存されたスコープに対応する selectedScopes 用キー（PERSONAL_KEY または
 * `${SCOPE_TYPE}:数値scopeId`）。
 *
 * F03.19 W2-a との統合修繕: `scope.scopeId`（ScheduleEventForm の保存API呼び出しに使う値）は
 * **slug**（公開スコープID）。一方 `selectedScopes`／`allScopeOptions` は数値スコープIDで
 * キー付けされている（`useMyCalendarData.ts` の `availableScopes` コメント参照 — 作成スコープ選択
 * 専用の slug 値を表示フィルタへ混入させてはならない）。両者を橋渡しするため、
 * `availableScopes`（slug 側）のエントリが持つ `filterKey`（数値キー・useMyCalendarData.ts の
 * `layers.value` 走査時に確定済み）をそのまま使う。
 *
 * [P2是正・Codex検分] 以前は scopeType + 表示名（label）が一致するエントリを
 * `allScopeOptions` から逆引きしていたが、`TeamEntity` にチーム名の一意制約が無いため、
 * 同名の別チーム／組織に複数所属していると `find` が常に先頭の別スコープを誤って返しうる
 * （案内が出ない・「表示する」で別チームが表示される、という AC-11b 違反）。
 * `filterKey` は layers.value の走査中に scopeId（数値）そのものから作られるため、
 * 名前の一意性に依存しない。
 */
function savedScopeFilterKey(scope: SavedScope): string {
  if (scope.isPersonal) return PERSONAL_KEY
  const created = availableScopes.value.find(
    sc => sc.scopeId === scope.scopeId && sc.scopeType.toLowerCase() === scope.scopeType,
  )
  return created?.filterKey ?? `${scope.scopeType.toUpperCase()}:${scope.scopeId}`
}

/** 案内に出すレイヤー表示名。allScopeOptions（表示フィルタと同じ一覧）から引く。 */
function scopeLabelForKey(scopeKey: string): string {
  return allScopeOptions.value.find(o => o.value === scopeKey)?.label ?? scopeKey
}

// 表示条件は「案内対象のスコープキーが設定されており、かつ現在も非表示」の両方（[P3是正]）。
const hiddenLayerNotice = computed(() => {
  const scopeKey = hiddenLayerNoticeScopeKey.value
  if (!scopeKey || selectedScopes.value.includes(scopeKey)) return null
  return { scopeKey, layerLabel: scopeLabelForKey(scopeKey) }
})

// [P2是正・検分四巡目] computed で非表示を導出するだけでは、対象キー（hiddenLayerNoticeScopeKey）
// 自体が保持され続けるため、ユーザーが手で表示に戻した後に同じレイヤーを再び非表示にすると、
// 何も保存していないのに古い案内が「ゾンビ」として復活してしまう。表示に戻った時点で
// 対象キー自体を破棄し、次に非表示にしても案内は出さない（＝新しい保存操作でのみ再び現れる）。
watch(selectedScopes, (val) => {
  const scopeKey = hiddenLayerNoticeScopeKey.value
  if (scopeKey && val.includes(scopeKey)) {
    hiddenLayerNoticeScopeKey.value = null
  }
}, { deep: true })

/** 作成ダイアログの保存完了（新規作成のみ・§5.4/AC-11b）。実際に保存されたスコープで判定する。 */
async function onCreated(scope: SavedScope) {
  const scopeKey = savedScopeFilterKey(scope)
  await refresh()
  hiddenLayerNoticeScopeKey.value = selectedScopes.value.includes(scopeKey) ? null : scopeKey
}

/** 「表示する」ボタン（AC-11b）: 押されたときだけそのレイヤーを表示状態にする。他は一切変更しない。 */
function onShowHiddenLayer() {
  const scopeKey = hiddenLayerNoticeScopeKey.value
  if (!scopeKey) return
  if (!selectedScopes.value.includes(scopeKey)) {
    selectedScopes.value = [...selectedScopes.value, scopeKey]
  }
  hiddenLayerNoticeScopeKey.value = null
}


// 作成スコープ（作成フォームの初期スコープ）と表示フィルタ（selectedScopes）は分離する（§5.4/AC-11）。
// 以前はここで selectedScopes を強制的に書き換えていたが、それだと表示中のレイヤーチップの選択状態が
// 作成スコープの変更につられて勝手に変わってしまう（P2 違反）。作成スコープは createScopeKey /
// selectedCreateScope（作成ダイアログへの引き渡し）にのみ影響させ、表示フィルタには一切触れない。
watch(createScopeKey, () => {
  // スコープ変更時はキャッシュを破棄して再取得（ガントビューのみ・データ取得対象の変更という正当な副作用）
  if (activeTab.value === 'gantt') {
    ganttCache.clear()
    ganttKey.value++
    loadGantt()
  }
})

function getMonthRange(year: number, month: number) {
  const lastDay = new Date(year, month, 0).getDate()
  return {
    from: `${year}-${pad(month)}-01`,
    to: `${year}-${pad(month)}-${pad(lastDay)}`,
  }
}

// ガントデータのキャッシュ（スコープ×年月をキーに保持）
const ganttCache = new Map<string, GanttTodo[]>()

function ganttCacheKey(year: number, month: number): string {
  return `${year}-${pad(month)}-${createScopeKey.value}`
}

async function fetchGanttMonth(year: number, month: number): Promise<GanttTodo[]> {
  const key = ganttCacheKey(year, month)
  if (ganttCache.has(key)) return ganttCache.get(key)!

  const { from, to } = getMonthRange(year, month)
  const scopeKey = createScopeKey.value
  let res: GanttResponse

  if (scopeKey === 'personal') {
    res = await ganttApi.getPersonalGanttTodos(from, to)
  } else {
    const scope = createScopeOptions.value.find(o => o.value === scopeKey)
    if (!scope) return []
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
      // eslint-disable-next-line no-restricted-syntax -- 隣接月の先読み。失敗は移動時に再取得されるため握りつぶすのが正しい（ベストエフォート）
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

  if (ganttCache.has(ganttCacheKey(year, month))) {
    // キャッシュヒット: ローディングなしで即表示
    ganttTodos.value = ganttCache.get(ganttCacheKey(year, month))!
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

// レイヤーチップでの表示絞り込みは filteredEvents（手元データのみ）で完結し、再取得を伴わない
// （AC-12c: 全画面スピナーを一度も出さない・ネットワークリクエストも発生しない）。
// 疑似的な calendarLoading 演出（旧 withScopeLoading）は撤去した。calendarLoading 自体は
// 月移動（本物の通信）のためだけに使う。
function onToggleScope(value: string) {
  toggleScope(value)
}

function onMultiSelectChange(vals: string[]) {
  selectedScopes.value = vals
}

async function onTabChange(tab: CalendarTab) {
  activeTab.value = tab
  if (tab === 'gantt') {
    await loadGantt()
  }
}

// ---- 月/週ビュー切替（F03.19 §6.5.3・AC-13） ----
// `view` の状態そのもの・localStorage への永続化は useMyCalendarData が既に持っている。
// ここではその値に応じて描画コンポーネントを切り替え、週ビューの表示週を管理するだけで、
// **ビュー切替では一切データを取得しない**（filteredEvents を束ね直すだけ・AC-13）。
const calendarWeekGridRef = ref<{ focusToday: () => void } | null>(null)

/**
 * 選択肢は配列で持つ。W3-b でアジェンダ（`agenda`）を1行足すだけで3値へ拡張できる形にしておく。
 */
const viewOptions: Array<{ mode: CalendarViewMode; labelKey: string }> = [
  { mode: 'month', labelKey: 'schedule.calendar.view.month' },
  { mode: 'week', labelKey: 'schedule.calendar.view.week' },
]

// `agenda`（W3-b で実装）が localStorage から復元された場合も、未実装のビューで白画面にせず
// 月ビューへ落とす。W3-b が `agenda` の分岐を足した時点でこの読み替えは不要になる。
const isWeekView = computed(() => view.value === 'week')

/**
 * 「今日」は**ユーザー設定タイムゾーン**で判定する（Codex 検分 [3]）。
 *
 * `goToToday()` はユーザー設定 TZ で月を決めるのに、週の算出だけ端末ローカルの日付を使うと、
 * 両者の日付が土曜と日曜に分かれる時間帯に「今日」ボタンが隣の週を出す。
 * 日付の取得経路を `todayInTimezone` 一本に統一して根から塞ぐ。
 */
function todayDateStr(): string {
  return todayInTimezone(userTimezone.value)
}

const weekStart = ref(weekStartOf(todayDateStr()))

/**
 * 表示中の週が `useCalendarEvents` の取得範囲（表示月の6週グリッド）に収まっているか確認し、
 * 外れていればその週を包含する月へ取得範囲を寄せる（§6.5.3）。
 *
 * 月の6週グリッドは「1日の直前の日曜」から42日分なので、その月に1日でも掛かる週は必ず
 * グリッドに丸ごと収まる。よって週の中日（木曜）が属する月へ寄せれば週全体が確実に含まれる。
 * 既に収まっている場合は何もしない＝無駄な再取得を起こさない。
 */
function ensureRangeCoversWeek(): void {
  const gridStart = dateToOrdinal(weekStartOf(`${currentYear.value}-${pad(currentMonth.value)}-01`))
  const start = dateToOrdinal(weekStart.value)
  if (start >= gridStart && start + 6 <= gridStart + 41) return
  const mid = shiftDate(weekStart.value, 3)
  navigateTo(Number(mid.slice(0, 4)), Number(mid.slice(5, 7)))
}

function onPrevWeek() {
  weekStart.value = shiftDate(weekStart.value, -7)
  ensureRangeCoversWeek()
}

function onNextWeek() {
  weekStart.value = shiftDate(weekStart.value, 7)
  ensureRangeCoversWeek()
}

/**
 * ビュー切替（AC-13）。切替では再取得しない — 表示中の月のグリッドに収まる週を選ぶため、
 * `ensureRangeCoversWeek` は何もしない。
 */
function setView(mode: CalendarViewMode) {
  if (view.value === mode) return
  view.value = mode
  if (mode !== 'week') return
  const today = todayDateStr()
  const inCurrentMonth = Number(today.slice(0, 4)) === currentYear.value
    && Number(today.slice(5, 7)) === currentMonth.value
  weekStart.value = weekStartOf(inCurrentMonth
    ? today
    : `${currentYear.value}-${pad(currentMonth.value)}-01`)
  ensureRangeCoversWeek()
}

function onPrevMonth() {
  calPrevMonth()
  if (activeTab.value === 'gantt') loadGantt()
}

function onNextMonth() {
  calNextMonth()
  if (activeTab.value === 'gantt') loadGantt()
}

/**
 * 是正1【P1】: 通知リンク（`/calendar?scheduleId=<id>&commentId=<uuid>`・
 * {@link ScheduleCommentNotifier#actionUrl} 生成、設計書 §6.4）から遷移した際、該当予定を選択状態にして
 * サイドパネルを開く。既存の {@link onEventClick}（selectedEvent まわり）の流れに倣う（新規の仕組みを作らない）。
 *
 * 対象は現在ロード済みの月（当月）の中から探す。見つからない場合は §6.4 の3項目目に従い、
 * 黙って無視せずトーストで知らせる（症状を隠さない）。ハイライト自体・commentId クエリの除去は
 * {@link ScheduleCommentSection}（`highlighted` イベント）に委譲する（4項目目）。
 */
async function openLinkedScheduleFromQuery() {
  const rawScheduleId = route.query.scheduleId
  if (!rawScheduleId) return
  const targetScheduleId = Number(Array.isArray(rawScheduleId) ? rawScheduleId[0] : rawScheduleId)
  if (!Number.isFinite(targetScheduleId)) return

  const rawCommentId = route.query.commentId
  const targetCommentId = Array.isArray(rawCommentId) ? (rawCommentId[0] ?? null) : (rawCommentId ?? null)

  const ext = extendedEvents.value.find(e => !e.isPersonal && e.scheduleId === targetScheduleId)
  if (!ext) {
    // 当月に無い（別月・削除済み・権限なし）場合の解決手段が今のところ無いため、
    // 見つからない旨をそのまま通知する（黙って通常のカレンダーを開くだけにしない）。
    notification.error(t('schedule.comment.error.notFound'))
    await clearLinkedQuery()
    return
  }

  linkedCommentId.value = targetCommentId
  await onEventClick(ext.id, false)
  if (!targetCommentId) {
    await clearLinkedQuery()
  }
}

/** ハイライト完了後（見つかった／見つからなかった双方）に commentId クエリを除去する（設計書 §6.4 の4項目目）。 */
async function clearLinkedQuery() {
  linkedCommentId.value = null
  const rest = { ...route.query }
  delete rest.scheduleId
  delete rest.commentId
  await router.replace({ query: rest })
}

onMounted(async () => {
  // F03.19 §6.8: 初回訪問（永続化済みの表示設定が無い）かつモバイル幅であれば、
  // 既定ビューを 'agenda' にする。initStorage() 自体は useMyCalendarData.ts 側の
  // 責務（触らない）なので、ここでは「保存済み設定が既にあったか」だけを事前に見て、
  // 無かった場合のみ後から上書きする（ユーザーが既に選んだ view は絶対に上書きしない）。
  const hadPersistedViewState = (() => {
    try {
      // useMyCalendarData.ts の LAYER_STATE_KEY と同じキー（同ファイルは書き換え対象外のため、
      // ここでは存在確認のためだけにキー名を重複させる）。
      return localStorage.getItem('mannschaft:calendar:layerState') != null
    }
    catch {
      return false
    }
  })()
  await initStorage()
  if (!hadPersistedViewState && typeof window !== 'undefined' && window.matchMedia(MOBILE_MEDIA_QUERY).matches) {
    view.value = 'agenda'
  }
  await loadEvents()
  // クエリパラメータ ?tab=gantt で直接ガントタブを開いた場合は初期読み込みを行う
  if (activeTab.value === 'gantt') {
    loadGantt()
  }
  await openLinkedScheduleFromQuery()
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <PageHeader :title="t('schedule.calendar_guide.page_title')" help @help="showGuide = true">
      <!-- #52: スコープ選択 + 予定を追加 -->
      <template #actions>
        <div class="flex items-center gap-2">
          <Select
            v-if="createScopeOptions.length > 1"
            v-model="createScopeKey"
            :options="createScopeOptions"
            option-label="label"
            option-value="value"
            class="text-sm"
            style="min-width: 120px"
          />
          <Button :label="t('schedule.event_add')" icon="pi pi-plus" @click="showCreateDialog = true" />
        </div>
      </template>
    </PageHeader>

    <!-- Issue #2637: TODO レイヤの取得失敗を明示（カレンダー本体は継続表示） -->
    <Message v-if="todosFailed" severity="warn" :closable="false" class="mb-4">
      <span class="font-medium">{{ t('schedule.todo_load_error.summary') }}</span>
      <span class="ml-2">{{ t('schedule.todo_load_error.detail') }}</span>
    </Message>

    <!-- F03.19 P2修繕: レイヤー一覧の取得失敗を明示（予定本体は独立取得のため継続表示） -->
    <Message v-if="layersFailed" severity="warn" :closable="false" class="mb-4">
      <span class="font-medium">{{ t('schedule.calendar.layer.loadError.summary') }}</span>
      <span class="ml-2">{{ t('schedule.calendar.layer.loadError.detail') }}</span>
    </Message>

    <!-- AC-11b（§5.4）: 作成先のレイヤーが表示フィルタで非表示のときの案内。表示するだけで
         フィルタは書き換えない。「表示する」を押したときだけ onShowHiddenLayer が変更する -->
    <HiddenLayerNotice
      v-if="hiddenLayerNotice"
      :layer-label="hiddenLayerNotice.layerLabel"
      class="mb-4"
      @show="onShowHiddenLayer"
    />

    <!-- タブ切替 -->
    <div class="mb-4 flex gap-1 rounded-lg border border-surface-300 bg-surface-100 p-1 dark:border-surface-600 dark:bg-surface-700 w-fit">
      <button
        type="button"
        class="rounded-md px-4 py-1.5 text-sm font-medium transition-colors"
        :class="activeTab === 'calendar'
          ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
          : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'"
        @click="onTabChange('calendar')"
      >
        <i class="pi pi-calendar mr-1.5" />カレンダー
      </button>
      <button
        type="button"
        class="rounded-md px-4 py-1.5 text-sm font-medium transition-colors"
        :class="activeTab === 'gantt'
          ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
          : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'"
        @click="onTabChange('gantt')"
      >
        <i class="pi pi-bars mr-1.5" />{{ t('todo.enhancement.gantt.title') }}
      </button>
    </div>

    <!-- カレンダービュー -->
    <div v-show="activeTab === 'calendar'">
      <!-- ===== モバイル（<768px）: リストビュー（F03.19 §6.8・Wave 3-c） =====
           狭幅では月グリッドが読めないため、共通コンポーネント ScheduleMobileListView を使う。
           週／アジェンダビュー本体（CalendarWeekGrid・CalendarAgendaList）は別戦役（W3-a/W3-b）の
           担当でまだ存在しないため、モバイルの表示はここでは常にこのリストで代替する。 -->
      <div class="md:hidden">
        <ScheduleMobileListView
          :year="currentYear"
          :month="currentMonth"
          :events="sortedFilteredEvents"
          scope-type="team"
          scope-id=""
          :empty-message="t('schedule.calendar.empty')"
          :dimmed="calendarLoading"
          @prev-month="onPrevMonth"
          @next-month="onNextMonth"
          @open="(ev) => (ev.isReflection && ev.referenceUuid && ev.referenceKind)
            ? onReflectionClick(ev.referenceUuid, ev.referenceKind)
            : onEventClick(ev.id, ev.isPersonal)"
          @responded="refresh"
        />
      </div>

      <!-- ===== デスクトップ（768px以上）: 従来のカレンダー主体UI（不変） ===== -->
      <div class="hidden gap-6 md:grid grid-cols-1 lg:grid-cols-3">
        <!-- カレンダー（2列） -->
        <div class="lg:col-span-2">
          <div class="relative">
            <DashboardWidgetCard :scrollable="false">
              <!-- 月/週ビュー切替（§6.5.3・AC-13）。W3-b でアジェンダを viewOptions に足すだけで3値になる。 -->
              <div class="mb-3 flex w-fit gap-1 rounded-lg border border-surface-300 bg-surface-100 p-0.5 dark:border-surface-600 dark:bg-surface-700">
                <button
                  v-for="opt in viewOptions"
                  :key="opt.mode"
                  type="button"
                  :data-testid="`calendar-view-${opt.mode}`"
                  :aria-pressed="view === opt.mode"
                  class="rounded-md px-3 py-1 text-xs font-medium transition-colors"
                  :class="view === opt.mode
                    ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
                    : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'"
                  @click="setView(opt.mode)"
                >
                  {{ t(opt.labelKey) }}
                </button>
              </div>
              <CalendarWeekGrid
                v-if="isWeekView"
                ref="calendarWeekGridRef"
                :week-start="weekStart"
                :events="filteredEvents"
                :create-scope-color="createScopeColor"
                @event-click="onEventClick"
                @reflection-click="onReflectionClick"
                @range-select="onRangeSelect"
                @prev-week="onPrevWeek"
                @next-week="onNextWeek"
                @today="onToday"
              />
              <CalendarGrid
                v-else
                ref="calendarGridRef"
                :year="currentYear"
                :month="currentMonth"
                :events="filteredEvents"
                show-today-button
                @date-click="onDateClick"
                @event-click="onEventClick"
                @reflection-click="onReflectionClick"
                @prev-month="onPrevMonth"
                @next-month="onNextMonth"
                @today="onToday"
              />
            </DashboardWidgetCard>
            <Transition name="fade">
              <div
                v-if="calendarLoading"
                class="absolute inset-0 flex items-center justify-center rounded-xl bg-surface-0/70 dark:bg-surface-900/70 z-10"
              >
                <ProgressSpinner style="width: 40px; height: 40px" stroke-width="4" />
              </div>
            </Transition>
          </div>

          <!-- 凡例 + フィルタ -->
          <div class="mt-4 flex flex-wrap items-center gap-4 text-xs text-surface-500">
            <span><span class="mr-1 inline-block h-3 w-3 rounded-full bg-green-500" />個人</span>
            <span><span class="mr-1 inline-block h-3 w-3 rounded-full bg-indigo-500" />チーム/組織</span>
            <!-- #51: スコープフィルタ（個人含む全スコープ） -->
            <div v-if="allScopeOptions.length > 0" class="flex gap-2 flex-wrap items-center">
              <span class="text-xs text-surface-400">表示:</span>

              <!-- ≤5件: 横並びトグルボタン -->
              <template v-if="allScopeOptions.length <= FILTER_OVERFLOW">
                <button
                  v-for="sc in allScopeOptions"
                  :key="sc.value"
                  type="button"
                  class="text-xs px-2 py-0.5 rounded-full border transition-colors"
                  :class="selectedScopes.includes(sc.value)
                    ? 'border-primary text-primary bg-primary/10'
                    : 'border-surface-300 text-surface-400'"
                  @click="onToggleScope(sc.value)"
                >
                  {{ sc.label }}
                </button>
              </template>

              <!-- 6件以上: MultiSelect ドロップダウン -->
              <MultiSelect
                v-else
                :model-value="multiSelectScopes"
                :options="allScopeOptions"
                option-label="label"
                option-value="value"
                :placeholder="t('schedule.filter.allTeamsOrgs')"
                :max-selected-labels="2"
                selected-items-label="{0}件選択中"
                class="text-xs"
                style="min-width: 180px"
                @update:model-value="onMultiSelectChange"
              />
            </div>
          </div>
        </div>

        <!-- サイドパネル（1列） -->
        <div class="lg:col-span-1">
          <!-- イベント詳細パネル -->
          <SectionCard v-if="showEventPanel && selectedEvent">
            <EventDetailPanel
              :event="{
                id: selectedEvent.id,
                scheduleId: selectedEvent.scheduleId ?? null,
                title: selectedEvent.title,
                description: selectedEvent.description,
                location: selectedEvent.location,
                startAt: selectedEvent.startAt,
                endAt: selectedEvent.endAt,
                allDay: selectedEvent.allDay,
                status: selectedEvent.status ?? 'PUBLISHED',
                categoryName: selectedEvent.categoryName ?? null,
                categoryColor: selectedEvent.categoryColor ?? null,
                createdBy: selectedEvent.createdBy ?? { displayName: '' },
                attendanceRequired: selectedEvent.attendanceRequired ?? false,
                myAttendance: selectedEvent.myAttendance ?? null,
                attendanceStats: selectedEvent.attendanceStats ?? null,
                targetMode: selectedEvent.targetMode,
                targetCount: selectedEvent.targetCount,
                targets: selectedEvent.targets,
              }"
              :scope-type="selectedEventIsPersonal ? 'team' : ((selectedEvent.scopeType ?? '').toLowerCase() as 'team' | 'organization')"
              :scope-id="selectedEvent.scopeId ?? ''"
              :can-edit="true"
              :skip-delegations="selectedEventIsPersonal"
              :scope-name="selectedEvent.scopeName ?? null"
              :scope-icon-url="selectedEvent.scopeIconUrl ?? null"
              :show-audience="!selectedEventIsPersonal"
              :highlight-comment-id="linkedCommentId"
              @edit="onEditEvent"
              @delete="onDeleteEvent"
              @responded="refresh"
              @comment-highlighted="clearLinkedQuery"
            />
          </SectionCard>

          <!-- 日別一覧パネル -->
          <SectionCard v-else-if="showDayPanel && selectedDay">
            <div class="space-y-3">
              <div class="flex items-center justify-between">
                <h3 class="font-bold text-sm">{{ selectedDay }} の予定</h3>
                <Button icon="pi pi-plus" size="small" text @click="showCreateDialog = true" />
              </div>
              <div v-if="dayEvents.length === 0" class="text-sm text-surface-400 text-center py-4">
                予定はありません
              </div>
              <div
                v-for="ev in dayEvents"
                :key="ev.uniqueKey"
                class="cursor-pointer rounded-lg p-2 hover:bg-surface-100 dark:hover:bg-surface-700 border border-surface-200 dark:border-surface-600"
                @click="ev.isReflection && ev.referenceUuid && ev.referenceKind
                  ? onReflectionClick(ev.referenceUuid, ev.referenceKind)
                  : onEventClick(ev.id, ev.isPersonal)"
              >
                <div class="flex items-center gap-2">
                  <span class="h-2 w-2 rounded-full flex-shrink-0" :style="{ backgroundColor: ev.color ?? '#6366f1' }" />
                  <span class="text-sm font-medium truncate">{{ ev.title }}</span>
                </div>
                <div v-if="!ev.allDay" class="text-xs text-surface-400 mt-0.5 pl-4">
                  {{ ev.startAt.slice(11, 16) }} - {{ ev.endAt.slice(11, 16) }}
                </div>
              </div>
            </div>
          </SectionCard>

          <!-- 空状態 -->
          <SectionCard v-else>
            <DashboardEmptyState icon="pi pi-calendar" message="日付またはイベントを選択してください" />
          </SectionCard>
        </div>
      </div>
    </div>

    <!-- TODOガントビュー -->
    <div v-show="activeTab === 'gantt'">
      <DashboardWidgetCard :scrollable="false">
        <div v-if="ganttLoading" class="space-y-3">
          <Skeleton v-for="i in 5" :key="i" height="2rem" />
        </div>
        <Transition v-else name="fade">
          <TodoGanttView
            :key="ganttKey"
            :todos="ganttTodos"
            :from-date="ganttFromDate"
            :to-date="ganttToDate"
            :current-year="currentYear"
            :current-month="currentMonth"
            @todo-click="(id) => router.push(`/todos/${id}`)"
            @prev-month="onPrevMonth"
            @next-month="onNextMonth"
          />
        </Transition>
      </DashboardWidgetCard>
    </div>

    <!-- 作成ダイアログ -->
    <ScheduleEventForm
      v-model:visible="showCreateDialog"
      :scope-type="selectedCreateScope.scopeType"
      :scope-id="selectedCreateScope.scopeId"
      :initial-date="selectedDate"
      :initial-start-at="selectedStartAt"
      :initial-end-at="selectedEndAt"
      :is-personal="selectedCreateScope.isPersonal"
      :scope-options="createScopeOptions.length > 1 ? createScopeOptions : undefined"
      @saved="onCreated"
    />

    <!-- 編集ダイアログ -->
    <ScheduleEventForm
      v-if="selectedEvent && selectedEventId"
      v-model:visible="showEditDialog"
      :scope-type="selectedEventIsPersonal ? 'team' : ((selectedEvent?.scopeType ?? '').toLowerCase() as 'team' | 'organization')"
      :scope-id="selectedEvent?.scopeId ?? ''"
      :schedule-id="selectedEventId"
      :is-personal="selectedEventIsPersonal"
      @saved="onSaved"
    />

    <!-- 使い方モーダル -->
    <CalendarGuideModal v-model:visible="showGuide" />
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.25s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
