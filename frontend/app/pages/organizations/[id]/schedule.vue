<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)
const scheduleApi = useScheduleApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const now = new Date()
const currentYear = ref(now.getFullYear())
const currentMonth = ref(now.getMonth() + 1)

interface CalEvent {
  id: number; title: string; startAt: string; endAt: string; allDay: boolean
  color: string | null; scopeType: string; isPersonal: boolean
}

const events = ref<CalEvent[]>([])
const loading = ref(true)
const showCreateDialog = ref(false)
const selectedDate = ref<string | undefined>(undefined)
const selectedEventId = ref<number | undefined>(undefined)
const selectedEvent = ref<Record<string, unknown> | null>(null)
const showDetailPanel = ref(false)
const showEditDialog = ref(false)

async function loadEvents() {
  loading.value = true
  try {
    const from = `${currentYear.value}-${String(currentMonth.value).padStart(2, '0')}-01`
    const lastDay = new Date(currentYear.value, currentMonth.value, 0).getDate()
    const to = `${currentYear.value}-${String(currentMonth.value).padStart(2, '0')}-${lastDay}`
    const res = await scheduleApi.listSchedules('organization', orgId, { from, to, size: 100 })
    events.value = (res.data as CalEvent[]).map(e => ({ ...e, isPersonal: false, scopeType: 'ORGANIZATION' }))
  }
  catch { events.value = [] }
  finally { loading.value = false }
}

function onDateClick(date: string) {
  selectedDate.value = date
  showCreateDialog.value = true
}

async function onEventClick(eventId: number) {
  try {
    const res = await scheduleApi.getSchedule('organization', orgId, eventId)
    selectedEvent.value = res.data as Record<string, unknown>
    selectedEventId.value = eventId
    showDetailPanel.value = true
  }
  catch { /* ignore */ }
}

function onPrevMonth() {
  if (currentMonth.value === 1) { currentMonth.value = 12; currentYear.value-- }
  else { currentMonth.value-- }
  loadEvents()
}

function onNextMonth() {
  if (currentMonth.value === 12) { currentMonth.value = 1; currentYear.value++ }
  else { currentMonth.value++ }
  loadEvents()
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
    await loadEvents()
  }
  catch { /* handled by api */ }
}

onMounted(async () => {
  await loadPermissions()
  await loadEvents()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">スケジュール</h1>
      <Button v-if="isAdminOrDeputy" label="イベント作成" icon="pi pi-plus" @click="showCreateDialog = true" />
    </div>

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <div class="lg:col-span-2">
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
          <CalendarGrid
            :year="currentYear"
            :month="currentMonth"
            :events="events"
            @date-click="onDateClick"
            @event-click="onEventClick"
            @prev-month="onPrevMonth"
            @next-month="onNextMonth"
          />
        </div>
      </div>

      <div>
        <div v-if="showDetailPanel && selectedEvent" class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
          <EventDetailPanel
            :event="selectedEvent as any"
            scope-type="organization"
            :scope-id="orgId"
            :can-edit="isAdminOrDeputy"
            @edit="onEditEvent"
            @delete="onDeleteEvent"
            @responded="loadEvents"
          />
        </div>
        <div v-else class="rounded-xl border border-surface-200 bg-surface-0 p-8 dark:border-surface-700 dark:bg-surface-800">
          <DashboardEmptyState icon="pi pi-calendar" message="イベントを選択してください" />
        </div>
      </div>
    </div>

    <EventForm
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgId"
      :initial-date="selectedDate"
      @saved="loadEvents"
    />

    <EventForm
      v-model:visible="showEditDialog"
      scope-type="organization"
      :scope-id="orgId"
      :schedule-id="selectedEventId"
      @saved="loadEvents"
    />
  </div>
</template>
