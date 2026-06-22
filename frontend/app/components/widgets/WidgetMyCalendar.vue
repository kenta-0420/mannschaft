<script setup lang="ts">
import { useMyCalendarData, FILTER_OVERFLOW } from '~/composables/useMyCalendarData'

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
}

interface PersonalScheduleRaw {
  id: number
  content: { title: string; description: string | null; eventType: string; color: string | null; location: string | null }
  time: { startAt: string; endAt: string; allDay: boolean }
  status: { status: string; isException: boolean; parentScheduleId: number | null; recurrenceRule: unknown; googleSynced: boolean }
  reminders: number[]
  audit: { createdAt: string; updatedAt: string; createdByDisplayName: string | null }
}

const scheduleApi = useScheduleApi()

const {
  currentYear, currentMonth, loading, loadEvents, refresh,
  onPrevMonth, onNextMonth,
  extendedEvents, allScopeOptions, selectedScopes, filteredEvents,
  toggleScope, multiSelectScopes, initStorage,
} = useMyCalendarData({ storageKey: 'mannschaft:widget:calendar:scopeFilter' })

const selectedEventId = ref<number | null>(null)
const selectedEvent = ref<EventDetail | null>(null)
const selectedEventIsPersonal = ref(false)
const showEventDialog = ref(false)
const showEditDialog = ref(false)

function onDateClick(date: string) {
  navigateTo(`/calendar?date=${date}`)
}

/**
 * reflection 印クリック（§6.2/AC-21）。
 * - REFLECTION_RECALL（SPACED 間隔反復）: recall 画面（entry_id 指定）へ遷移。
 * - REFLECTION_PRE_EXAM（考査前総まとめ）: テーマ詳細画面（theme_id 指定）へ遷移。
 * - それ以外（REFLECTION_ENTRY 等）: エントリ詳細へ遷移。
 * referenceUuid は SPACED/エントリ＝entry UUID、PRE_EXAM＝theme UUID。
 */
function onReflectionClick(referenceUuid: string, referenceKind: string) {
  if (referenceKind === 'REFLECTION_RECALL') {
    navigateTo(`/reflections/recall?entry=${referenceUuid}`)
  }
  else if (referenceKind === 'REFLECTION_PRE_EXAM') {
    navigateTo(`/reflections/themes/${referenceUuid}`)
  }
  else {
    navigateTo(`/reflections/entries/${referenceUuid}`)
  }
}

async function onEventClick(eventId: number, isPersonal: boolean) {
  if (eventId < 0) {
    await navigateTo(`/todos/${-(eventId + 1)}`)
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
      const sid = ext.scopeId ?? ''
      const res = await scheduleApi.getSchedule(st, sid, eventId)
      const d = res.data as EventDetail & { createdByDisplayName?: string; myAttendanceStatus?: string }
      selectedEvent.value = {
        ...d,
        scopeType: ext.scopeType,
        scopeId: ext.scopeId,
        scopeName: (d as EventDetail).scopeName ?? ext.scopeName,
        scopeIconUrl: (d as EventDetail).scopeIconUrl ?? null,
        createdBy: d.createdByDisplayName ? { displayName: d.createdByDisplayName } : d.createdBy,
        myAttendance: d.myAttendanceStatus ?? null,
      }
    }
    showEventDialog.value = true
  }
  catch {
    // エラーはapi側で処理
  }
}

function onEditEvent() {
  showEventDialog.value = false
  showEditDialog.value = true
}

async function onDeleteEvent() {
  if (!selectedEventId.value) return
  try {
    if (selectedEventIsPersonal.value) {
      await scheduleApi.deletePersonalSchedule(selectedEventId.value)
      showEventDialog.value = false
      selectedEvent.value = null
      await refresh()
    }
    else {
      showEventDialog.value = false
      await navigateTo('/calendar')
    }
  }
  catch {
    // エラーはapi側で処理
  }
}

async function onSaved() {
  showEditDialog.value = false
  await refresh()
}

onMounted(() => {
  initStorage()
  loadEvents()
})
</script>

<template>
  <div>
    <div class="mb-2 flex items-center justify-between">
      <h3 class="font-semibold text-[22px] text-surface-700 dark:text-surface-200">
        <i class="pi pi-calendar mr-1.5 text-primary" />マイカレンダー
      </h3>
      <Button label="全画面で開く" icon="pi pi-external-link" text size="small" @click="navigateTo('/calendar')" />
    </div>

    <div v-if="loading" class="space-y-2">
      <Skeleton height="1.5rem" />
      <Skeleton height="12rem" />
    </div>
    <template v-else>
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

      <!-- スコープフィルター -->
      <div v-if="allScopeOptions.length > 1" class="mt-3 flex flex-wrap items-center gap-2 text-xs text-surface-500">
        <span class="text-surface-400">表示:</span>
        <template v-if="allScopeOptions.length <= FILTER_OVERFLOW">
          <button
            v-for="sc in allScopeOptions"
            :key="sc.value"
            type="button"
            class="px-2 py-0.5 rounded-full border transition-colors text-xs"
            :class="selectedScopes.includes(sc.value)
              ? 'border-primary text-primary bg-primary/10'
              : 'border-surface-300 text-surface-400'"
            @click="toggleScope(sc.value)"
          >
            {{ sc.label }}
          </button>
        </template>
        <MultiSelect
          v-else
          v-model="multiSelectScopes"
          :options="allScopeOptions"
          option-label="label"
          option-value="value"
          placeholder="表示するスコープを選択"
          :max-selected-labels="2"
          selected-items-label="{0}件選択中"
          class="text-xs"
          style="min-width: 160px"
        />
      </div>

      <!-- 凡例 -->
      <div class="mt-2 flex flex-wrap gap-3 text-xs text-surface-400">
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-green-500" />個人</span>
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-orange-500" />TODO</span>
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-indigo-500" />チーム/組織</span>
      </div>
    </template>

    <Dialog
      v-model:visible="showEventDialog"
      modal
      :header="selectedEvent?.title ?? ''"
      :style="{ width: '480px', maxWidth: '95vw' }"
      :pt="{ header: { class: 'pb-2' } }"
    >
      <EventDetailPanel
        v-if="selectedEvent"
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
          attendanceRequired: selectedEvent.attendanceRequired ?? false,
          myAttendance: selectedEvent.myAttendance ?? null,
          attendanceStats: selectedEvent.attendanceStats ?? null,
        }"
        :scope-type="selectedEventIsPersonal ? 'team' : ((selectedEvent.scopeType ?? '').toLowerCase() as 'team' | 'organization')"
        :scope-id="selectedEvent.scopeId ?? ''"
        :can-edit="true"
        :skip-delegations="selectedEventIsPersonal"
        :scope-name="selectedEvent.scopeName ?? null"
        :scope-icon-url="selectedEvent.scopeIconUrl ?? null"
        @edit="onEditEvent"
        @delete="onDeleteEvent"
        @responded="refresh"
      />
    </Dialog>

    <ScheduleEventForm
      v-if="selectedEvent && selectedEventId"
      v-model:visible="showEditDialog"
      :schedule-id="selectedEventId"
      :scope-type="selectedEventIsPersonal ? 'team' : ((selectedEvent?.scopeType ?? '').toLowerCase() as 'team' | 'organization')"
      :scope-id="selectedEvent?.scopeId ?? ''"
      :is-personal="selectedEventIsPersonal"
      @saved="onSaved"
    />
  </div>
</template>
