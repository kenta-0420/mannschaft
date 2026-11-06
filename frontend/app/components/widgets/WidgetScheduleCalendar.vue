<script setup lang="ts">
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import { toCalendarEventItems, type NestedScheduleResponse } from '~/utils/scheduleCalendar'
import { eventOccupiesDate } from '~/utils/calendarWeek'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

interface PersonalScheduleRaw {
  id: number
  content?: { title?: string; color?: string | null; eventType?: string }
  time?: { startAt?: string; endAt?: string; allDay?: boolean }
}

const scheduleApi = useScheduleApi()
const { captureQuiet } = useErrorReport()
const { isAdminOrDeputy, roleName, can, loadPermissions, loading: permissionsLoading } =
  useRoleAccess(props.scopeType, toRef(props, 'scopeId'))
const canCreate = computed(() => isAdminOrDeputy.value || (roleName.value === 'MEMBER' && can('MANAGE_SCHEDULES')))
const permissionsError = ref(false)
const selectedDate = ref('')
const selectedPersonalId = ref<number | null>(null)
const showDayDialog = ref(false)
const showCreateDialog = ref(false)
const showPersonalDialog = ref(false)

async function loadWidget() {
  const result = await loadPermissions()
  permissionsError.value = !result.ok
  if (!result.ok) captureQuiet(result.error, { context: 'WidgetScheduleCalendar: 権限取得' })
  await loadEvents()
}

const fetcher = async (from: string, to: string): Promise<CalendarEventItem[]> => {
  const [scopeRes, personalRes] = await Promise.all([
    scheduleApi.listSchedules(props.scopeType, props.scopeId, { from, to, size: 500 }),
    scheduleApi.getMySchedules({ from, to, size: 500 }),
  ])
  const scopeItems = (scopeRes.data ?? []) as NestedScheduleResponse[]
  const personalItems = (personalRes.data ?? []) as PersonalScheduleRaw[]
  return [
    ...toCalendarEventItems(scopeItems, props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION'),
    ...personalItems.map((e) => ({
      id: e.id,
      uniqueKey: `personal:${e.id}`,
      title: e.content?.title ?? '',
      startAt: e.time?.startAt ?? '',
      endAt: e.time?.endAt ?? e.time?.startAt ?? '',
      allDay: e.time?.allDay ?? false,
      color: e.content?.color ?? null,
      isPersonal: true,
    })),
  ]
}

const { currentYear, currentMonth, events, loading, calendarLoading, loadError, loadEvents, refresh, onPrevMonth, onNextMonth } =
  useCalendarEvents(fetcher, {
    cacheHalfMonths: 2,
    onError: (error) =>
      captureQuiet(error, { context: 'WidgetScheduleCalendar: スコープ + 個人スケジュール取得' }),
  })

function onDateClick(date: string) {
  if (loading.value || calendarLoading.value || loadError.value || permissionsLoading.value || permissionsError.value) return
  selectedDate.value = date
  if (!events.value.some(event => eventOccupiesDate(event, date)) && canCreate.value) {
    onCreateEvent()
  }
  else {
    showDayDialog.value = true
  }
}

function moveMonth(delta: number) {
  if (loading.value || calendarLoading.value || permissionsLoading.value) return
  if (delta < 0) onPrevMonth()
  else onNextMonth()
}

function onCreateEvent() {
  if (!canCreate.value) return
  showDayDialog.value = false
  showCreateDialog.value = true
}

function onDayEventOpen(event: CalendarEventItem) {
  showDayDialog.value = false
  if (event.isPersonal) {
    selectedPersonalId.value = event.id
    showPersonalDialog.value = true
    return
  }
  const base =
    props.scopeType === 'team' ? `/teams/${props.scopeId}` : `/organizations/${props.scopeId}`
  navigateTo(`${base}/schedule?eventId=${event.id}`)
}

function onEventClick(id: number, isPersonal: boolean) {
  const event = events.value.find(event => event.id === id && event.isPersonal === isPersonal)
  if (event) onDayEventOpen(event)
}

async function onSaved() {
  showCreateDialog.value = false
  showPersonalDialog.value = false
  await refresh()
}

onMounted(loadWidget)
</script>

<template>
  <div data-testid="widget-schedule-calendar">
    <div v-if="loading || permissionsLoading" class="space-y-3">
      <Skeleton height="2rem" />
      <Skeleton height="14rem" />
    </div>
    <DashboardErrorState v-else-if="loadError || permissionsError" @retry="loadWidget" />
    <CalendarGrid
      v-else
      :year="currentYear"
      :month="currentMonth"
      :events="events"
      @prev-month="moveMonth(-1)"
      @next-month="moveMonth(1)"
      @date-click="onDateClick"
      @event-click="onEventClick"
    />
    <ScheduleDayDialog
      v-model:visible="showDayDialog"
      :date="selectedDate"
      :events="events"
      :can-create="canCreate"
      @create="onCreateEvent"
      @row-open="onDayEventOpen"
    />
    <ScheduleEventForm
      v-model:visible="showCreateDialog"
      :scope-type="scopeType"
      :scope-id="scopeId"
      :initial-date="selectedDate"
      @saved="onSaved"
    />
    <ScheduleEventForm
      v-model:visible="showPersonalDialog"
      :scope-type="scopeType"
      :scope-id="scopeId"
      :schedule-id="selectedPersonalId ?? undefined"
      is-personal
      @saved="onSaved"
    />
  </div>
</template>
