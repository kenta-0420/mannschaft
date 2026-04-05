<script setup lang="ts">
const props = defineProps<{
  year: number
  month: number
  events: Array<{
    id: number
    title: string
    startAt: string
    endAt: string
    allDay: boolean
    color: string | null
    isPersonal: boolean
  }>
}>()

const emit = defineEmits<{
  dateClick: [date: string]
  eventClick: [eventId: number, isPersonal: boolean]
  prevMonth: []
  nextMonth: []
}>()

const daysOfWeek = ['日', '月', '火', '水', '木', '金', '土']

const calendarDays = computed(() => {
  const firstDay = new Date(props.year, props.month - 1, 1)
  const lastDay = new Date(props.year, props.month, 0)
  const startOffset = firstDay.getDay()
  const totalDays = lastDay.getDate()

  const days: Array<{ date: number; month: number; year: number; isCurrentMonth: boolean; dateStr: string }> = []

  // Previous month padding
  const prevLastDay = new Date(props.year, props.month - 1, 0).getDate()
  for (let i = startOffset - 1; i >= 0; i--) {
    const d = prevLastDay - i
    const m = props.month - 1 < 1 ? 12 : props.month - 1
    const y = props.month - 1 < 1 ? props.year - 1 : props.year
    days.push({ date: d, month: m, year: y, isCurrentMonth: false, dateStr: `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}` })
  }

  // Current month
  for (let d = 1; d <= totalDays; d++) {
    days.push({ date: d, month: props.month, year: props.year, isCurrentMonth: true, dateStr: `${props.year}-${String(props.month).padStart(2, '0')}-${String(d).padStart(2, '0')}` })
  }

  // Next month padding
  const remaining = 42 - days.length
  for (let d = 1; d <= remaining; d++) {
    const m = props.month + 1 > 12 ? 1 : props.month + 1
    const y = props.month + 1 > 12 ? props.year + 1 : props.year
    days.push({ date: d, month: m, year: y, isCurrentMonth: false, dateStr: `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}` })
  }

  return days
})

function getEventsForDate(dateStr: string) {
  return props.events.filter(e => {
    const start = e.startAt.split('T')[0]
    const end = e.endAt.split('T')[0]
    return dateStr >= start && dateStr <= end
  }).slice(0, 3)
}

function isToday(dateStr: string): boolean {
  return dateStr === new Date().toISOString().split('T')[0]
}

const monthLabel = computed(() => `${props.year}年${props.month}月`)
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex items-center justify-between">
      <Button icon="pi pi-chevron-left" text rounded @click="emit('prevMonth')" />
      <h2 class="text-lg font-bold">{{ monthLabel }}</h2>
      <Button icon="pi pi-chevron-right" text rounded @click="emit('nextMonth')" />
    </div>

    <!-- 曜日ヘッダー -->
    <div class="grid grid-cols-7 border-b border-surface-200 dark:border-surface-600">
      <div
        v-for="day in daysOfWeek"
        :key="day"
        class="py-2 text-center text-xs font-medium text-surface-500"
        :class="{ 'text-red-500': day === '日', 'text-blue-500': day === '土' }"
      >
        {{ day }}
      </div>
    </div>

    <!-- 日付グリッド -->
    <div class="grid grid-cols-7">
      <div
        v-for="(day, idx) in calendarDays"
        :key="idx"
        class="min-h-24 cursor-pointer border-b border-r border-surface-100 p-1 transition-colors hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
        :class="{ 'bg-surface-50/50 dark:bg-surface-800/30': !day.isCurrentMonth }"
        @click="emit('dateClick', day.dateStr)"
      >
        <div
          class="mb-1 inline-flex h-6 w-6 items-center justify-center rounded-full text-xs"
          :class="{
            'bg-primary text-white': isToday(day.dateStr),
            'text-surface-400': !day.isCurrentMonth,
            'font-medium': day.isCurrentMonth,
          }"
        >
          {{ day.date }}
        </div>
        <div class="space-y-0.5">
          <div
            v-for="event in getEventsForDate(day.dateStr)"
            :key="event.id"
            class="truncate rounded px-1 py-0.5 text-xs"
            :style="{ backgroundColor: (event.color ?? '#6366f1') + '20', color: event.color ?? '#6366f1' }"
            @click.stop="emit('eventClick', event.id, event.isPersonal)"
          >
            {{ event.title }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
