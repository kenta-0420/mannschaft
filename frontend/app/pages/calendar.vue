<script setup lang="ts">
import type { GanttResponse, GanttTodo } from '~/types/todo'
import { useMyCalendarData, PERSONAL_KEY, FILTER_OVERFLOW } from '~/composables/useMyCalendarData'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
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
  onPrevMonth: calPrevMonth, onNextMonth: calNextMonth,
  extendedEvents, todosFailed, layersFailed, availableScopes, allScopeOptions, selectedScopes,
  filteredEvents, toggleScope, multiSelectScopes, initStorage,
} = useMyCalendarData()

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

// 上部セレクト変更でカレンダー表示を絞り込む（ガントタブ表示中は再読み込みも行う）
watch(createScopeKey, (key) => {
  withScopeLoading(() => {
    if (key === 'personal') {
      selectedScopes.value = [PERSONAL_KEY]
    } else {
      selectedScopes.value = [PERSONAL_KEY, key]
    }
  })
  // スコープ変更時はキャッシュを破棄して再取得（ガントビューのみ）
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

async function withScopeLoading(fn: () => void) {
  calendarLoading.value = true
  await nextTick()
  await new Promise<void>(resolve => setTimeout(resolve, 0))
  fn()
  await nextTick()
  calendarLoading.value = false
}

function onToggleScope(value: string) {
  withScopeLoading(() => toggleScope(value))
}

function onMultiSelectChange(vals: string[]) {
  withScopeLoading(() => { selectedScopes.value = vals })
}

async function onTabChange(tab: CalendarTab) {
  activeTab.value = tab
  if (tab === 'gantt') {
    await loadGantt()
  }
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
  await initStorage()
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
      <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <!-- カレンダー（2列） -->
        <div class="lg:col-span-2">
          <div class="relative">
            <DashboardWidgetCard :scrollable="false">
              <CalendarGrid
                :year="currentYear"
                :month="currentMonth"
                :events="filteredEvents"
                @date-click="onDateClick"
                @event-click="onEventClick"
                @reflection-click="onReflectionClick"
                @prev-month="onPrevMonth"
                @next-month="onNextMonth"
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
      :is-personal="selectedCreateScope.isPersonal"
      :scope-options="createScopeOptions.length > 1 ? createScopeOptions : undefined"
      @saved="refresh"
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
