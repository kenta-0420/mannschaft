<script setup lang="ts">
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import type { GanttTodo } from '~/types/todo'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const router = useRouter()
const scheduleApi = useScheduleApi()
const ganttApi = useTodoGantt()

type CalendarTab = 'calendar' | 'gantt'
const activeTab = ref<CalendarTab>('calendar')

const showCreateDialog = ref(false)
const selectedDate = ref<string | undefined>(undefined)

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
  isPersonal: boolean
}

const fetcher = async (from: string, to: string): Promise<CalendarEventItem[]> => {
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
  }))
  const sharedEvents = (
    ((shared.data as unknown as Record<string, unknown>)?.events as CalEvent[]) ?? []
  ).map((e) => ({
    ...e,
    allDay: e.allDay ?? false,
    color: e.color ?? null,
    isPersonal: false,
    scopeType: e.scopeType ?? '',
  }))
  return [...personalEvents, ...sharedEvents]
}

const { currentYear, currentMonth, events, loading, loadEvents, refresh, onPrevMonth: calPrevMonth, onNextMonth: calNextMonth } =
  useCalendarEvents(fetcher, { cacheHalfMonths: 0 })

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
  } catch {
    ganttTodos.value = []
  } finally {
    ganttLoading.value = false
  }
}

async function onTabChange(tab: CalendarTab) {
  activeTab.value = tab
  if (tab === 'gantt' && ganttTodos.value.length === 0) {
    await loadGantt()
  }
}

function onDateClick(date: string) {
  selectedDate.value = date
  showCreateDialog.value = true
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
      <Button label="予定を追加" icon="pi pi-plus" @click="showCreateDialog = true" />
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
      <div
        class="rounded-xl border-2 border-surface-400 bg-surface-0 p-4 dark:border-surface-500 dark:bg-surface-800"
      >
        <CalendarGrid
          :year="currentYear"
          :month="currentMonth"
          :events="events"
          @date-click="onDateClick"
          @prev-month="onPrevMonth"
          @next-month="onNextMonth"
        />
      </div>

      <div class="mt-4 flex gap-4 text-xs text-surface-500">
        <span><span class="mr-1 inline-block h-3 w-3 rounded-full bg-green-500" />個人</span>
        <span><span class="mr-1 inline-block h-3 w-3 rounded-full bg-indigo-500" />チーム/組織</span>
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

    <ScheduleEventForm
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="0"
      :initial-date="selectedDate"
      :is-personal="true"
      @saved="refresh"
    />
  </div>
</template>
