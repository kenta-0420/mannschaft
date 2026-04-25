<script setup lang="ts">
/**
 * ダッシュボード「スケジュール」ウィジェット (カレンダー版)。
 *
 * - 当月のスケジュールを月表示カレンダーで描画する。
 * - 月切り替えボタン (前月/次月) で `currentYear`/`currentMonth` を進めて再フェッチ。
 * - 日付クリックで対象スコープのスケジュールページに遷移する。
 *
 * 旧来の「今週の予定」リスト型 (`WidgetUpcomingEvents`) と棲み分けるため、
 * 本ウィジェットはカレンダー (`CalendarGrid`) を表示する役割に特化している。
 *
 * `from`/`to` は backend 側のバリデーション仕様 (LocalDateTime) に合わせ、
 * `YYYY-MM-DDTHH:mm:ss` 形式 (ISO の先頭 19 文字) で渡す必要がある。
 * `.slice(0, 10)` で日付のみ送ると 400 になる事象が確認済 (F01.2)。
 */
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

interface CalendarEvent {
  id: number
  title: string
  startAt: string
  endAt: string
  allDay: boolean
  color: string | null
  isPersonal: boolean
}

interface ScheduleApiItem {
  id: number
  title: string
  startAt: string
  endAt: string
  allDay?: boolean
  color?: string | null
}

const scheduleApi = useScheduleApi()
const { captureQuiet } = useErrorReport()

const now = new Date()
const currentYear = ref(now.getFullYear())
const currentMonth = ref(now.getMonth() + 1)
const events = ref<CalendarEvent[]>([])
const loading = ref(true)

const pad = (n: number) => String(n).padStart(2, '0')

function buildMonthRange(year: number, month: number): { from: string; to: string } {
  const firstDay = new Date(year, month - 1, 1, 0, 0, 0)
  const lastDay = new Date(year, month, 0, 23, 59, 59)
  // ISO の先頭 19 文字 (YYYY-MM-DDTHH:mm:ss) を抽出。
  // タイムゾーンずれを避けるため Date#toISOString ではなく手組みする。
  const from = `${firstDay.getFullYear()}-${pad(firstDay.getMonth() + 1)}-${pad(firstDay.getDate())}T00:00:00`
  const to = `${lastDay.getFullYear()}-${pad(lastDay.getMonth() + 1)}-${pad(lastDay.getDate())}T23:59:59`
  // 念のため slice(0, 19) で長さを揃える (規約準拠)。
  return { from: from.slice(0, 19), to: to.slice(0, 19) }
}

async function loadEvents() {
  loading.value = true
  try {
    const { from, to } = buildMonthRange(currentYear.value, currentMonth.value)
    const res = await scheduleApi.listSchedules(props.scopeType, props.scopeId, {
      from,
      to,
      size: 100,
    })
    const items = (res.data ?? []) as ScheduleApiItem[]
    events.value = items.map((e) => ({
      id: e.id,
      title: e.title,
      startAt: e.startAt,
      endAt: e.endAt,
      allDay: e.allDay ?? false,
      color: e.color ?? null,
      isPersonal: false,
    }))
  } catch (error) {
    captureQuiet(error, { context: 'WidgetScheduleCalendar: 月次スケジュール取得' })
    events.value = []
  } finally {
    loading.value = false
  }
}

function onPrevMonth() {
  if (currentMonth.value === 1) {
    currentMonth.value = 12
    currentYear.value--
  } else {
    currentMonth.value--
  }
  loadEvents()
}

function onNextMonth() {
  if (currentMonth.value === 12) {
    currentMonth.value = 1
    currentYear.value++
  } else {
    currentMonth.value++
  }
  loadEvents()
}

function onDateClick(date: string) {
  const base =
    props.scopeType === 'team' ? `/teams/${props.scopeId}` : `/organizations/${props.scopeId}`
  navigateTo(`${base}/schedule?date=${date}`)
}

onMounted(loadEvents)
</script>

<template>
  <div data-testid="widget-schedule-calendar">
    <div v-if="loading" class="space-y-3">
      <Skeleton height="2rem" />
      <Skeleton height="14rem" />
    </div>
    <CalendarGrid
      v-else
      :year="currentYear"
      :month="currentMonth"
      :events="events"
      @prev-month="onPrevMonth"
      @next-month="onNextMonth"
      @date-click="onDateClick"
    />
  </div>
</template>
