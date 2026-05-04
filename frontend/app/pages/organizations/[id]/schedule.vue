<script setup lang="ts">
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)
const scheduleApi = useScheduleApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

interface ScheduleEventDetail {
  id: number
  title: string
  description: string | null
  location: string | null
  startAt: string
  endAt: string
  allDay: boolean
  status: string
  categoryName: string | null
  categoryColor: string | null
  createdBy: { displayName: string }
  myAttendance: string | null
  attendanceStats: { yes: number; no: number; maybe: number; pending: number; total: number } | null
}

const refreshing = ref(false)
const showTypeSelector = ref(false)
const showCreateDialog = ref(false)
const createAsPersonal = ref(false)
const selectedDate = ref<string | undefined>(undefined)
const selectedEventId = ref<number | undefined>(undefined)
const selectedEvent = ref<ScheduleEventDetail | null>(null)
const showDetailPanel = ref(false)
const showEditDialog = ref(false)

const fetcher = async (from: string, to: string): Promise<CalendarEventItem[]> => {
  const res = await scheduleApi.listSchedules('organization', orgId, { from, to, size: 100 })
  return (res.data as CalendarEventItem[]).map((e) => ({
    ...e,
    allDay: e.allDay ?? false,
    color: e.color ?? null,
    isPersonal: false,
    scopeType: 'ORGANIZATION',
  }))
}

const { currentYear, currentMonth, events, loading, loadEvents, refresh, onPrevMonth, onNextMonth } =
  useCalendarEvents(fetcher, { cacheHalfMonths: 2 })

function onDateClick(date: string) {
  selectedDate.value = date
  showTypeSelector.value = true
}

function onAddButtonClick() {
  selectedDate.value = undefined
  showTypeSelector.value = true
}

function onTypeSelected(type: 'personal' | 'event') {
  createAsPersonal.value = type === 'personal'
  showCreateDialog.value = true
}

async function onEventClick(eventId: number) {
  try {
    const res = await scheduleApi.getSchedule('organization', orgId, eventId)
    selectedEvent.value = res.data as ScheduleEventDetail
    selectedEventId.value = eventId
    showDetailPanel.value = true
  } catch {
    /* ignore */
  }
}

function onEditEvent() {
  showDetailPanel.value = false
  showEditDialog.value = true
}

async function onDeleteEvent() {
  if (!selectedEventId.value || !confirm('このイベントを削除しますか？')) return
  try {
    await scheduleApi.deleteSchedule('organization', orgId, selectedEventId.value)
    showDetailPanel.value = false
    refreshing.value = true
    await refresh()
    refreshing.value = false
  } catch {
    /* handled by api */
  }
}

async function onSaved() {
  refreshing.value = true
  await refresh()
  refreshing.value = false
}

onMounted(async () => {
  await loadPermissions()
  await loadEvents()
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <BackButton />
        <PageHeader title="スケジュール" />
      </div>
      <Button label="予定を追加" icon="pi pi-plus" @click="onAddButtonClick" />
    </div>

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <div class="lg:col-span-2">
        <SectionCard :class="{ 'opacity-60': refreshing }">
          <CalendarGrid
            :year="currentYear"
            :month="currentMonth"
            :events="events"
            @date-click="onDateClick"
            @event-click="onEventClick"
            @prev-month="onPrevMonth"
            @next-month="onNextMonth"
          />
        </SectionCard>
      </div>

      <div>
        <SectionCard v-if="showDetailPanel && selectedEvent">
          <EventDetailPanel
            :event="selectedEvent!"
            scope-type="organization"
            :scope-id="orgId"
            :can-edit="isAdminOrDeputy"
            @edit="onEditEvent"
            @delete="onDeleteEvent"
            @responded="refresh"
          />
        </SectionCard>
        <SectionCard v-else>
          <DashboardEmptyState icon="pi pi-calendar" message="イベントを選択してください" />
        </SectionCard>
      </div>
    </div>

    <!-- タイプ選択ダイアログ -->
    <ScheduleTypeSelector
      v-model:visible="showTypeSelector"
      @select="onTypeSelected"
    />

    <!-- 作成ダイアログ -->
    <ScheduleEventForm
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="createAsPersonal ? 0 : orgId"
      :is-personal="createAsPersonal"
      :initial-date="selectedDate"
      @saved="onSaved"
    />

    <!-- 編集ダイアログ -->
    <ScheduleEventForm
      v-model:visible="showEditDialog"
      scope-type="organization"
      :scope-id="orgId"
      :schedule-id="selectedEventId"
      @saved="onSaved"
    />
  </div>
</template>
