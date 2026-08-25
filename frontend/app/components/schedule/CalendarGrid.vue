<script setup lang="ts">
import dayjs from 'dayjs'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

const { userTimezone } = useDatetime()
const { t } = useI18n()

const props = withDefaults(defineProps<{
  year: number
  month: number
  events: CalendarEventItem[]
  // 「今日」ボタン（§6.3/AC-12d）の表示可否。CalendarGrid はチーム/組織スケジュール画面や
  // ダッシュボードウィジェットでも再利用されており、それらの親は today イベントを購読していない。
  // 既定 false とし、押しても無反応なボタンをそれらの画面に出さない。統合カレンダー（pages/calendar.vue）
  // でのみ明示的に true を渡して有効化する契約とする。
  showTodayButton?: boolean
}>(), {
  showTodayButton: false,
})

const emit = defineEmits<{
  dateClick: [date: string]
  eventClick: [eventId: number, isPersonal: boolean]
  // reflection 等 UUID 主キードメインの印クリック（§6.2/AC-21・id 非依存）。
  reflectionClick: [referenceUuid: string, referenceKind: string]
  prevMonth: []
  nextMonth: []
  // 「今日」ボタン（§6.3/AC-12d）。月移動は親（currentYear/currentMonth の所有者）に委ねる。
  today: []
}>()

/**
 * イベントクリックを種別で振り分ける（§6.2/AC-21）。
 *
 * reflection 行は id=null/-1 で数値 id ルックアップが壊れるため、referenceUuid+referenceKind で
 * 親へ通知する。schedule/todo 行は従来どおり数値 id で eventClick を発火する。
 */
function onEventClick(event: CalendarEventItem) {
  if (event.isReflection && event.referenceUuid && event.referenceKind) {
    emit('reflectionClick', event.referenceUuid, event.referenceKind)
    return
  }
  emit('eventClick', event.id, event.isPersonal)
}

const { getHoliday } = useHolidays()
const daysOfWeek = ['日', '月', '火', '水', '木', '金', '土']

// レイアウト定数
const DATE_HEADER_H = 30  // p-1(4) + h-6(24) + mb-0.5(2) = 30px
const BAR_H = 18
const BAR_STRIDE = 21     // バー高さ + 3px ギャップ
// 単日イベントの既定表示件数（§6.2）。3件以下は全件表示、4件以上は先頭2件＋「他N件」。
const SINGLE_VISIBLE = 2
const SINGLE_VISIBLE_THRESHOLD = 3
// 複数日バーのレーン数がこの本数以下なら全バー表示（§6.2 の表）。
const MAX_LANES = 3
// 超過（4本以上）時にのみ実バーとして表示するレーン数。溢れた残りをレーン2の位置に
// 日ごとの「+N件」として出す。3本ちょうどのときは超過扱いにせず MAX_LANES 全てを表示する
// （閾値と描画レーン数は別物。同一視すると3本ちょうどの週で3本目が消える表示退行になる）。
const OVERFLOW_VISIBLE_LANES = MAX_LANES - 1

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
  // 実バーとして表示するレーン数の上限（この週のレーン数が MAX_LANES 以下ならレーン数そのもの＝
  // 全バー表示、超過時のみ OVERFLOW_VISIBLE_LANES に切り詰める）。
  visibleBarLaneCap: number
  // 日ごとの複数日バー非表示件数（§6.2・AC-12b）。列 di に対応。0 なら「+N件」を出さない。
  laneOverflowByCol: number[]
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

    // 週のレーン数が MAX_LANES(3) 以下なら全バー表示、超過（4本以上）時のみ
    // OVERFLOW_VISIBLE_LANES(2) 本に切り詰めて残りを「+N件」に回す（§6.2 の表）。
    const lanesUsedRaw = slots.length > 0 ? Math.max(...slots.map(s => s.lane)) + 1 : 0
    const hasLaneOverflow = lanesUsedRaw > MAX_LANES
    const visibleBarLaneCap = hasLaneOverflow ? OVERFLOW_VISIBLE_LANES : lanesUsedRaw
    // 表示に確保する高さ（レーン）。超過時は実バー ＋「+N件」行の1行分を追加で確保する。
    const lanesUsed = hasLaneOverflow ? OVERFLOW_VISIBLE_LANES + 1 : lanesUsedRaw

    // 日ごとの非表示バー件数を数える（週で1つの数字にすると日によって嘘になるため必ず日単位）。
    const laneOverflowByCol = days.map((_, di) =>
      slots.filter(s => s.lane >= visibleBarLaneCap && s.startCol <= di && s.endCol >= di).length,
    )

    // 1日イベント（複数日でないもの）を日列ごとに分類
    const singleByCol = days.map(day =>
      props.events.filter(e => !isMultiDay(e) && dateOf(e.startAt) === day.dateStr),
    )

    return { days, slots, singleByCol, lanesUsed, visibleBarLaneCap, laneOverflowByCol }
  }),
)

