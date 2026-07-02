<script setup lang="ts">
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import {
  toCalendarEventItems,
  toFlatScheduleEvent,
  type FlatScheduleEvent,
  type NestedScheduleResponse,
} from '~/utils/scheduleCalendar'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const scheduleApi = useScheduleApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const refreshing = ref(false)
const showCreateDialog = ref(false)
const selectedDate = ref<string | undefined>(undefined)
const selectedEventId = ref<number | undefined>(undefined)
const selectedEvent = ref<FlatScheduleEvent | null>(null)
const showDetailPanel = ref(false)
const showEditDialog = ref(false)

const fetcher = async (from: string, to: string): Promise<CalendarEventItem[]> => {
  const res = await scheduleApi.listSchedules('team', teamSlug, { from, to, size: 100 })
  // BE はネスト ScheduleResponse を返すため、平坦な CalendarEventItem へ変換する。
  return toCalendarEventItems(res.data as NestedScheduleResponse[], 'TEAM')
}

const { currentYear, currentMonth, events, loading, loadEvents, refresh, onPrevMonth, onNextMonth } =
  useCalendarEvents(fetcher, { cacheHalfMonths: 2 })

function onDateClick(date: string) {
  selectedDate.value = date
  showCreateDialog.value = true
}

function onAddButtonClick() {
  selectedDate.value = undefined
  showCreateDialog.value = true
}

async function onEventClick(eventId: number) {
  try {
    const res = await scheduleApi.getSchedule('team', teamSlug, eventId)
    // 詳細 GET もネスト ScheduleResponse のため平坦化してから EventDetailPanel へ渡す。
    selectedEvent.value = toFlatScheduleEvent(res.data as NestedScheduleResponse)
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
    await scheduleApi.deleteSchedule('team', teamSlug, selectedEventId.value)
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
      <PageHeader title="スケジュール" />
      <Button label="予定を追加" icon="pi pi-plus" @click="onAddButtonClick" />
    </div>

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <!-- カレンダー -->
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

      <!-- イベント詳細サイドパネル -->
      <div>
        <SectionCard v-if="showDetailPanel && selectedEvent">
          <EventDetailPanel
            :event="selectedEvent!"
            scope-type="team"
            :scope-id="teamSlug"
            :can-edit="isAdminOrDeputy"
            @edit="onEditEvent"
            @delete="onDeleteEvent"
            @responded="refresh"
          />
        </SectionCard>
        <SectionCard v-else class="p-8">
          <DashboardEmptyState icon="pi pi-calendar" message="イベントを選択してください" />
        </SectionCard>
      </div>
    </div>

    <!-- 作成ダイアログ -->
    <ScheduleEventForm
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="teamSlug"
      :initial-date="selectedDate"
      @saved="onSaved"
    />

    <!-- 編集ダイアログ -->
    <ScheduleEventForm
      v-model:visible="showEditDialog"
      scope-type="team"
      :scope-id="teamSlug"
      :schedule-id="selectedEventId"
      @saved="onSaved"
    />
  </div>
</template>
