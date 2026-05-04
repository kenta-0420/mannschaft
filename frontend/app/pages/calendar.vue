<script setup lang="ts">
import type { GanttTodo } from '~/types/todo'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const router = useRouter()
const scheduleApi = useScheduleApi()
const ganttApi = useTodoGantt()

type CalendarTab = 'calendar' | 'gantt'
const activeTab = ref<CalendarTab>('calendar')

const showCreateDialog = ref(false)
const showEditDialog = ref(false)
const selectedDate = ref<string | undefined>(undefined)

// サイドパネル用
const selectedDay = ref<string | null>(null)
const selectedEventId = ref<number | null>(null)
const selectedEventIsPersonal = ref(false)
const showDayPanel = ref(false)
const showEventPanel = ref(false)

interface EventDetail {
  id: number
  title: string
  description: string | null
  location: string | null
  startAt: string
  endAt: string
  allDay: boolean
  color?: string | null
  scopeType?: string
  scopeId?: number
  scopeName?: string | null
  myAttendance?: string | null
  attendanceStats?: { yes: number; no: number; maybe: number; pending: number; total: number } | null
  createdBy?: { displayName: string }
  status?: string
  categoryName?: string | null
  categoryColor?: string | null
}

const selectedEvent = ref<EventDetail | null>(null)

const ganttTodos = ref<GanttTodo[]>([])
const ganttFromDate = ref('')
const ganttToDate = ref('')
const ganttLoading = ref(false)

const pad = (n: number) => String(n).padStart(2, '0')

interface CalEvent {
  id: number
  title: string
  startAt: string
  endAt: string
  allDay: boolean
  color: string | null
  scopeType: string
  scopeId?: number
  scopeName?: string | null
  isPersonal: boolean
}

// events を CalEvent として拡張して保持するための ref
const extendedEvents = ref<CalEvent[]>([])

const fetcher = async (from: string, to: string) => {
  const year = parseInt(from.slice(0, 4), 10)
  const month = parseInt(from.slice(5, 7), 10)
  const [personal, shared] = await Promise.all([
    scheduleApi.listPersonalSchedules({ from, to }),
    scheduleApi.getCalendarMonth(year, month),
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
  }))
  const merged = [...personalEvents, ...sharedEvents]
  extendedEvents.value = merged
  return merged
}

const { currentYear, currentMonth, events, loading, loadEvents, refresh, onPrevMonth: calPrevMonth, onNextMonth: calNextMonth } =
  useCalendarEvents(fetcher, { cacheHalfMonths: 0 })

// #51: スコープフィルタ
interface ScopeOption {
  label: string
  value: string
  scopeType: string
  scopeId: number
}

const availableScopes = computed<ScopeOption[]>(() => {
  const seen = new Set<string>()
  const result: ScopeOption[] = []
  for (const e of extendedEvents.value) {
    const st = e.scopeType
    const sid = e.scopeId
    const name = e.scopeName
    if (!st || st === 'PERSONAL' || !sid) continue
    const key = `${st}:${sid}`
    if (!seen.has(key)) {
      seen.add(key)
      result.push({ label: name ?? `${st} ${sid}`, value: key, scopeType: st, scopeId: sid })
    }
  }
  return result
})

const selectedScopes = ref<string[]>([])

const filteredEvents = computed(() => {
  if (selectedScopes.value.length === 0) return events.value
  return events.value.filter(e => {
    const ext = extendedEvents.value.find(x => x.id === e.id && x.isPersonal === e.isPersonal)
    if (!ext || ext.scopeType === 'PERSONAL' || ext.isPersonal) return true
    const key = `${ext.scopeType}:${ext.scopeId}`
    return selectedScopes.value.includes(key)
  })
})

