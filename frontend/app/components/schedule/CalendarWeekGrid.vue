<script setup lang="ts">
/**
 * F03.19 §6.5 バーチカル週ビュー。
 *
 * 月ビュー（CalendarGrid.vue）と同じ `filteredEvents` を受け取り、日付で束ね直して描画するだけの
 * 表示コンポーネント。**自身では一切データを取得しない**（§6.5.3・AC-13: ビュー切替でネットワーク
 * リクエストを発生させない）。
 *
 * 構造は3層:
 *   1. 日付ヘッダー（sticky top-0）
 *   2. 終日帯（sticky・日付ヘッダー直下）— allDay=true と「その日を24時間フル占有する予定」を置く
 *   3. 時間グリッド（0:00〜24:00・1時間=48px）— 時刻付き予定を重なり解決して横並びに置く
 */
import dayjs from 'dayjs'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

const { userTimezone } = useDatetime()
const { t } = useI18n()
const { getHoliday } = useHolidays()

const props = defineProps<{
  /** 表示する週の起点（日曜）の日付。'YYYY-MM-DD'。 */
  weekStart: string
  events: CalendarEventItem[]
}>()

const emit = defineEmits<{
  eventClick: [eventId: number, isPersonal: boolean]
  reflectionClick: [referenceUuid: string, referenceKind: string]
  prevWeek: []
  nextWeek: []
  today: []
}>()

// ---- レイアウト定数（§6.5.1） ----
/** 1時間の高さ(px)。全高は 24 * 48 = 1152px。 */
const HOUR_H = 48
/** 1分あたりの高さ(px)。 */
const MIN_H = HOUR_H / 60
/** スナップ境界（§6.5.4 の data-testid の minute 部分と一致させる）。 */
const SNAP_MINUTES = [0, 15, 30, 45]
const SLOT_H = HOUR_H / SNAP_MINUTES.length
/**
 * 極短時間の予定の最低高さ(px)（§6.5.2）。計算上の高さがこれを下回っても潰さない。
 * 重なり解決そのものは実時刻ベースで行い、この下限は描画高さにしか効かせない。
 */
const MIN_EVENT_H = 20
/** 日付ヘッダーの高さ(px)。終日帯の sticky オフセットに使うため固定値で持つ。 */
const HEADER_H = 48
const MINUTES_PER_DAY = 1440
const EIGHT_AM_MIN = 8 * 60

// 終日帯のバー（月ビュー CalendarGrid.vue のレーン割当をそのまま踏襲する）
const BAR_H = 18
const BAR_STRIDE = 21
const MAX_LANES = 3
const OVERFLOW_VISIBLE_LANES = MAX_LANES - 1
/** 「+N件」行の実高さ。タップ領域44px規約（FRONTEND_CODING_CONVENTION.md §3b）。 */
const OVERFLOW_ROW_H = 44

const DEFAULT_COLOR = '#6366f1'
const daysOfWeek = ['日', '月', '火', '水', '木', '金', '土']

const pad = (n: number) => String(n).padStart(2, '0')

/** 'YYYY-MM-DD' を UTC 基準の通日番号へ。日付そのものの加減算にのみ使う。 */
function dayOrdinal(dateStr: string): number {
  const y = Number(dateStr.slice(0, 4))
  const m = Number(dateStr.slice(5, 7))
  const d = Number(dateStr.slice(8, 10))
  return Math.floor(Date.UTC(y, m - 1, d) / 86400000)
}

function ordinalToDateStr(ord: number): string {
  const dt = new Date(ord * 86400000)
  return `${dt.getUTCFullYear()}-${pad(dt.getUTCMonth() + 1)}-${pad(dt.getUTCDate())}`
}