/** 単日イベントの表示分（§6.2）。3件以下は全件、4件以上は先頭2件のみ。 */
function visibleSingleEvents(events: CalendarEventItem[] | undefined): CalendarEventItem[] {
  const list = events ?? []
  return list.length > SINGLE_VISIBLE_THRESHOLD ? list.slice(0, SINGLE_VISIBLE) : list
}

/** 単日イベントの「他N件」件数（0 なら非表示）。 */
function singleOverflowCount(events: CalendarEventItem[] | undefined): number {
  const list = events ?? []
  return list.length > SINGLE_VISIBLE_THRESHOLD ? list.length - SINGLE_VISIBLE : 0
}

function isToday(d: string) {
  return d === dayjs().tz(userTimezone.value).format('YYYY-MM-DD')
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
    backgroundColor: color,
    color: '#ffffff',
    borderRadius: r,
  }
}

/** 複数日バーのレーン超過「+N件」チップの位置（該当日1列分・§6.2）。行はその週の実バー本数の直後。 */
function laneOverflowStyle(di: number, visibleBarLaneCap: number): Record<string, string> {
  const colW = 100 / 7
  return {
    top: `${visibleBarLaneCap * BAR_STRIDE}px`,
    left: `calc(${di * colW}% + 2px)`,
    width: `calc(${colW}% - 4px)`,
    height: `${BAR_H}px`,
  }
}

const monthLabel = computed(() => `${props.year}年${props.month}月`)

// ---- 日別ポップオーバー（§6.2・AC-12/AC-12b・data-testid="day-detail-popover"） ----
const dayPopover = ref<{ show: (ev: Event) => void; hide: () => void } | null>(null)
const popoverDateStr = ref('')

/** ポップオーバー対象日に掛かる予定の全件（単日・複数日バーの両方を含む）。 */
const popoverEvents = computed<CalendarEventItem[]>(() => {
  if (!popoverDateStr.value) return []
  const d = popoverDateStr.value
  return props.events.filter(e => dateOf(e.startAt) <= d && dateOf(e.endAt) >= d)
})

function openDayOverflow(dateStr: string, ev: Event) {
  popoverDateStr.value = dateStr
  dayPopover.value?.show(ev)
}

/** ポップオーバー内の行クリック（ScheduleListRow の `open` イベント）を種別で振り分ける。 */
function onPopoverRowOpen(event: CalendarEventItem) {
  dayPopover.value?.hide()
  if (event.isReflection && event.referenceUuid && event.referenceKind) {
    emit('reflectionClick', event.referenceUuid, event.referenceKind)
    return
  }
  onEventClick(event)
}

// ---- 「今日」ボタン（§6.3・AC-12d） ----
// 月グリッドの日付セル DOM 要素（フォーカスリング付与対象）。dateStr をキーに保持する。
const dayCellEls = new Map<string, HTMLElement>()

function setDayCellRef(el: Element | null, dateStr: string) {
  if (el instanceof HTMLElement) dayCellEls.set(dateStr, el)
  else dayCellEls.delete(dateStr)
}

/** 今日のセルへフォーカスを移す（既に当月表示中でも必ず呼ばれる。AC-12d）。 */
function focusToday() {
  const key = dayjs().tz(userTimezone.value).format('YYYY-MM-DD')
  dayCellEls.get(key)?.focus()
}