function toggleScope(value: string) {
  const idx = selectedScopes.value.indexOf(value)
  if (idx >= 0) {
    selectedScopes.value.splice(idx, 1)
  }
  else {
    selectedScopes.value.push(value)
  }
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

// 日付クリック
function onDateClick(date: string) {
  selectedDay.value = date
  showDayPanel.value = true
  showEventPanel.value = false
  selectedDate.value = date
}

// イベントクリック
async function onEventClick(eventId: number, isPersonal: boolean) {
  try {
    selectedEventId.value = eventId
    selectedEventIsPersonal.value = isPersonal
    if (isPersonal) {
      const res = await scheduleApi.getMyScheduleDetail(eventId)
      selectedEvent.value = res.data as EventDetail
    }
    else {
      const ext = extendedEvents.value.find(e => e.id === eventId && !e.isPersonal)
      if (!ext) return
      const st = (ext.scopeType ?? '').toLowerCase() as 'team' | 'organization'
      const sid = ext.scopeId ?? 0
      const res = await scheduleApi.getSchedule(st, sid, eventId)
      const d = res.data as EventDetail
      selectedEvent.value = { ...d, scopeType: ext.scopeType, scopeId: ext.scopeId, scopeName: ext.scopeName }
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
      const sid = ext.scopeId ?? 0
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
  scopeId: number
}

const createScopeOptions = computed<CreateScope[]>(() => [
  { label: '個人の予定', value: 'personal', isPersonal: true, scopeType: 'team', scopeId: 0 },
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

function getMonthRange(year: number, month: number) {
  const lastDay = new Date(year, month, 0).getDate()
  return {
    from: `${year}-${pad(month)}-01`,
    to: `${year}-${pad(month)}-${pad(lastDay)}`,
  }
}

async function loadGantt() {
  ganttLoading.value = true
  try {
    const { from, to } = getMonthRange(currentYear.value, currentMonth.value)
    ganttFromDate.value = from
    ganttToDate.value = to
    const res = await ganttApi.getGanttTodos('team', 0, from, to)
    ganttTodos.value = res.data
  }
  catch {
    ganttTodos.value = []
  }
  finally {
    ganttLoading.value = false
  }
}

async function onTabChange(tab: CalendarTab) {
  activeTab.value = tab
  if (tab === 'gantt' && ganttTodos.value.length === 0) {
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

onMounted(loadEvents)
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
        <h1 class="text-2xl font-bold">マイカレンダー</h1>
      </div>
      <!-- #52: スコープ選択 + 予定を追加 -->
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
        <Button label="予定を追加" icon="pi pi-plus" @click="showCreateDialog = true" />
      </div>
    </div>

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
    <template v-if="activeTab === 'calendar'">
      <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <!-- カレンダー（2列） -->
        <div class="lg:col-span-2">
          <div
            class="rounded-xl border-2 border-surface-400 bg-surface-0 p-4 dark:border-surface-500 dark:bg-surface-800"
          >
            <CalendarGrid
              :year="currentYear"
              :month="currentMonth"
              :events="filteredEvents"
              @date-click="onDateClick"
              @event-click="onEventClick"
              @prev-month="onPrevMonth"
              @next-month="onNextMonth"
            />
          </div>

          <!-- 凡例 + フィルタ -->
          <div class="mt-4 flex flex-wrap items-center gap-4 text-xs text-surface-500">
            <span><span class="mr-1 inline-block h-3 w-3 rounded-full bg-green-500" />個人</span>
            <span><span class="mr-1 inline-block h-3 w-3 rounded-full bg-indigo-500" />チーム/組織</span>
            <!-- #51: スコープフィルタ -->
            <div v-if="availableScopes.length > 0" class="flex gap-2 flex-wrap items-center">
              <span class="text-xs text-surface-400">表示:</span>
              <button
                v-for="sc in availableScopes"
                :key="sc.value"
                type="button"
                class="text-xs px-2 py-0.5 rounded-full border transition-colors"
                :class="selectedScopes.includes(sc.value) || selectedScopes.length === 0
                  ? 'border-primary text-primary bg-primary/10'
                  : 'border-surface-300 text-surface-400'"
                @click="toggleScope(sc.value)"
              >
                {{ sc.label }}
              </button>
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
                myAttendance: selectedEvent.myAttendance ?? null,
                attendanceStats: selectedEvent.attendanceStats ?? null,
              }"
              :scope-type="selectedEventIsPersonal ? 'team' : ((selectedEvent.scopeType ?? '').toLowerCase() as 'team' | 'organization')"
              :scope-id="selectedEvent.scopeId ?? 0"
              :can-edit="true"
              @edit="onEditEvent"
              @delete="onDeleteEvent"
              @responded="refresh"
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
                :key="ev.id"
                class="cursor-pointer rounded-lg p-2 hover:bg-surface-100 dark:hover:bg-surface-700 border border-surface-200 dark:border-surface-600"
                @click="onEventClick(ev.id, ev.isPersonal)"
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
    </template>

    <!-- ガントビュー -->
    <template v-else>
      <div
        class="rounded-xl border-2 border-surface-400 bg-surface-0 p-4 dark:border-surface-500 dark:bg-surface-800"
      >
        <div v-if="ganttLoading" class="space-y-3">
          <Skeleton v-for="i in 5" :key="i" height="2rem" />
        </div>
        <TodoGanttView
          v-else
          :todos="ganttTodos"
          :from-date="ganttFromDate"
          :to-date="ganttToDate"
        />
      </div>
    </template>

    <!-- 作成ダイアログ -->
    <ScheduleEventForm
      v-model:visible="showCreateDialog"
      :scope-type="selectedCreateScope.scopeType"
      :scope-id="selectedCreateScope.scopeId"
      :initial-date="selectedDate"
      :is-personal="selectedCreateScope.isPersonal"
      @saved="refresh"
    />

    <!-- 編集ダイアログ -->
    <ScheduleEventForm
      v-if="selectedEvent && selectedEventId"
      v-model:visible="showEditDialog"
      :scope-type="selectedEventIsPersonal ? 'team' : ((selectedEvent?.scopeType ?? '').toLowerCase() as 'team' | 'organization')"
      :scope-id="selectedEvent?.scopeId ?? 0"
      :schedule-id="selectedEventId"
      :is-personal="selectedEventIsPersonal"
      @saved="onSaved"
    />
  </div>
</template>