/**
 * ISO 文字列を「通日番号 * 1440 + 壁時計の分」へ変換する。
 *
 * 月ビューが `startAt.slice(0, 10)` / `slice(11, 16)` で日付・時刻を切り出しているのと同じ流儀
 * （BE から届く文字列の壁時計をそのまま採用する）。日付のみの文字列が来た場合は 0:00 とみなす
 * （握りつぶしではなく「時刻部が無い＝その日の始まり」という明示的な解釈）。
 */
function absMinutes(iso: string): number {
  const ord = dayOrdinal(iso.slice(0, 10))
  const h = Number(iso.slice(11, 13))
  const mi = Number(iso.slice(14, 16))
  const minutes = Number.isFinite(h) && Number.isFinite(mi) ? h * 60 + mi : 0
  return ord * MINUTES_PER_DAY + minutes
}

interface WeekDay {
  dateStr: string
  ord: number
  date: number
  month: number
}

const weekDays = computed<WeekDay[]>(() => {
  const startOrd = dayOrdinal(props.weekStart)
  return Array.from({ length: 7 }, (_, i) => {
    const dateStr = ordinalToDateStr(startOrd + i)
    return {
      dateStr,
      ord: startOrd + i,
      date: Number(dateStr.slice(8, 10)),
      month: Number(dateStr.slice(5, 7)),
    }
  })
})

/** 終日帯に置くバー1本（月ビューの MultiDaySlot と同じ意味）。 */
interface AllDaySlot {
  event: CalendarEventItem
  startCol: number
  endCol: number
  lane: number
  continuesBefore: boolean
  continuesAfter: boolean
}

/** 時間グリッドに置く1片。日をまたぐ予定は日ごとに分割され、複数の片が同じ event を共有する（§6.5.1b）。 */
interface TimedSegment {
  event: CalendarEventItem
  dayIndex: number
  /** その日の 0:00 からの分。 */
  startMin: number
  endMin: number
  /** 前日から続いている（上端に ▲）。 */
  continuesBefore: boolean
  /** 翌日へ続く（下端に ▼）。 */
  continuesAfter: boolean
  /** 重なり解決の結果（§6.5.2）。 */
  col: number
  cols: number
}

/**
 * §6.5.2 の重なり解決。
 *
 * 1) 開始時刻昇順に整列 2) 推移的に重なる集合＝クラスタへまとめる
 * 3) クラスタ内で空いている最小の列へ貪欲に詰める（区間グラフの貪欲彩色は最適＝使用列数が
 *    同時に重なっている最大本数に一致する）ので、使用列数をそのまま幅の分母にする
 *
 * **上限は設けない。** 10本重なれば 1/10 幅になるが、1件も落とさない（§6.5.2・P3）。
 */
function resolveOverlaps(segments: TimedSegment[]): void {
  segments.sort((a, b) =>
    a.startMin - b.startMin
    || b.endMin - a.endMin
    || a.event.uniqueKey.localeCompare(b.event.uniqueKey))

  let i = 0
  while (i < segments.length) {
    let clusterEnd = segments[i]!.endMin
    let j = i + 1
    while (j < segments.length && segments[j]!.startMin < clusterEnd) {
      clusterEnd = Math.max(clusterEnd, segments[j]!.endMin)
      j++
    }
    // クラスタ [i, j) 内で列を割り当てる。colEnds[c] = 列 c に最後に置いた片の終了時刻。
    const colEnds: number[] = []
    for (let k = i; k < j; k++) {
      const seg = segments[k]!
      let col = colEnds.findIndex(end => end <= seg.startMin)
      if (col < 0) {
        col = colEnds.length
        colEnds.push(seg.endMin)
      }
      else {
        colEnds[col] = seg.endMin
      }
      seg.col = col
    }
    for (let k = i; k < j; k++) segments[k]!.cols = colEnds.length
    i = j
  }
}

interface Classified {
  allDaySlots: AllDaySlot[]
  /** 実バーとして描くレーン数の上限（超過時のみ切り詰める）。 */
  visibleBarLaneCap: number
  /** 終日帯の確保高さ(px)。 */
  laneHeight: number
  /** 日ごとの終日帯バー非表示件数（列 di に対応。0 なら「+N件」を出さない）。 */
  laneOverflowByCol: number[]
  segmentsByDay: TimedSegment[][]
}

