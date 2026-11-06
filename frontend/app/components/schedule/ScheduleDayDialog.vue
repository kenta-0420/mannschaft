<script setup lang="ts">
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import { eventOccupiesDate } from '~/utils/calendarWeek'

const props = defineProps<{
  visible: boolean
  date: string
  events: CalendarEventItem[]
  canCreate: boolean
}>()

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  rowOpen: [event: CalendarEventItem]
  create: []
}>()

const { t } = useI18n()
const dayEvents = computed(() => props.events
  .filter(event => eventOccupiesDate(event, props.date))
  .sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt)))
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :header="t('schedule.calendar.dayDetail.title', { date })"
    :style="{ width: '560px', maxWidth: '95vw' }"
    @update:visible="emit('update:visible', $event)"
  >
    <div data-testid="schedule-day-dialog" class="min-w-0">
      <div v-if="dayEvents.length" class="max-h-[60vh] overflow-y-auto [&_button]:min-h-11">
        <ScheduleListRow
          v-for="event in dayEvents"
          :key="event.uniqueKey"
          :event="{ ...event, attendanceRequired: false }"
          scope-type="team"
          scope-id=""
          @open="emit('rowOpen', event)"
        />
      </div>
      <DashboardEmptyState v-else icon="pi pi-calendar" :message="t('schedule.calendar.dayDetail.empty')" />
    </div>
    <template #footer>
      <Button
        v-if="canCreate"
        :label="t('schedule.calendar.dayDetail.create')"
        icon="pi pi-plus"
        class="min-h-11"
        data-testid="schedule-day-create"
        @click="emit('create')"
      />
    </template>
  </Dialog>
</template>
