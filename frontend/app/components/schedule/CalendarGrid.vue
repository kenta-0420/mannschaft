<script setup lang="ts">
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

const props = defineProps<{
  year: number
  month: number
  events: CalendarEventItem[]
}>()

const emit = defineEmits<{
  dateClick: [date: string]
  eventClick: [eventId: number, isPersonal: boolean]
  prevMonth: []
  nextMonth: []
}>()

const { getHoliday } = useHolidays()
const daysOfWeek = ['日', '月', '火', '水', '木', '金', '土']

// レイアウト定数
const DATE_HEADER_H = 30  // p-1(4) + h-6(24) + mb-0.5(2) = 30px
const BAR_H = 18
const BAR_STRIDE = 21     // バー高さ + 3px ギャップ
const MAX_LANES = 3       // 1週に表示するバーの最大行数

interface DayInfo {
  date: number
  month: number
  year: number
  isCurrentMonth: boolean
  dateStr: string
}

interface MultiDaySlot {
  event: CalendarEventItem
  startCol: number
  endCol: number
  lane: number
  continuesBefore: boolean
  continuesAfter: boolean
}

interface WeekData {
  days: DayInfo[]
  slots: MultiDaySlot[]
  singleByCol: CalendarEventItem[][]
  lanesUsed: number
}

const pad = (n: number) => String(n).padStart(2, '0')
const dateOf = (dt: string) => dt.split('T')[0] ?? ''
const isMultiDay = (e: CalendarEventItem) => dateOf(e.startAt) !== dateOf(e.endAt)

const calendarDays = computed<DayInfo[]>(() => {
  const firstDay = new Date(props.year, props.month - 1, 1)
  const startOffset = firstDay.getDay()
  const totalDays = new Date(props.year, props.month, 0).getDate()
  const days: DayInfo[] = []

  const prevLastDay = new Date(props.year, props.month - 1, 0).getDate()
  for (let i = startOffset - 1; i >= 0; i--) {
    const d = prevLastDay - i
    const m = props.month === 1 ? 12 : props.month - 1
    const y = props.month === 1 ? props.year - 1 : props.year
    days.push({ date: d, month: m, year: y, isCurrentMonth: false, dateStr: `${y}-${pad(m)}-${pad(d)}` })
  }
  for (let d = 1; d <= totalDays; d++) {
    days.push({ date: d, month: props.month, year: props.year, isCurrentMonth: true, dateStr: `${props.year}-${pad(props.month)}-${pad(d)}` })
  }
  const remaining = 42 - days.length
  for (let d = 1; d <= remaining; d++) {
    const m = props.month === 12 ? 1 : props.month + 1
    const y = props.month === 12 ? props.year + 1 : props.year
    days.push({ date: d, month: m, year: y, isCurrentMonth: false, dateStr: `${y}-${pad(m)}-${pad(d)}` })
  }
  return days
})

const weeks = computed<WeekData[]>(() =>
  Array.from({ length: 6 }, (_, w) => {
    const days = calendarDays.value.slice(w * 7, w * 7 + 7) as DayInfo[]
    const weekStart = days[0]!.dateStr
    const weekEnd = days[6]!.dateStr

    // この週にかかる複数日イベントを開始日順・長い順にソート
    const mdEvents = props.events
      .filter(e => isMultiDay(e) && dateOf(e.startAt) <= weekEnd && dateOf(e.endAt) >= weekStart)
      .sort((a, b) => {
        const s = dateOf(a.startAt).localeCompare(dateOf(b.startAt))
        return s !== 0 ? s : dateOf(b.endAt).localeCompare(dateOf(a.endAt))
      })

    // レーン割り当て（重なりのない最小レーンを貪欲に選択）
    const laneOcc: Array<Array<[number, number]>> = []
    const slots: MultiDaySlot[] = []

    for (const event of mdEvents) {
      const startStr = dateOf(event.startAt)
      const endStr = dateOf(event.endAt)
      const scIdx = startStr < weekStart ? 0 : days.findIndex(d => d.dateStr === startStr)
      const ecIdx = endStr > weekEnd ? 6 : days.findIndex(d => d.dateStr === endStr)
      const startCol = scIdx < 0 ? 0 : scIdx
      const endCol = ecIdx < 0 ? 6 : ecIdx

      let lane = 0
      for (;;) {
        if (!laneOcc[lane]) laneOcc[lane] = []
        const blocked = laneOcc[lane]!.some(([s, e]) => !(endCol < s || startCol > e))
        if (!blocked) { laneOcc[lane]!.push([startCol, endCol]); break }
        lane++
      }

      slots.push({
        event, startCol, endCol, lane,
        continuesBefore: startStr < weekStart,
        continuesAfter: endStr > weekEnd,
      })
    }

    const lanesUsed = slots.length > 0
      ? Math.min(MAX_LANES, Math.max(...slots.map(s => s.lane)) + 1)
      : 0

    // 1日イベント（複数日でないもの）を日列ごとに分類
    const singleByCol = days.map(day =>
      props.events.filter(e => !isMultiDay(e) && dateOf(e.startAt) === day.dateStr),
    )

    return { days, slots, singleByCol, lanesUsed }
  }),
)

function isToday(d: string) {
  return d === new Date().toISOString().split('T')[0]
}

