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
import type { Dayjs } from 'dayjs'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import { MINUTES_PER_DAY, dateToOrdinal, eventDayOccupancy, ordinalToDate, todayInTimezone } from '~/utils/calendarWeek'
import type { GridPoint } from '~/composables/useGridRangeSelect'
import {
  DEFAULT_DURATION_MIN,
  MIN_RANGE_MIN,
  snapMinutesForDensity,
  snapToBoundary,
  useGridRangeSelect,
} from '~/composables/useGridRangeSelect'

const { userTimezone } = useDatetime()
const { t, locale } = useI18n()
const { getHoliday } = useHolidays()

const props = defineProps<{
  /** 表示する週の起点（日曜）の日付。'YYYY-MM-DD'。 */
  weekStart: string
  events: CalendarEventItem[]
  /**
   * §6.6.6 現在の**作成スコープ**のレイヤー色。選択ハイライトはこの色で描き、
   * 「これから何色の予定がここに入るか」を選択中に見せる。
   * 表示フィルタ（`selectedScopes`）とは無関係（(d) との整合・P2）。
   */
  createScopeColor?: string
}>()

const emit = defineEmits<{
  eventClick: [eventId: number, isPersonal: boolean]
  reflectionClick: [referenceUuid: string, referenceKind: string]
  prevWeek: []
  nextWeek: []
  today: []
  /**
   * §6.6.5 グリッド選択の確定。**ユーザー TZ のオフセットを明示した ISO 8601** を渡す
   * （例 `2026-08-06T09:00:00+09:00`）。ナイーブ文字列は渡さない。
   * コンポーネント自身はダイアログを知らない — 親が組み立てる（既存 `dateClick` と同じ責務分離）。
   */
  rangeSelect: [startAt: string, endAt: string]
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
const EIGHT_AM_MIN = 8 * 60

// 終日帯のバー（月ビュー CalendarGrid.vue のレーン割当をそのまま踏襲する）
const BAR_H = 18
const BAR_STRIDE = 21
const MAX_LANES = 3
const OVERFLOW_VISIBLE_LANES = MAX_LANES - 1
/** 「+N件」行の実高さ。タップ領域44px規約（FRONTEND_CODING_CONVENTION.md §3b）。 */
const OVERFLOW_ROW_H = 44

const DEFAULT_COLOR = '#6366f1'

const pad = (n: number) => String(n).padStart(2, '0')

/** 通日番号 → その日の正午 UTC の Date（Intl へ渡す用。日付だけが意味を持つ）。 */
function ordinalToUtcNoon(ord: number): Date {
  return new Date(ord * 86400000 + 12 * 3600000)
}

/**
 * [4] 曜日名・週の見出しは**選択中のロケールから生成する**（i18n ルール／FE規約 §15）。
 *
 * 曜日名と年月の綴りはロケールデータであってプロダクトの文言ではないため、6言語ぶんの
 * 訳文をロケールファイルに複製するのではなく `Intl.DateTimeFormat` に委ねる。
 * ロケールが増えても追随漏れが起きない。
 */
/**
 * `timeZone: 'UTC'` は必須（[3] と同根の自己点検で発見）。
 *
 * {@link ordinalToUtcNoon} が作るのは「その日の正午 UTC」という瞬間であり、Intl に timeZone を
 * 渡さないと**端末ローカル**で解釈される。Pacific/Kiritimati(UTC+14) のような端末では
 * 正午 UTC が翌日 02:00 になり、曜日名と見出しの日付が丸ごと1日ずれる。
 * 通日番号は既にユーザー設定 TZ で確定した「暦の日付」なので、UTC で読み戻すのが正しい。
 */
const weekdayFormatter = computed(() =>
  new Intl.DateTimeFormat(locale.value, { weekday: 'short', timeZone: 'UTC' }))
const weekRangeFormatter = computed(() =>
  new Intl.DateTimeFormat(locale.value, { year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC' }))

interface WeekDay {
  dateStr: string
  ord: number
  date: number
  month: number
  /** 選択中のロケールの曜日名（[4]・直書きしない）。 */
  weekdayLabel: string
}

const weekDays = computed<WeekDay[]>(() => {
  const startOrd = dateToOrdinal(props.weekStart)
  return Array.from({ length: 7 }, (_, i) => {
    const ord = startOrd + i
    const dateStr = ordinalToDate(ord)
    return {
      dateStr,
      ord,
      date: Number(dateStr.slice(8, 10)),
      month: Number(dateStr.slice(5, 7)),
      weekdayLabel: weekdayFormatter.value.format(ordinalToUtcNoon(ord)),
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
        sOrd: dateToOrdinal(event.startAt.slice(0, 10)),
        eOrd: dateToOrdinal(event.endAt.slice(0, 10)),
      })
      continue
    }

    // その予定が 24時間フルで占有する日（3日以上にまたがる予定の中間日）。§6.5.1b の例外。
    const fullDays: number[] = []

    for (let ord = weekStartOrd; ord <= weekEndOrd; ord++) {
      // 「その日に存在するか」の判定は共通の eventDayOccupancy 一本に統一する（検分二巡目 [1]）。
      // 翌日 0:00 ちょうど終了のような占有ゼロの日はここで null になり、片を作らない。
      const occ = eventDayOccupancy(event, ord)
      if (!occ) continue
      if (occ.startMin === 0 && occ.endMin >= MINUTES_PER_DAY) {
        fullDays.push(ord)
        continue
      }
      segmentsByDay[ord - weekStartOrd]!.push({
        event,
        dayIndex: ord - weekStartOrd,
        startMin: occ.startMin,
        endMin: occ.endMin,
        // 前後の日にも占有があるなら継続記号を出す（同じ占有基準で判定する）。
        continuesBefore: eventDayOccupancy(event, ord - 1) !== null,
        continuesAfter: eventDayOccupancy(event, ord + 1) !== null,
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
  // 日付は共有ユーティリティ経由で取り、端末ローカルの日付を混ぜない（[3] と同根の事故防止）。
  return { dateStr: todayInTimezone(userTimezone.value), minutes: now.hour() * 60 + now.minute() }
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

// ---- グリッド選択による予定作成（§6.6） ----
/** 7日分の列を包む要素。クライアント座標 → (曜日, 分) の変換の基準にする。 */
const columnsEl = ref<HTMLElement | null>(null)

/**
 * クライアント座標をグリッド上の点へ落とす。
 *
 * `columnsEl` はスクロールコンテナの**内側**にあるため、`getBoundingClientRect()` の
 * `top` がスクロール量を自動的に織り込む。スクロール位置を自前で足し引きしてはならない
 * （自動スクロール中に二重に効いて選択が飛ぶ）。
 */
function resolveGridPoint(clientX: number, clientY: number): GridPoint | null {
  const el = columnsEl.value
  if (!el) return null
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return null
  const colWidth = rect.width / weekDays.value.length
  const rawCol = Math.floor((clientX - rect.left) / colWidth)
  return {
    dayIndex: Math.min(weekDays.value.length - 1, Math.max(0, rawCol)),
    minutes: (clientY - rect.top) / MIN_H,
  }
}

/**
 * 日内の分 → ユーザー TZ のオフセット付き ISO 8601（§6.6.5・R16）。24:00 は翌日 0:00 になる。
 *
 * **深夜 0:00 に分を足してはならない。** 夏時間の切替日は「その日の 0:00 のオフセット」と
 * 「選んだ時刻のオフセット」が異なるため、加算方式では選んだ壁時計とずれた瞬間が出る
 * （例: America/New_York の 2026-03-08 は 0:00 が -05:00、9:00 は -04:00。0:00 に 540分を
 * 足すと 10:00 を指してしまい、選んだ 9:00 から1時間ずれる）。
 *
 * **暦日と壁時計の時分から直接構築する**ことで、その瞬間の正しいオフセットが適用される。
 * これは `useDatetime.buildOffsetDateTimeStr()`（:104-116）と同じ流儀であり、
 * §6.6.5 が「ピッカーの Date は瞬間ではなく壁時計」と警告している罠の同根である。
 */
function toUserTzMoment(dayIndex: number, minutes: number): Dayjs {
  const day = weekDays.value[dayIndex]!
  // 24:00（= MINUTES_PER_DAY）は翌日の 0:00。暦日を繰り上げてから壁時計を組む
  // （23:60 のような不正な時刻文字列を dayjs へ渡さないため）。
  const dayCarry = Math.floor(minutes / MINUTES_PER_DAY)
  const withinDay = minutes - dayCarry * MINUTES_PER_DAY
  const dateStr = dayCarry === 0 ? day.dateStr : ordinalToDate(day.ord + dayCarry)
  const timeStr = `${pad(Math.floor(withinDay / 60))}:${pad(withinDay % 60)}:00`
  return dayjs.tz(`${dateStr}T${timeStr}`, userTimezone.value)
}

/**
 * 選択範囲を emit 用の ISO 8601 の対にする。**変換後の「瞬間」で最小長を保証する**。
 *
 * 壁時計から瞬間への写像は全単射ではない。夏時間の春の切替日には**その地域に存在しない
 * 時刻帯**があり（America/New_York の 2026-03-08 は 02:00〜03:00 が丸ごと飛ぶ）、
 * その範囲の壁時計は `dayjs.tz` によって実在時刻へ正規化される。02:30 と 03:30 は
 * **どちらも 03:30 -04:00 へ潰れる**ため、分単位では 60分あった範囲がゼロ分になる。
 *
 * `normalizeRange()`（composable 側）は**分の世界**で最小長を保証しているが、
 * この潰れは分から瞬間へ移すときに起きるので、そこでは検出できない。
 * よって**変換した後の二つの瞬間を比べ**、最小長に満たなければ終了側を延ばす。
 *
 * **原因ではなく不変条件を守る形にしている。** ギャップの検出は地域ごとの切替規則に
 * 依存し、秋の重複（同じ壁時計が二度来る）まで含めると場合分けが増える。
 * 「変換後に正の期間を保証する」なら、原因が何であれ「ゼロ分を渡さない」を守れる。
 *
 * 終了側を延ばした結果が翌日へ食い込む場合も、`add()` は**瞬間**を進めるだけなので
 * `format()` が正しい日付とオフセットで描く（§6.6.3 の 24:00 の扱いとも矛盾しない。
 * 24:00 は既に翌日 0:00 の瞬間であり、通常はここで延長は起きない）。
 */
function toUserTzRangeIso(dayIndex: number, startMin: number, endMin: number): [string, string] {
  // 分の世界での最小長（composable の normalizeRange と同一の基準を使う）。
  const minLengthMin = Math.max(MIN_RANGE_MIN, snapMinutesForDensity(HOUR_H))
  const start = toUserTzMoment(dayIndex, startMin)
  let end = toUserTzMoment(dayIndex, endMin)
  if (end.valueOf() - start.valueOf() < minLengthMin * 60_000) {
    end = start.add(minLengthMin, 'minute')
  }
  return [start.format(), end.format()]
}

/**
 * §6.7.3 `prefers-reduced-motion: reduce`。ハイライトの追従アニメーションと
 * 自動スクロールの補間を止め、即座にジャンプさせる。
 *
 * SSR では `window` が無いため既定 `false`（＝通常の動き）で描き、マウント後に実測して切り替える。
 * `matchMedia` を持たない実行環境（jsdom の一部設定）でも例外にしない。
 */
const prefersReducedMotion = ref(false)
onMounted(() => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  prefersReducedMotion.value = window.matchMedia('(prefers-reduced-motion: reduce)').matches
})

const gridSelect = useGridRangeSelect({
  // このコンポーネント自体が週ビューなので常に有効。月ビュー／アジェンダビューは
  // そもそもこの composable を使わない（§6.6.2 の「週ビュー限定」＝ AC-22b）。
  enabled: () => true,
  snapMinutes: () => snapMinutesForDensity(HOUR_H),
  resolvePoint: resolveGridPoint,
  scrollEl: () => scrollEl.value,
  reducedMotion: () => prefersReducedMotion.value,
  onCommit: (range) => {
    const [startAt, endAt] = toUserTzRangeIso(range.dayIndex, range.startMin, range.endMin)
    emit('rangeSelect', startAt, endAt)
  },
})

/** 時刻ラベルの片側。`9:00` のように時は0埋めしない（§6.6.3 の表記例）。24:00 は 24:00 のまま出す。 */
function fmtRangeBoundary(minutes: number): string {
  return `${Math.floor(minutes / 60)}:${pad(minutes % 60)}`
}

const selectionLabel = computed(() => {
  const sel = gridSelect.selection.value
  if (!sel) return ''
  return t('schedule.calendar.week.selectedRange', {
    start: fmtRangeBoundary(sel.startMin),
    end: fmtRangeBoundary(sel.endMin),
  })
})

const selectionBoxStyle = computed<Record<string, string>>(() => {
  const sel = gridSelect.selection.value
  const style: Record<string, string> = {}
  if (!sel) return style
  style.top = `${(sel.startMin * MIN_H).toFixed(2)}px`
  style.height = `${((sel.endMin - sel.startMin) * MIN_H).toFixed(2)}px`
  // 色だけに依存させない（§6.6.3・色覚多様性配慮）。塗りに加えて破線枠を持たせる。
  style.borderColor = props.createScopeColor ?? DEFAULT_COLOR
  return style
})

/** 半透明の塗り（`opacity: 0.35`）。時刻ラベルまで薄くならないよう塗りだけを別レイヤーに分ける。 */
const selectionFillStyle = computed<Record<string, string>>(() => ({
  backgroundColor: props.createScopeColor ?? DEFAULT_COLOR,
  opacity: '0.35',
}))

/**
 * 操作ヒント（§8）。タッチ端末では「長押ししてなぞる」と伝えないと、
 * §6.6.4 の長押しゲートは「反応しない機能」にしか見えない。
 */
const isCoarsePointer = ref(false)
onMounted(() => {
  isCoarsePointer.value = typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(pointer: coarse)').matches
})
const dragHintText = computed(() =>
  t(isCoarsePointer.value ? 'schedule.calendar.week.dragHintTouch' : 'schedule.calendar.week.dragHint'))

// ---- キーボード操作とアクセシビリティ（§6.7・AC-25 / AC-25b） ----

/** 1スロットの分（15分）。`SNAP_MINUTES` の刻みと必ず一致する。 */
const SLOT_MIN = 60 / SNAP_MINUTES.length

/** 現在のスナップ単位(分)。ポインタ経路と同じ導出を使う（経路で刻みが変わってはならない）。 */
function currentSnap(): number {
  return snapMinutesForDensity(HOUR_H)
}

/**
 * フォーカスセル。**グリッド全体で1タブストップ**とし（§6.7.1）、
 * DOM のフォーカスはグリッドのコンテナに置いたまま、
 * どのセルに居るかは `aria-activedescendant` で支援技術へ伝える（ロービングフォーカス）。
 * 42×96 個のセルを個別のタブストップにすると `Tab` を数千回押す羽目になる。
 */
const focusDayIndex = ref(0)
const focusMinutes = ref(EIGHT_AM_MIN)
/** グリッドがフォーカスを持っているか。フォーカスリングの表示にだけ使う。 */
const isGridFocused = ref(false)

onMounted(() => {
  // 週内に今日があればそこから始める。無ければ週頭。
  focusDayIndex.value = todayColIndex.value >= 0 ? todayColIndex.value : 0
})

/** セルの DOM id（`aria-activedescendant` の参照先）。data-testid とは別名にして衝突を避ける。 */
function slotDomId(dayIndex: number, minutes: number): string {
  return `wg-slot-${dayIndex}-${minutes}`
}

const activeDescendantId = computed(() =>
  slotDomId(focusDayIndex.value, snapToBoundary(focusMinutes.value, SLOT_MIN)))

/**
 * 読み上げ用の時刻（例 `9時00分`・§6.7.2）。
 *
 * `Intl.DateTimeFormat` の時刻書式は ja でも `9:00` になり、**読み上げでは「きゅうころんゼロゼロ」**の
 * ように読まれうる。時刻の読み上げ表現はロケールごとの文言としてキー化する（§8）。
 */
function fmtA11yTime(minutes: number): string {
  const m = Math.min(MINUTES_PER_DAY, Math.max(0, Math.floor(minutes)))
  return t('schedule.calendar.a11y.time', { hour: Math.floor(m / 60), minute: pad(m % 60) })
}

/**
 * 読み上げ用の日付（例 `8月6日 水曜日`）。
 * `timeZone: 'UTC'` は必須（{@link ordinalToUtcNoon} と同根。UTC+14 の端末で1日ずれる）。
 */
const a11yDateFormatter = computed(() =>
  new Intl.DateTimeFormat(locale.value, { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'UTC' }))

/** 1日あたりのスロット数（15分刻みで96個）。 */
const SLOTS_PER_DAY = MINUTES_PER_DAY / SLOT_MIN

/** 96個ぶんの時刻表記。全曜日で共通なので1本だけ作って使い回す。 */
const a11yTimeLabels = computed(() =>
  Array.from({ length: SLOTS_PER_DAY }, (_, i) => fmtA11yTime(i * SLOT_MIN)))

/**
 * 各 `gridcell` の `aria-label`（§6.7.2）。**時刻を必ず含める**。
 * そのスロットに掛かっている予定があればタイトルを、無ければ「空き」を添える。
 *
 * **7×96 = 672 個ぶんをまとめて computed で持つ。**
 * テンプレートから1セルずつ関数を呼ぶ形にすると、選択範囲が1段階伸びるたびに
 * 672 回の `Intl` 整形と `t()` が走り、キーを押すたびに描画が固まる
 * （実測: 並列実行下でテストが 5秒のタイムアウトに掛かった）。
 * ラベルは**週・予定・ロケールだけの関数**であり選択状態には依存しないので、
 * computed に置けばキー操作では一切再計算されない。
 */
const slotAriaLabels = computed<string[][]>(() => {
  const emptyLabel = t('schedule.calendar.a11y.emptySlot')
  const dateFormatter = a11yDateFormatter.value
  const times = a11yTimeLabels.value
  return weekDays.value.map((day, dayIndex) => {
    const dateLabel = dateFormatter.format(ordinalToUtcNoon(day.ord))
    const segs = classified.value.segmentsByDay[dayIndex] ?? []
    return Array.from({ length: SLOTS_PER_DAY }, (_, i) => {
      const minutes = i * SLOT_MIN
      const titles = segs
        .filter(seg => seg.startMin < minutes + SLOT_MIN && seg.endMin > minutes)
        .map(seg => seg.event.title)
      return t('schedule.calendar.a11y.slot', {
        date: dateLabel,
        time: times[i] ?? '',
        content: titles.length > 0 ? titles.join(' ') : emptyLabel,
      })
    })
  })
})

function slotAriaLabel(dayIndex: number, minutes: number): string {
  return slotAriaLabels.value[dayIndex]?.[minutes / SLOT_MIN] ?? ''
}

/** そのセルが選択範囲に入っているか（`aria-selected`）。 */
function isSlotSelected(dayIndex: number, minutes: number): boolean {
  const sel = gridSelect.selection.value
  if (!sel || sel.dayIndex !== dayIndex) return false
  return minutes >= sel.startMin && minutes < sel.endMin
}

/** `role="grid"` の `aria-label`（例「2026年8月2日～8日の週」・§6.7.2）。 */
const gridAriaLabel = computed(() => t('schedule.calendar.a11y.weekGrid', { range: weekLabel.value }))

/** 選択範囲の変化を `aria-live="polite"` でアナウンスする文言（§6.7.2）。 */
const selectionAnnouncement = computed(() => {
  const sel = gridSelect.selection.value
  if (!sel) return ''
  return t('schedule.calendar.a11y.selecting', {
    start: fmtA11yTime(sel.startMin),
    end: fmtA11yTime(sel.endMin),
  })
})

/**
 * フォーカスセルが見えるところまでスクロールする。
 * `reduce` 指定時は補間せず即座にジャンプする（§6.7.3）。
 */
function scrollFocusIntoView(): void {
  const el = scrollEl.value
  if (!el) return
  const top = focusMinutes.value * MIN_H
  const bottom = top + SLOT_H
  let next = el.scrollTop
  if (top < el.scrollTop) next = top
  else if (bottom > el.scrollTop + el.clientHeight) next = bottom - el.clientHeight
  if (next === el.scrollTop) return
  if (!prefersReducedMotion.value && typeof el.scrollTo === 'function') {
    el.scrollTo({ top: next, behavior: 'smooth' })
    return
  }
  el.scrollTop = next
}

/**
 * `↑` `↓`（Shift なし）: フォーカスをスナップ単位で動かす。
 * **選択中に素の矢印を押したら選択は破棄する** — 選択の起点から離れたフォーカスを残すと、
 * 次の `Enter` がどの範囲を確定するのか操作者に分からなくなる。
 */
function moveFocusMinutes(deltaMin: number): void {
  gridSelect.cancel()
  const snap = currentSnap()
  const next = snapToBoundary(focusMinutes.value, snap) + deltaMin
  focusMinutes.value = Math.min(MINUTES_PER_DAY - snap, Math.max(0, next))
  scrollFocusIntoView()
}

/** `←` `→`: 前日・翌日へ。**週の端では前週・翌週へ繰り上がる**（§6.7.1）。 */
function moveFocusDay(delta: number): void {
  gridSelect.cancel()
  const lastCol = weekDays.value.length - 1
  const next = focusDayIndex.value + delta
  if (next < 0) {
    focusDayIndex.value = lastCol
    emit('prevWeek')
    return
  }
  if (next > lastCol) {
    focusDayIndex.value = 0
    emit('nextWeek')
    return
  }
  focusDayIndex.value = next
}

/**
 * `Shift` + `↑` `↓`: 選択範囲の延長・縮小（§6.7.1）。
 * **ポインタ経路と同じ `beginAt` / `extendTo` を使う**（別系統の状態を作らない）。
 * 縮小は composable の `normalizeRange` が最小15分で止める。
 */
function extendSelection(deltaMin: number): void {
  if (!gridSelect.selection.value) {
    // まだ選択が無いときの `Shift`+`↓` は「フォーカス位置から選択を開始する」。
    // 何も無い状態での `Shift`+`↑`（縮小）は対象が無いので何もしない。
    if (deltaMin <= 0) return
    gridSelect.beginAt({ dayIndex: focusDayIndex.value, minutes: focusMinutes.value })
    scrollFocusIntoView()
    return
  }
  const sel = gridSelect.selection.value
  gridSelect.extendTo(Math.min(MINUTES_PER_DAY, Math.max(0, sel.endMin + deltaMin)))
  scrollFocusIntoView()
}

/**
 * `Enter` / `Space`: 範囲選択中ならその範囲で、そうでなければフォーカス位置から既定60分で確定する。
 *
 * **どちらも composable の `commit()` へ落とす。** ドラッグ経路と出口を共有することが、
 * 「経路が違うだけで到達点は同一」（§6.7.1 末尾）の実装上の担保である。
 */
function commitFromKeyboard(): void {
  if (gridSelect.selection.value) {
    gridSelect.commit()
    return
  }
  gridSelect.beginAt({ dayIndex: focusDayIndex.value, minutes: focusMinutes.value })
  gridSelect.extendTo(focusMinutes.value + DEFAULT_DURATION_MIN)
  gridSelect.commit()
}

function onGridKeydown(event: KeyboardEvent): void {
  const snap = currentSnap()
  switch (event.key) {
    case 'ArrowDown':
      if (event.shiftKey) extendSelection(snap)
      else moveFocusMinutes(snap)
      break
    case 'ArrowUp':
      if (event.shiftKey) extendSelection(-snap)
      else moveFocusMinutes(-snap)
      break
    case 'ArrowLeft':
      moveFocusDay(-1)
      break
    case 'ArrowRight':
      moveFocusDay(1)
      break
    case 'Enter':
    case ' ':
      commitFromKeyboard()
      break
    case 'Escape':
      gridSelect.cancel()
      break
    default:
      // 未対応キーは既定動作のまま通す（Tab で抜けられなくなるのを防ぐ）。
      return
  }
  // ここへ来たキーはすべて処理済み。ページスクロール等の既定動作を止める。
  event.preventDefault()
}

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

/**
 * [2] 分割片が表示する時刻は、**その片自身の開始位置**から出す。
 *
 * 元の `startAt` をそのまま出すと、22:00〜翌02:00 の予定の翌日側が 00:00 の位置にありながら
 * 「22:00」と表示される（Codex 検分 [2]）。§6.5.1b が「1つの予定として扱う」と言うのは
 * 詳細を開く経路（uniqueKey 共有）の話であり、表示時刻まで元のものにせよという意味ではない。
 */
function fmtSegmentTime(seg: TimedSegment): string {
  // startMin は秒を小数として持ちうる（[P2]・判定はミリ秒、描画は分）。
  // 分の表示では必ず切り捨てる（floor を落とすと "0:0.5" のような文字列になる）。
  const totalMinutes = Math.floor(seg.startMin)
  return `${pad(Math.floor(totalMinutes / 60))}:${pad(totalMinutes % 60)}`
}

/**
 * 週の見出し（[4]）。「年」「月」の綴りも区切りも**選択中のロケール**に委ねる。
 * ja なら「2026年8月2日～8日」、en なら「August 2 – 8, 2026」のように出る。
 * `formatRange` が無い実行環境では両端を個別に整形して繋ぐ（機能を落とさないための代替経路）。
 */
const weekLabel = computed(() => {
  const days = weekDays.value
  const from = ordinalToUtcNoon(days[0]!.ord)
  const to = ordinalToUtcNoon(days[6]!.ord)
  const fmt = weekRangeFormatter.value
  if (typeof fmt.formatRange === 'function') return fmt.formatRange(from, to)
  return `${fmt.format(from)} – ${fmt.format(to)}`
})

// ---- 日別ポップオーバー（§6.2。月ビューと共有する ScheduleDayDetailPopover） ----
// 終日帯のレーン超過で省かれた予定を、週ビュー内で必ず開けるようにする。
// 「+N件」を出すだけで開けないのでは、予定が無言で消える欠陥を塞いだことにならない。
const dayPopover = ref<{ open: (dateStr: string, ev: Event) => void; close: () => void } | null>(null)

function openDayOverflow(dateStr: string, ev: Event) {
  dayPopover.value?.open(dateStr, ev)
}

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
      <div class="flex items-center gap-2">
        <!-- 操作ヒント（§8）。ポインタ種別で文言を出し分ける（モバイルは長押しが前提のため） -->
        <span class="hidden text-[10px] text-surface-500 sm:inline" data-testid="week-drag-hint">{{ dragHintText }}</span>
        <Button
          :label="t('schedule.calendar.today')"
          text
          size="small"
          data-testid="calendar-today-button"
          @click="emit('today')"
        />
      </div>
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
            <span class="text-[10px] font-medium" :class="dateColorClass(day.dateStr, di)">{{ day.weekdayLabel }}</span>
            <span
              class="inline-flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold"
              :class="[{ 'bg-primary text-white': isToday(day.dateStr) }, dateColorClass(day.dateStr, di)]"
            >{{ day.date }}</span>
          </div>
        </div>

        <!-- 終日帯（sticky・日付ヘッダー直下） -->
        <!-- data-range-select-ignore: 終日帯は時刻を持たないため、ここから始まるジェスチャは拾わない（§6.6.2） -->
        <div
          data-testid="week-allday-lane"
          data-range-select-ignore
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
            <!--
              §6.7.2 終日帯は `role="grid"` の外に置き、独立した `role="list"` とする。
              時間軸を持たないため grid の行列モデルに乗らない。
            -->
            <div
              role="list"
              :aria-label="t('schedule.calendar.a11y.allDayList')"
              class="relative"
              :style="{ height: `${Math.max(classified.laneHeight, BAR_H + 4)}px` }"
            >
              <div
                v-for="slot in classified.allDaySlots.filter(s => s.lane < classified.visibleBarLaneCap)"
                :key="`allday-${slot.event.uniqueKey}`"
                :data-testid="`week-allday-event-${slot.event.uniqueKey}`"
                role="listitem"
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
                role="listitem"
                :data-testid="`day-overflow-${cell.dateStr}`"
                class="absolute flex items-center rounded bg-surface-100 px-1 text-left text-[10px] font-medium text-surface-500 dark:bg-surface-700 dark:text-surface-300"
                :style="allDayOverflowStyle(cell.di)"
                @click.stop="openDayOverflow(cell.dateStr, $event)"
              >
                {{ t('schedule.calendar.more', { count: cell.count }) }}
              </button>
            </div>
          </div>
        </div>

        <!-- 時間グリッド -->
        <div class="flex">
          <!-- 時刻ラベル列（sticky left-0） -->
          <!-- data-range-select-ignore: スクロール操作と衝突するため選択の起点にしない（§6.6.2） -->
          <div data-range-select-ignore class="sticky left-0 z-10 w-14 shrink-0 bg-surface-0 dark:bg-surface-900">
            <div v-for="h in 24" :key="`hour-${h}`" class="relative" :style="{ height: `${HOUR_H}px` }">
              <span
                class="absolute right-1 top-0 text-[10px] text-surface-500"
                :class="{ '-translate-y-1/2': h > 1 }"
              >{{ h - 1 }}:00</span>
            </div>
          </div>

          <!-- 7日分の列（§6.6 グリッド選択の受け口） -->
          <!--
            `touch-action` は**選択モード中だけ** none にする（§6.6.4-4）。
            常時 none にするとタッチでの縦スクロールが完全に死ぬ。
          -->
          <!--
            §6.7.1 グリッド全体で **1タブストップ**（ロービングフォーカス）。
            どのセルに居るかは `aria-activedescendant` で伝える。
            42×96 個のセルを個別のタブストップにすると `Tab` を数千回押す羽目になる。
          -->
          <div
            ref="columnsEl"
            data-testid="week-grid-columns"
            role="grid"
            tabindex="0"
            :aria-label="gridAriaLabel"
            :aria-activedescendant="activeDescendantId"
            class="relative flex flex-1 outline-none focus-visible:ring-2 focus-visible:ring-primary"
            :style="{ touchAction: gridSelect.isSelecting.value ? 'none' : 'auto' }"
            @pointerdown="gridSelect.onPointerDown"
            @touchstart="gridSelect.onTouchStart"
            @keydown="onGridKeydown"
            @focus="isGridFocused = true"
            @blur="isGridFocused = false"
          >
            <div
              v-for="(day, di) in weekDays"
              :key="`col-${day.dateStr}`"
              role="row"
              class="relative flex-1 border-l border-surface-200 dark:border-surface-700"
              :class="{ 'bg-primary/5': isToday(day.dateStr) }"
              :style="{ height: `${24 * HOUR_H}px` }"
            >
              <!-- スナップスロット（§6.5.4・E2E はこの box を基準に座標を出す） -->
              <div
                v-for="cell in slotRows"
                :id="slotDomId(di, cell.hour * 60 + cell.minute)"
                :key="`slot-${di}-${cell.hour}-${cell.minute}`"
                :data-testid="`week-slot-${di}-${cell.hour}-${cell.minute}`"
                role="gridcell"
                :aria-label="slotAriaLabel(di, cell.hour * 60 + cell.minute)"
                :aria-selected="isSlotSelected(di, cell.hour * 60 + cell.minute)"
                class="absolute inset-x-0"
                :class="[
                  cell.minute === 0
                    ? 'border-t border-surface-200 dark:border-surface-700'
                    : (cell.minute === 30 ? 'border-t border-dashed border-surface-100 dark:border-surface-800' : ''),
                  isGridFocused && di === focusDayIndex && cell.hour * 60 + cell.minute === snapToBoundary(focusMinutes, SLOT_MIN)
                    ? 'ring-2 ring-inset ring-primary'
                    : '',
                ]"
                :style="{ top: `${(cell.hour * 60 + cell.minute) * MIN_H}px`, height: `${SLOT_H}px` }"
              />

              <!-- 時刻付き予定 -->
              <div
                v-for="seg in classified.segmentsByDay[di]"
                :key="`seg-${seg.event.uniqueKey}-${seg.dayIndex}`"
                :data-testid="`week-event-${seg.event.uniqueKey}`"
                :data-day-index="seg.dayIndex"
                data-range-select-ignore
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
                  <span class="mr-0.5 opacity-70">{{ fmtSegmentTime(seg) }}</span>{{ seg.event.title }}
                </span>
                <span
                  v-if="seg.continuesAfter"
                  class="shrink-0 text-center leading-none"
                  data-testid="week-event-continues-after"
                >▼</span>
              </div>

              <!--
                選択ハイライト（§6.6.3）。**選択を開始した列にだけ**描く。
                横へ指が移っても列は変わらない（斜めに引いて複数日の予定が生まれるのは事故）。
              -->
              <div
                v-if="gridSelect.selection.value && gridSelect.selection.value.dayIndex === di"
                data-testid="week-selection-highlight"
                aria-hidden="true"
                class="pointer-events-none absolute inset-x-0 z-20 flex items-center justify-center overflow-hidden rounded border-2 border-dashed"
                :class="prefersReducedMotion ? '' : 'transition-[top,height] duration-100 ease-out'"
                :style="selectionBoxStyle"
              >
                <span class="absolute inset-0" :style="selectionFillStyle" />
                <span class="relative px-1 text-[10px] font-bold leading-tight text-surface-900 dark:text-surface-0">
                  {{ selectionLabel }}
                </span>
              </div>

              <!-- 現在時刻ライン（今日の列のみ・1分ごとに更新） -->
              <div
                v-if="di === todayColIndex"
                data-testid="week-now-line"
                aria-hidden="true"
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

    <!--
      §6.7.2 選択範囲の変化のアナウンス。ハイライト（視覚表現）の等価物であり、
      画面には出さないが読み上げには乗せる。`polite` は操作の邪魔をしない。
    -->
    <p class="sr-only" aria-live="polite" data-testid="week-selection-announcement">{{ selectionAnnouncement }}</p>

    <!-- 日別ポップオーバー（§6.2。終日帯の「+N件」から開く。月ビューと共有） -->
    <ScheduleDayDetailPopover
      ref="dayPopover"
      :events="events"
      @row-open="onEventClick"
    />
  </div>
</template>
