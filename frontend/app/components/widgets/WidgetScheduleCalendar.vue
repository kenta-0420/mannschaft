<script setup lang="ts">
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

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

const fetcher = async (from: string, to: string): Promise<CalendarEventItem[]> => {
  const [scopeRes, personalRes] = await Promise.all([
    scheduleApi.listSchedules(props.scopeType, props.scopeId, { from, to, size: 500 }),
    scheduleApi.getMySchedules({ from, to, size: 500 }),
  ])
  const scopeItems = (scopeRes.data ?? []) as ScheduleApiItem[]
  const personalItems = (personalRes.data ?? []) as ScheduleApiItem[]
  return [
    ...scopeItems.map((e) => ({
      ...e,
      allDay: e.allDay ?? false,
      color: e.color ?? null,
      isPersonal: false,
      scopeType: props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION',
    })),
    ...personalItems.map((e) => ({
      ...e,
      allDay: e.allDay ?? false,
      color: e.color ?? null,
      isPersonal: true,
    })),
  ]
}

const { currentYear, currentMonth, events, loading, loadEvents, onPrevMonth, onNextMonth } =
  useCalendarEvents(fetcher, {
    cacheHalfMonths: 2,
    onError: (error) =>
      captureQuiet(error, { context: 'WidgetScheduleCalendar: スコープ + 個人スケジュール取得' }),
  })

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