const classified = computed<Classified>(() => {
  const days = weekDays.value
  const weekStartOrd = days[0]!.ord
  const weekEndOrd = days[6]!.ord

  /** 終日帯行き（日単位の占有範囲）。 */
  const allDayRaw: Array<{ event: CalendarEventItem; sOrd: number; eOrd: number }> = []
  const segmentsByDay: TimedSegment[][] = Array.from({ length: 7 }, () => [])

  for (const event of props.events) {
    if (event.allDay) {
      // allDay=true は単日・複数日を問わず終日帯へ（時間軸に置くと 0:00〜23:59 の巨大な箱になる）。
      allDayRaw.push({
        event,
        sOrd: dayOrdinal(event.startAt.slice(0, 10)),
        eOrd: dayOrdinal(event.endAt.slice(0, 10)),
      })
      continue
    }

    const absStart = absMinutes(event.startAt)
    const absEnd = Math.max(absMinutes(event.endAt), absStart)
    const sOrd = Math.floor(absStart / MINUTES_PER_DAY)
    const eOrd = Math.floor(absEnd / MINUTES_PER_DAY)

    // その予定が 24時間フルで占有する日（3日以上にまたがる予定の中間日）。§6.5.1b の例外。
    const fullDays: number[] = []

    for (let ord = Math.max(sOrd, weekStartOrd); ord <= Math.min(eOrd, weekEndOrd); ord++) {
      const dayStart = ord * MINUTES_PER_DAY
      const dayEnd = dayStart + MINUTES_PER_DAY
      const s = Math.max(absStart, dayStart)
      const e = Math.min(absEnd, dayEnd)
      if (e <= s) continue // 翌日 0:00 ちょうど終了などの「占有ゼロの日」は片を作らない
      if (s <= dayStart && e >= dayEnd) {
        fullDays.push(ord)
        continue
      }
      segmentsByDay[ord - weekStartOrd]!.push({
        event,
        dayIndex: ord - weekStartOrd,
        startMin: s - dayStart,
        endMin: e - dayStart,
        continuesBefore: absStart < dayStart,
        continuesAfter: absEnd > dayEnd,
        col: 0,
        cols: 1,
      })
    }

    if (fullDays.length > 0) {
      // 24時間フル占有の日は連続しているため、1本のバーとして終日帯へ送る。
      allDayRaw.push({ event, sOrd: Math.min(...fullDays), eOrd: Math.max(...fullDays) })
    }
  }

  // ---- 終日帯のレーン割当（月ビュー CalendarGrid.vue と同一アルゴリズム） ----
  const laneOcc: Array<Array<[number, number]>> = []
  const allDaySlots: AllDaySlot[] = []

  const sorted = allDayRaw
    .filter(r => r.eOrd >= weekStartOrd && r.sOrd <= weekEndOrd)
    .sort((a, b) => (a.sOrd - b.sOrd) || (b.eOrd - a.eOrd) || a.event.uniqueKey.localeCompare(b.event.uniqueKey))

  for (const raw of sorted) {
    const startCol = Math.max(0, raw.sOrd - weekStartOrd)
    const endCol = Math.min(6, raw.eOrd - weekStartOrd)
    let lane = 0
    for (;;) {
      if (!laneOcc[lane]) laneOcc[lane] = []
      const blocked = laneOcc[lane]!.some(([s, e]) => !(endCol < s || startCol > e))
      if (!blocked) {
        laneOcc[lane]!.push([startCol, endCol])
        break
      }
      lane++
    }
    allDaySlots.push({
      event: raw.event,
      startCol,
      endCol,
      lane,
      continuesBefore: raw.sOrd < weekStartOrd,
      continuesAfter: raw.eOrd > weekEndOrd,
    })
  }

  const lanesUsedRaw = allDaySlots.length > 0 ? Math.max(...allDaySlots.map(s => s.lane)) + 1 : 0
  const hasLaneOverflow = lanesUsedRaw > MAX_LANES
  const visibleBarLaneCap = hasLaneOverflow ? OVERFLOW_VISIBLE_LANES : lanesUsedRaw
  const laneHeight = hasLaneOverflow
    ? visibleBarLaneCap * BAR_STRIDE + OVERFLOW_ROW_H
    : lanesUsedRaw * BAR_STRIDE
  const laneOverflowByCol = days.map((_, di) =>
    allDaySlots.filter(s => s.lane >= visibleBarLaneCap && s.startCol <= di && s.endCol >= di).length)

  for (const segs of segmentsByDay) resolveOverlaps(segs)

  return { allDaySlots, visibleBarLaneCap, laneHeight, laneOverflowByCol, segmentsByDay }
})