defineExpose({ focusToday })
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-1">
        <Button icon="pi pi-chevron-left" text rounded @click="emit('prevMonth')" />
        <h2 class="text-lg font-extrabold">{{ monthLabel }}</h2>
        <Button icon="pi pi-chevron-right" text rounded @click="emit('nextMonth')" />
      </div>
      <!-- 「今日」ボタン（§6.3・AC-12d）: 既に当月表示中でも押すたびフォーカスは今日のセルへ移る。
           showTodayButton=true の呼び出し元（統合カレンダー）でのみ表示する。 -->
      <Button
        v-if="showTodayButton"
        :label="t('schedule.calendarGrid.today')"
        text
        size="small"
        data-testid="calendar-today-button"
        @click="emit('today')"
      />
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
          :ref="(el) => setDayCellRef(el as Element | null, day.dateStr)"
          tabindex="-1"
          class="cursor-pointer overflow-hidden border-b border-r border-surface-400 p-1 transition-colors hover:bg-primary/10 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-primary dark:border-surface-500 dark:hover:bg-primary/10"
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
              v-for="event in visibleSingleEvents(week.singleByCol[di])"
              :key="event.uniqueKey"
              class="flex items-center rounded px-1 py-0.5 text-xs gap-0.5"
              :style="{ backgroundColor: (event.color ?? '#6366f1') + '20', color: event.color ?? '#6366f1' }"
              @click.stop="onEventClick(event)"
            >
              <!-- チーム・組織スコープのみアイコンを表示 -->
              <span
                v-if="event.scopeType && event.scopeType !== 'PERSONAL'"
                class="inline-flex items-center justify-center w-3.5 h-3.5 rounded-full overflow-hidden bg-white/30 flex-shrink-0"
              >
                <img v-if="event.scopeIconUrl" :src="event.scopeIconUrl" class="w-full h-full object-cover" alt="" >
                <span v-else class="text-[8px] font-bold leading-none">{{ event.scopeName?.charAt(0) }}</span>
              </span>
              <span class="min-w-0 flex-1 truncate">
                <i v-if="event.isTodo" class="pi pi-check-square mr-0.5 opacity-80" />
                <span v-if="!event.allDay" class="opacity-70 mr-0.5">{{ fmtTime(event.startAt) }}</span>{{ event.title }}
              </span>
              <ScheduleTargetAudience
                v-if="event.scopeType && event.scopeType !== 'PERSONAL' && !event.isTodo"
                :target-mode="event.targetMode"
                :target-count="event.targetCount"
                :targets="event.targets"
                compact
                class="ml-auto shrink-0"
              />
            </div>
            <!-- 単日イベント溢れ分（§6.2・AC-12）。タップ領域は44px以上を確保する
                 （FRONTEND_CODING_CONVENTION.md §3b）。文字サイズ・見た目の詰め方は変えず、
                 min-h/flex でパディング側だけ拡大する（既存の通知既読ボタン等と同じパターン）。 -->
            <button
              v-if="singleOverflowCount(week.singleByCol[di]) > 0"
              type="button"
              :data-testid="`day-overflow-${day.dateStr}`"
              class="flex min-h-11 w-full items-center truncate rounded px-1 text-left text-[10px] font-medium text-surface-500 hover:bg-surface-100 dark:text-surface-400 dark:hover:bg-surface-700"
              @click.stop="openDayOverflow(day.dateStr, $event)"
            >
              {{ t('schedule.calendarGrid.dayOverflow', { count: singleOverflowCount(week.singleByCol[di]) }) }}
            </button>
          </div>
        </div>
      </div>

      <!-- 複数日バー絶対レイヤー -->
      <div
        v-if="week.slots.length"
        class="pointer-events-none absolute inset-x-0 z-10"
        :style="{ top: `${DATE_HEADER_H}px` }"
      >
        <div class="relative" :style="{ height: `${week.lanesUsed * BAR_STRIDE}px` }">
          <div
            v-for="slot in week.slots.filter(s => s.lane < week.visibleBarLaneCap)"
            :key="`${slot.event.uniqueKey}-w${wi}`"
            class="pointer-events-auto absolute flex cursor-pointer select-none items-center overflow-hidden text-xs font-medium"
            :style="barStyle(slot)"
            @click.stop="onEventClick(slot.event)"
          >
            <i v-if="slot.continuesBefore" class="pi pi-angle-left shrink-0 text-[9px]" />
            <!-- チーム・組織スコープのみアイコンを表示 -->
            <span
              v-if="slot.event.scopeType && slot.event.scopeType !== 'PERSONAL'"
              class="inline-flex items-center justify-center w-4 h-4 rounded-full overflow-hidden bg-white/30 flex-shrink-0 mx-0.5"
            >
              <img v-if="slot.event.scopeIconUrl" :src="slot.event.scopeIconUrl" class="w-full h-full object-cover" alt="" >
              <span v-else class="text-[9px] font-bold leading-none">{{ slot.event.scopeName?.charAt(0) }}</span>
            </span>
            <span class="flex-1 truncate px-0.5">
              <i v-if="slot.event.isTodo" class="pi pi-check-square mr-0.5 opacity-80" />{{ slot.event.title }}
            </span>
            <ScheduleTargetAudience
              v-if="slot.event.scopeType && slot.event.scopeType !== 'PERSONAL' && !slot.event.isTodo"
              :target-mode="slot.event.targetMode"
              :target-count="slot.event.targetCount"
              :targets="slot.event.targets"
              compact
              class="mr-1 shrink-0"
            />
            <i v-if="slot.continuesAfter" class="pi pi-angle-right shrink-0 text-[9px]" />
          </div>

          <!-- 複数日バーのレーン超過「+N件」（§6.2・AC-12b・日ごとに数える）。
               見た目の高さ（BAR_H=18px）はバー行との整列上変えられないため、タップ領域は
               `lane-overflow-hit-area` の疑似要素で不可視に拡張する（見た目は変えない）。 -->
          <button
            v-for="(count, di) in week.laneOverflowByCol"
            v-show="count > 0"
            :key="`overflow-w${wi}-${di}`"
            type="button"
            :data-testid="`day-overflow-${week.days[di]?.dateStr}`"
            class="lane-overflow-hit-area pointer-events-auto absolute rounded bg-surface-100 px-1 text-left text-[10px] font-medium text-surface-500 hover:bg-surface-200 dark:bg-surface-700 dark:text-surface-300 dark:hover:bg-surface-600"
            :style="laneOverflowStyle(di, week.visibleBarLaneCap)"
            @click.stop="openDayOverflow(week.days[di]!.dateStr, $event)"
          >
            {{ t('schedule.calendarGrid.laneOverflow', { count }) }}
          </button>
        </div>
      </div>
    </div>

    <!-- 日別ポップオーバー（§6.2・AC-12/AC-12b） -->
    <Popover ref="dayPopover">
      <div data-testid="day-detail-popover" class="flex flex-col" style="min-width: 260px; max-width: 320px">
        <div class="px-2 pb-1 text-xs font-semibold text-surface-500">
          {{ t('schedule.calendarGrid.dayDetailTitle', { date: popoverDateStr }) }}
        </div>
        <div class="max-h-80 overflow-y-auto">
          <ScheduleListRow
            v-for="event in popoverEvents"
            :key="event.uniqueKey"
            :event="event"
            scope-type="team"
            :scope-id="''"
            @open="onPopoverRowOpen(event)"
          />
        </div>
      </div>
    </Popover>
  </div>
</template>

<style scoped>
/*
 * 複数日バーのレーン超過「+N件」（AC-12b）のタップ領域拡張（FRONTEND_CODING_CONVENTION.md §3b）。
 * 見た目のボックスは BAR_H=18px のまま（バー行との整列を保つため変えられない）だが、
 * ::before の透明な疑似要素を負のinsetで重ねることで、視覚サイズを変えずにヒット領域だけ
 * 44x44px 相当まで広げる（生成コンテンツはホスト要素の一部としてヒットテストされるため、
 * クリックはボタン自身のイベントとして届く）。
 */
.lane-overflow-hit-area {
  position: absolute;
}
.lane-overflow-hit-area::before {
  content: '';
  position: absolute;
  inset: -13px -4px;
}
</style>