function fmtTime(iso: string): string {
  return iso.slice(11, 16)
}

function dateColorClass(dateStr: string, isCurrentMonth: boolean, col: number): string {
  if (isToday(dateStr)) return ''
  const holiday = !!getHoliday(dateStr)
  if (holiday || col === 0) return isCurrentMonth ? 'text-red-500' : 'text-red-300'
  if (col === 6) return isCurrentMonth ? 'text-blue-500' : 'text-blue-300'
  return isCurrentMonth ? '' : 'text-surface-400'
}

function barStyle(slot: MultiDaySlot): Record<string, string> {
  const colW = 100 / 7
  const color = slot.event.color ?? '#6366f1'
  const r = slot.continuesBefore
    ? (slot.continuesAfter ? '0px' : '0 4px 4px 0')
    : (slot.continuesAfter ? '4px 0 0 4px' : '4px')
  return {
    top: `${slot.lane * BAR_STRIDE}px`,
    left: `calc(${slot.startCol * colW}% + ${slot.continuesBefore ? '0px' : '2px'})`,
    width: `calc(${(slot.endCol - slot.startCol + 1) * colW}% - ${slot.continuesBefore || slot.continuesAfter ? '2px' : '4px'})`,
    height: `${BAR_H}px`,
    backgroundColor: color + '28',
    color,
    borderRadius: r,
  }
}

const monthLabel = computed(() => `${props.year}年${props.month}月`)
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex items-center justify-between">
      <Button icon="pi pi-chevron-left" text rounded @click="emit('prevMonth')" />
      <h2 class="text-lg font-extrabold">{{ monthLabel }}</h2>
      <Button icon="pi pi-chevron-right" text rounded @click="emit('nextMonth')" />
    </div>

    <!-- 曜日ヘッダー -->
    <div class="grid grid-cols-7 border-b border-surface-400 dark:border-surface-500">
      <div
        v-for="(d, i) in daysOfWeek"
        :key="d"
        class="py-2 text-center text-xs font-medium text-surface-500"
        :class="{ 'text-red-500': i === 0, 'text-blue-500': i === 6 }"
      >
        {{ d }}
      </div>
    </div>

    <!-- 週行 -->
    <div v-for="(week, wi) in weeks" :key="wi" class="relative">
      <!-- 日付セルグリッド -->
      <div class="grid grid-cols-7">
        <div
          v-for="(day, di) in week.days"
          :key="di"
          class="cursor-pointer overflow-hidden border-b border-r border-surface-400 p-1 transition-colors hover:bg-primary/10 dark:border-surface-500 dark:hover:bg-primary/10"
          :class="{
            'bg-surface-50/50 dark:bg-surface-800/30': !day.isCurrentMonth,
            'border-l': di === 0,
          }"
          @click="emit('dateClick', day.dateStr)"
        >
          <!-- 日付数字 -->
          <div
            class="mb-0.5 inline-flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold"
            :class="[
              { 'bg-primary text-white': isToday(day.dateStr) },
              dateColorClass(day.dateStr, day.isCurrentMonth, di),
            ]"
          >
            {{ day.date }}
          </div>
          <!-- 複数日バー用スペーサー（バー絶対レイヤーと高さを同期） -->
          <div :style="{ height: `${week.lanesUsed * BAR_STRIDE}px` }" />
          <!-- 祝日名 -->
          <div
            v-if="getHoliday(day.dateStr)"
            class="truncate text-[10px] font-medium text-red-400"
          >
            {{ getHoliday(day.dateStr) }}
          </div>
          <!-- 1日イベント -->
          <div class="space-y-0.5">
            <div
              v-for="event in week.singleByCol[di]?.slice(0, 3)"
              :key="event.id"
              class="truncate rounded px-1 py-0.5 text-xs"
              :style="{ backgroundColor: (event.color ?? '#6366f1') + '20', color: event.color ?? '#6366f1' }"
              @click.stop="emit('eventClick', event.id, event.isPersonal)"
            >
              <span v-if="!event.allDay" class="opacity-70 mr-0.5">{{ fmtTime(event.startAt) }}</span>{{ event.title }}
            </div>
          </div>
        </div>
      </div>

      <!-- 複数日バー絶対レイヤー -->
      <div
        v-if="week.slots.length"
        class="pointer-events-none absolute inset-x-0"
        :style="{ top: `${DATE_HEADER_H}px` }"
      >
        <div class="relative" :style="{ height: `${week.lanesUsed * BAR_STRIDE}px` }">
          <div
            v-for="slot in week.slots.filter(s => s.lane < MAX_LANES)"
            :key="`${slot.event.id}-w${wi}`"
            class="pointer-events-auto absolute flex cursor-pointer select-none items-center justify-center overflow-hidden text-xs font-medium"
            :style="barStyle(slot)"
            @click.stop="emit('eventClick', slot.event.id, slot.event.isPersonal)"
          >
            <i v-if="slot.continuesBefore" class="pi pi-angle-left shrink-0 text-[9px]" />
            <span class="flex-1 truncate px-1 text-center">{{ slot.event.title }}</span>
            <i v-if="slot.continuesAfter" class="pi pi-angle-right shrink-0 text-[9px]" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