// ---- 現在時刻ライン（§6.5.1） ----
/**
 * 「今日の日付」と「今日の 0:00 からの経過分」。1分ごとに更新する。
 * 日付も一緒に持つことで、日付をまたいだ瞬間にラインが翌日の列へ移る。
 */
function readNow(): { dateStr: string; minutes: number } {
  const now = dayjs().tz(userTimezone.value)
  return { dateStr: now.format('YYYY-MM-DD'), minutes: now.hour() * 60 + now.minute() }
}

const nowState = ref(readNow())
let nowTimerId: ReturnType<typeof setInterval> | null = null

const todayColIndex = computed(() => weekDays.value.findIndex(d => d.dateStr === nowState.value.dateStr))

function isToday(dateStr: string): boolean {
  return dateStr === nowState.value.dateStr
}

// ---- 初期スクロール位置（§6.5.1） ----
const scrollEl = ref<HTMLElement | null>(null)

/**
 * 8:00 を上端に置く。ただし 8:00 より早い予定があればそれが見える位置まで戻す。
 *
 * 設計書は「当日に」と書いているが、表示中の週に今日が含まれないとき（前後の週を見ているとき）は
 * 判断材料が無くなるため、その場合は表示中の週全体の最も早い予定を見る、と読み替えている。
 */
function initialScrollTop(): number {
  const segs = classified.value.segmentsByDay
  const todayIdx = todayColIndex.value
  const pool = todayIdx >= 0 ? (segs[todayIdx] ?? []) : segs.flat()
  const earliest = pool.reduce((min, s) => Math.min(min, s.startMin), Number.POSITIVE_INFINITY)
  if (earliest < EIGHT_AM_MIN) return Math.max(0, earliest * MIN_H - SLOT_H)
  return EIGHT_AM_MIN * MIN_H
}

onMounted(() => {
  nowState.value = readNow()
  nowTimerId = setInterval(() => { nowState.value = readNow() }, 60_000)
  if (scrollEl.value) scrollEl.value.scrollTop = initialScrollTop()
})

onUnmounted(() => {
  // §6.5.1 の明示要求: 積み上がったタイマーがアンマウント済みの ref を触り続けるのを防ぐ。
  if (nowTimerId !== null) {
    clearInterval(nowTimerId)
    nowTimerId = null
  }
})

/** 「今日」ボタン（親の onToday から呼ばれる）。現在時刻ラインが見える位置へ寄せる。 */
function focusToday(): void {
  if (!scrollEl.value) return
  scrollEl.value.scrollTop = Math.max(0, nowState.value.minutes * MIN_H - HOUR_H * 2)
}

defineExpose({ focusToday })

// ---- スロット（§6.5.4 の data-testid 規約） ----
const slotRows = computed(() =>
  Array.from({ length: 24 }, (_, hour) => SNAP_MINUTES.map(minute => ({ hour, minute }))).flat())

