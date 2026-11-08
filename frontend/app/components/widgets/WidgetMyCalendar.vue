<script setup lang="ts">
import { useMyCalendarData, FILTER_OVERFLOW } from '~/composables/useMyCalendarData'
import { toCalendarPanelEvent, type NestedScheduleResponse, type CalendarPanelEvent } from '~/utils/scheduleCalendar'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import { eventOccupiesDate } from '~/utils/calendarWeek'

const { t } = useI18n()
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
  currentYear, currentMonth, loading, calendarLoading, loadError, refresh,
  onPrevMonth, onNextMonth,
  extendedEvents, allScopeOptions, selectedScopes, filteredEvents,
  toggleScope, multiSelectScopes, initialize,
} = useMyCalendarData()

const selectedEventId = ref<number | null>(null)
const selectedEvent = ref<CalendarPanelEvent | null>(null)
const selectedEventIsPersonal = ref(false)
const showEventDialog = ref(false)
const showEditDialog = ref(false)
const selectedDate = ref('')
const showDayDialog = ref(false)
const showCreateDialog = ref(false)
const mountedDialogs = reactive({ day: false, create: false, edit: false })
watch([showDayDialog, showCreateDialog, showEditDialog], ([day, create, edit]) => {
  mountedDialogs.day ||= day
  mountedDialogs.create ||= create
  mountedDialogs.edit ||= edit
})

function onDateClick(date: string) {
  if (loading.value || calendarLoading.value || loadError.value) return
  selectedDate.value = date
  if (filteredEvents.value.some(event => eventOccupiesDate(event, date))) {
    showDayDialog.value = true
  }
  else {
    onCreateEvent()
  }
}

function moveMonth(delta: number) {
  if (loading.value || calendarLoading.value || loadError.value) return
  if (delta < 0) onPrevMonth()
  else onNextMonth()
}

function onCreateEvent() {
  showDayDialog.value = false
  showCreateDialog.value = true
}

function onDayEventOpen(event: CalendarEventItem) {
  showDayDialog.value = false
  if (event.isReflection && event.referenceUuid) {
    onReflectionClick(event.referenceUuid, event.referenceKind ?? '')
  }
  else {
    void onEventClick(event.id, event.isPersonal)
  }
}

async function onCreated() {
  showCreateDialog.value = false
  await refresh()
}

/** reflection の参照種別に応じて既存の想起・テーマ・エントリ画面へ移動する。 */
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
        ...toCalendarPanelEvent(d),
        description: d.content?.description ?? null,
        color: d.content?.color ?? null,
        status: d.status?.status ?? 'PUBLISHED',
      }
    }
    else {
      const ext = extendedEvents.value.find(e => e.id === eventId && !e.isPersonal)
      if (!ext) return
      const st = (ext.scopeType ?? '').toLowerCase() as 'team' | 'organization'
      // F03.19 W2-a P1修繕: 詳細APIは公開スコープID（slug）を要求する。ext.scopeId は
      // レイヤーキー照合用の数値IDに変わったため、詳細取得には ext.scopeRouteId を使う。
      const sid = ext.scopeRouteId ?? ''
      const res = await scheduleApi.getSchedule(st, sid, eventId)
      // F03.19 実機E2E 欠陥1 と同型の欠陥がここにもあった（calendar.vue と同じ嘘のキャスト＋
      // スプレッドで、題名・日時が常に undefined になっていた）。詰め替えは共通の
      // toCalendarPanelEvent に委ねる（画面ごとに書くから1画面だけ取り残される）。
      selectedEvent.value = toCalendarPanelEvent(res.data as NestedScheduleResponse, {
        scopeType: ext.scopeType,
        scopeId: ext.scopeRouteId,
        scopeName: ext.scopeName,
        targetMode: ext.targetMode,
        targetCount: ext.targetCount,
        targets: ext.targets,
      })
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

onMounted(async () => {
  await initialize()
})
</script>

<template>
  <div>
    <div class="mb-2 flex items-center justify-between">
      <h3 class="font-semibold text-[22px] text-surface-700 dark:text-surface-200">
        <i class="pi pi-calendar mr-1.5 text-primary" />{{ t('dashboard.widgets.myCalendar.title') }}
      </h3>
      <Button :label="t('dashboard.widgets.myCalendar.openFullScreen')" icon="pi pi-external-link" text size="small" @click="navigateTo('/calendar')" />
    </div>

    <div v-if="loading" class="space-y-2">
      <Skeleton height="1.5rem" />
      <Skeleton height="12rem" />
    </div>
    <DashboardErrorState v-else-if="loadError" @retry="initialize" />
    <!-- 表示対象のmount後、データ待ちの間に部品も読み込む。設定確定前の予定は渡さない。 -->
    <div v-show="!loading && !loadError">
      <LazyCalendarGrid
        :year="currentYear"
        :month="currentMonth"
        :events="loading || loadError ? [] : filteredEvents"
        @date-click="onDateClick"
        @event-click="onEventClick"
        @reflection-click="onReflectionClick"
        @prev-month="moveMonth(-1)"
        @next-month="moveMonth(1)"
      />
    </div>
    <template v-if="!loading && !loadError">
      <!-- スコープフィルター -->
      <div v-if="allScopeOptions.length > 1" class="mt-3 flex flex-wrap items-center gap-2 text-xs text-surface-500">
        <span class="text-surface-400">{{ t('dashboard.widgets.myCalendar.show') }}</span>
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
          :placeholder="t('dashboard.widgets.myCalendar.scopePlaceholder')"
          :max-selected-labels="2"
          :selected-items-label="t('dashboard.widgets.myCalendar.selectedCount', { n: '{0}' })"
          class="text-xs"
          style="min-width: 160px"
        />
      </div>

      <!-- 凡例 -->
      <div class="mt-2 flex flex-wrap gap-3 text-xs text-surface-400">
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-green-500" />{{ t('dashboard.widgets.myCalendar.legendPersonal') }}</span>
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-orange-500" />TODO</span>
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-indigo-500" />{{ t('dashboard.widgets.myCalendar.legendTeamOrg') }}</span>
      </div>
    </template>

    <LazyScheduleDayDialog
      v-if="mountedDialogs.day"
      v-model:visible="showDayDialog"
      :date="selectedDate"
      :events="filteredEvents"
      :can-create="true"
      @create="onCreateEvent"
      @row-open="onDayEventOpen"
    />

    <LazyScheduleEventForm
      v-if="mountedDialogs.create"
      v-model:visible="showCreateDialog"
      scope-type="team"
      scope-id=""
      is-personal
      :initial-date="selectedDate"
      @saved="onCreated"
    />

    <Dialog
      v-model:visible="showEventDialog"
      modal
      :header="selectedEvent?.title ?? ''"
      :style="{ width: '480px', maxWidth: '95vw' }"
      :pt="{ header: { class: 'pb-2' } }"
    >
      <LazyEventDetailPanel
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
        @edit="onEditEvent"
        @delete="onDeleteEvent"
        @responded="refresh"
      />
    </Dialog>

    <LazyScheduleEventForm
      v-if="mountedDialogs.edit && selectedEvent && selectedEventId"
      v-model:visible="showEditDialog"
      :schedule-id="selectedEventId"
      :scope-type="selectedEventIsPersonal ? 'team' : ((selectedEvent?.scopeType ?? '').toLowerCase() as 'team' | 'organization')"
      :scope-id="selectedEvent?.scopeId ?? ''"
      :is-personal="selectedEventIsPersonal"
      @saved="onSaved"
    />
  </div>
</template>