// ---- 描画スタイル ----
function segStyle(seg: TimedSegment): Record<string, string> {
  const color = seg.event.color ?? DEFAULT_COLOR
  const widthPct = 100 / seg.cols
  return {
    top: `${(seg.startMin * MIN_H).toFixed(2)}px`,
    height: `${Math.max(MIN_EVENT_H, (seg.endMin - seg.startMin) * MIN_H).toFixed(2)}px`,
    left: `calc(${(widthPct * seg.col).toFixed(4)}% + 1px)`,
    width: `calc(${widthPct.toFixed(4)}% - 2px)`,
    backgroundColor: `${color}26`,
    // 淡い背景でも所属レイヤーが判別できるよう、左端に濃いめの縦アクセント線を置く（§6.5.2）。
    borderLeft: `3px solid ${color}`,
    color,
  }
}

function allDayBarStyle(slot: AllDaySlot): Record<string, string> {
  const colW = 100 / 7
  const color = slot.event.color ?? DEFAULT_COLOR
  const radius = slot.continuesBefore
    ? (slot.continuesAfter ? '0px' : '0 4px 4px 0')
    : (slot.continuesAfter ? '4px 0 0 4px' : '4px')
  return {
    top: `${slot.lane * BAR_STRIDE}px`,
    left: `calc(${slot.startCol * colW}% + ${slot.continuesBefore ? '0px' : '2px'})`,
    width: `calc(${(slot.endCol - slot.startCol + 1) * colW}% - ${slot.continuesBefore || slot.continuesAfter ? '2px' : '4px'})`,
    height: `${BAR_H}px`,
    backgroundColor: color,
    color: '#ffffff',
    borderRadius: radius,
  }
}

/**
 * 終日帯の「+N件」を出す日だけを列挙する。
 * v-for + v-if の同居を避けるためと、0件の日に `day-overflow-*` の幽霊要素を残さないため
 * （E2E がその存在を「溢れている」と誤読する）。
 */
const laneOverflowCells = computed(() =>
  classified.value.laneOverflowByCol
    .map((count, di) => ({ count, di, dateStr: weekDays.value[di]!.dateStr }))
    .filter(c => c.count > 0))

function allDayOverflowStyle(di: number): Record<string, string> {
  const colW = 100 / 7
  return {
    top: `${classified.value.visibleBarLaneCap * BAR_STRIDE}px`,
    left: `calc(${di * colW}% + 2px)`,
    width: `calc(${colW}% - 4px)`,
    height: `${OVERFLOW_ROW_H}px`,
  }
}

function dateColorClass(dateStr: string, col: number): string {
  if (isToday(dateStr)) return ''
  if (getHoliday(dateStr) || col === 0) return 'text-red-500'
  if (col === 6) return 'text-blue-500'
  return ''
}

function fmtTime(iso: string): string {
  return iso.slice(11, 16)
}

/** 週ラベル。月ビューの `${year}年${month}月` と同じ流儀。週が月をまたぐ場合は両方を出す。 */
const weekLabel = computed(() => {
  const days = weekDays.value
  const first = days[0]!
  const last = days[6]!
  const firstYear = Number(first.dateStr.slice(0, 4))
  const lastYear = Number(last.dateStr.slice(0, 4))
  if (firstYear === lastYear && first.month === last.month) return `${firstYear}年${first.month}月`
  if (firstYear === lastYear) return `${firstYear}年${first.month}月 - ${last.month}月`
  return `${firstYear}年${first.month}月 - ${lastYear}年${last.month}月`
})

function onEventClick(event: CalendarEventItem) {
  if (event.isReflection && event.referenceUuid && event.referenceKind) {
    emit('reflectionClick', event.referenceUuid, event.referenceKind)
    return
  }
  emit('eventClick', event.id, event.isPersonal)
}
</script>

<template>
  <div>
    <!-- ヘッダー（週ナビゲーション・§6.5.3） -->
    <div class="mb-2 flex items-center justify-between">
      <div class="flex items-center gap-1">
        <Button icon="pi pi-chevron-left" text rounded data-testid="week-prev" @click="emit('prevWeek')" />
        <h2 class="text-lg font-extrabold">{{ weekLabel }}</h2>
        <Button icon="pi pi-chevron-right" text rounded data-testid="week-next" @click="emit('nextWeek')" />
      </div>
      <Button
        :label="t('schedule.calendar.today')"
        text
        size="small"
        data-testid="calendar-today-button"
        @click="emit('today')"
      />
    </div>

    <div ref="scrollEl" class="relative max-h-[70vh] overflow-auto" data-testid="week-scroll-container">
      <div class="min-w-[620px]">
        <!-- 日付ヘッダー（sticky top-0） -->
        <div
          class="sticky top-0 z-30 flex border-b border-surface-300 bg-surface-0 dark:border-surface-600 dark:bg-surface-900"
          :style="{ height: `${HEADER_H}px` }"
        >
          <div class="sticky left-0 z-10 w-14 shrink-0 bg-surface-0 dark:bg-surface-900" />
          <div
            v-for="(day, di) in weekDays"
            :key="day.dateStr"
            class="flex flex-1 flex-col items-center justify-center border-l border-surface-200 dark:border-surface-700"
            :class="{ 'bg-primary/10': isToday(day.dateStr) }"
            :data-testid="`week-day-header-${di}`"
          >
            <span class="text-[10px] font-medium" :class="dateColorClass(day.dateStr, di)">{{ daysOfWeek[di] }}</span>
            <span
              class="inline-flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold"
              :class="[{ 'bg-primary text-white': isToday(day.dateStr) }, dateColorClass(day.dateStr, di)]"
            >{{ day.date }}</span>
          </div>
        </div>

        <!-- 終日帯（sticky・日付ヘッダー直下） -->
        <div
          data-testid="week-allday-lane"
          class="sticky z-20 flex border-b border-surface-300 bg-surface-0 dark:border-surface-600 dark:bg-surface-900"
          :style="{ top: `${HEADER_H}px` }"
        >
          <div class="sticky left-0 z-10 w-14 shrink-0 bg-surface-0 py-1 pr-1 text-right text-[10px] text-surface-500 dark:bg-surface-900">
            {{ t('schedule.calendar.week.allDay') }}
          </div>
          <div class="relative flex-1">
            <!-- 列の境界線 -->
            <div class="absolute inset-0 flex">
              <div
                v-for="(day, di) in weekDays"
                :key="`allday-col-${day.dateStr}`"
                class="flex-1 border-l border-surface-200 dark:border-surface-700"
                :class="{ 'bg-primary/5': isToday(day.dateStr) }"
                :data-testid="`week-allday-col-${di}`"
              />
            </div>
            <div class="relative" :style="{ height: `${Math.max(classified.laneHeight, BAR_H + 4)}px` }">
              <div
                v-for="slot in classified.allDaySlots.filter(s => s.lane < classified.visibleBarLaneCap)"
                :key="`allday-${slot.event.uniqueKey}`"
                :data-testid="`week-allday-event-${slot.event.uniqueKey}`"
                class="absolute flex cursor-pointer select-none items-center overflow-hidden text-xs font-medium"
                :style="allDayBarStyle(slot)"
                @click.stop="onEventClick(slot.event)"
              >
                <i v-if="slot.continuesBefore" class="pi pi-angle-left shrink-0 text-[9px]" />
                <span class="flex-1 truncate px-1">
                  <i v-if="slot.event.isTodo" class="pi pi-check-square mr-0.5 opacity-80" />{{ slot.event.title }}
                </span>
                <i v-if="slot.continuesAfter" class="pi pi-angle-right shrink-0 text-[9px]" />
              </div>
              <!-- レーン超過（月ビューと同じ「+N件」・§6.5.1） -->
              <button
                v-for="cell in laneOverflowCells"
                :key="`allday-overflow-${cell.di}`"
                type="button"
                :data-testid="`day-overflow-${cell.dateStr}`"
                class="absolute flex items-center rounded bg-surface-100 px-1 text-left text-[10px] font-medium text-surface-500 dark:bg-surface-700 dark:text-surface-300"
                :style="allDayOverflowStyle(cell.di)"
              >
                {{ t('schedule.calendar.more', { count: cell.count }) }}
              </button>
            </div>
          </div>
        </div>

        <!-- 時間グリッド -->
        <div class="flex">
          <!-- 時刻ラベル列（sticky left-0） -->
          <div class="sticky left-0 z-10 w-14 shrink-0 bg-surface-0 dark:bg-surface-900">
            <div v-for="h in 24" :key="`hour-${h}`" class="relative" :style="{ height: `${HOUR_H}px` }">
              <span
                class="absolute right-1 top-0 text-[10px] text-surface-500"
                :class="{ '-translate-y-1/2': h > 1 }"
              >{{ h - 1 }}:00</span>
            </div>
          </div>

          <!-- 7日分の列 -->
          <div class="relative flex flex-1">
            <div
              v-for="(day, di) in weekDays"
              :key="`col-${day.dateStr}`"
              class="relative flex-1 border-l border-surface-200 dark:border-surface-700"
              :class="{ 'bg-primary/5': isToday(day.dateStr) }"
              :style="{ height: `${24 * HOUR_H}px` }"
            >
              <!-- スナップスロット（§6.5.4・E2E はこの box を基準に座標を出す） -->
              <div
                v-for="cell in slotRows"
                :key="`slot-${di}-${cell.hour}-${cell.minute}`"
                :data-testid="`week-slot-${di}-${cell.hour}-${cell.minute}`"
                class="absolute inset-x-0"
                :class="cell.minute === 0
                  ? 'border-t border-surface-200 dark:border-surface-700'
                  : (cell.minute === 30 ? 'border-t border-dashed border-surface-100 dark:border-surface-800' : '')"
                :style="{ top: `${(cell.hour * 60 + cell.minute) * MIN_H}px`, height: `${SLOT_H}px` }"
              />

              <!-- 時刻付き予定 -->
              <div
                v-for="seg in classified.segmentsByDay[di]"
                :key="`seg-${seg.event.uniqueKey}-${seg.dayIndex}`"
                :data-testid="`week-event-${seg.event.uniqueKey}`"
                :data-day-index="seg.dayIndex"
                class="absolute z-10 flex cursor-pointer select-none flex-col overflow-hidden rounded-r px-1 text-[10px] leading-tight"
                :style="segStyle(seg)"
                @click.stop="onEventClick(seg.event)"
              >
                <span
                  v-if="seg.continuesBefore"
                  class="shrink-0 text-center leading-none"
                  data-testid="week-event-continues-before"
                >▲</span>
                <span class="min-w-0 flex-1 truncate">
                  <i v-if="seg.event.isTodo" class="pi pi-check-square mr-0.5 opacity-80" />
                  <span class="mr-0.5 opacity-70">{{ fmtTime(seg.event.startAt) }}</span>{{ seg.event.title }}
                </span>
                <span
                  v-if="seg.continuesAfter"
                  class="shrink-0 text-center leading-none"
                  data-testid="week-event-continues-after"
                >▼</span>
              </div>

              <!-- 現在時刻ライン（今日の列のみ・1分ごとに更新） -->
              <div
                v-if="di === todayColIndex"
                data-testid="week-now-line"
                class="pointer-events-none absolute inset-x-0 z-20 flex items-center"
                :style="{ top: `${nowState.minutes * MIN_H}px` }"
              >
                <span class="h-0.5 w-full bg-red-500" />
                <span class="absolute -left-0.5 rounded bg-red-500 px-1 text-[9px] leading-tight text-white">
                  {{ t('schedule.calendar.week.now') }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
