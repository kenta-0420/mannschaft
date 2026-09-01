<script setup lang="ts">
/**
 * F03.19 §6.1/§6.2 アジェンダ（リスト）ビュー（W3-b）。
 *
 * 表示中の月の全予定を、**日付見出し＋時系列の一次元リスト**として描く。行描画自体は
 * {@link ScheduleListRow} に委譲し重複実装しない（`ScheduleMobileListView.vue` と同じ流儀）。
 *
 * 「+N件」は一切出さない（AC-13b）。一次元リストである以上、月ビュー・週ビューのような
 * レーン数／件数の切り捨てが構造的に発生しない。これが P3（取りこぼし不安）に対する
 * 最も強い保証であり、月ビュー・週ビューの「+N件」を押すのが億劫なときの逃げ道になる。
 *
 * ドラッグ選択（§6.6）は週ビュー限定（AC-22b）。本コンポーネントはポインタ／タッチの
 * ドラッグ系イベントを一切購読しないため、ドラッグしても何も起きない — これは
 * 「無効化する実装」ではなく「実装しない」ことによる保証であり、対処療法ではない。
 */
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import { dateToOrdinal, eventOccupiesDate, ordinalToDate, todayInTimezone } from '~/utils/calendarWeek'

const props = defineProps<{
  /** 表示中の月の年（月ナビラベル・期間算出用）。 */
  year: number
  /** 表示中の月（1-12）。 */
  month: number
  /** 表示中の期間に関わりうる全予定。期間内の絞り込みは本コンポーネント側で行う。 */
  events: CalendarEventItem[]
  /** {@link ScheduleListRow} へ渡す出欠回答 API 呼び出し先スコープ。 */
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

const emit = defineEmits<{
  prevMonth: []
  nextMonth: []
  today: []
  eventClick: [eventId: number, isPersonal: boolean]
  reflectionClick: [referenceUuid: string, referenceKind: string]
  /** 出欠回答成功時（`ScheduleListRow` からの中継。親はここで一覧を再取得する）。 */
  responded: []
}>()

const { t, locale } = useI18n()
const { userTimezone } = useDatetime()

/**
 * 月ナビの見出し（例: 「2026年8月」/ "August 2026"）は選択中のロケールから生成する
 * （i18n ルール・`ScheduleMobileListView.vue`/`CalendarWeekGrid.vue` と同じ流儀）。
 * `timeZone: 'UTC'` は必須（W3-a が踏んだ罠と同根。省くと UTC+14 の端末で月がずれる）。
 */
const periodFormatter = computed(() =>
  new Intl.DateTimeFormat(locale.value, { year: 'numeric', month: 'long', timeZone: 'UTC' }))
const periodLabel = computed(() =>
  periodFormatter.value.format(new Date(Date.UTC(props.year, props.month - 1, 1))))

/** 日付見出しの表示（曜日つき）。ロケールから生成し直書きしない。 */
const dayHeaderFormatter = computed(() =>
  new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric', weekday: 'short', timeZone: 'UTC' }))

function daysInMonth(year: number, month: number): number {
  // month は 1-12。翌月の0日目 = 当月末日。
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

interface AgendaDay {
  dateStr: string
  ord: number
  label: string
  isToday: boolean
  events: CalendarEventItem[]
}

/**
 * 表示中の月の日付ごとに、その日を占有する予定を束ねる（{@link eventOccupiesDate} が唯一の判定基準
 * — 時間グリッド・日別ポップオーバーと同じ関数を使い、基準の食い違いを作らない）。
 * 予定0件の日は見出しごと出さない（一次元リストで空の日を並べても情報が無い）。
 * 各日の中では開始時刻の実際の瞬間（Date.parse）昇順に並べる — ISO 文字列のまま
 * localeCompare すると、オフセットの異なる予定（+09:00 と Z 等）で前後関係が食い違う
 * （`pages/calendar.vue` の sortedFilteredEvents と同じ理由）。
 */
const agendaDays = computed<AgendaDay[]>(() => {
  const today = todayInTimezone(userTimezone.value)
  const count = daysInMonth(props.year, props.month)
  const firstOrd = dateToOrdinal(`${props.year}-${String(props.month).padStart(2, '0')}-01`)
  const days: AgendaDay[] = []
  for (let i = 0; i < count; i++) {
    const ord = firstOrd + i
    const dateStr = ordinalToDate(ord)
    const dayEvents = props.events
      .filter(ev => eventOccupiesDate(ev, dateStr))
      .sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt))
    if (dayEvents.length === 0) continue
    days.push({
      dateStr,
      ord,
      label: dayHeaderFormatter.value.format(new Date(ord * 86400000 + 12 * 3600000)),
      isToday: dateStr === today,
      events: dayEvents,
    })
  }
  return days
})

const isEmpty = computed(() => agendaDays.value.length === 0)

const containerEl = ref<HTMLElement | null>(null)

/**
 * 「今日」ボタン（親の onToday から呼ばれる）。今日の日付見出しへスクロールする（§6.3）。
 * 今日が表示中の月に無い（=見出しが存在しない）場合は何もしない — 親が先に月を今日の月へ
 * 移動させてから本メソッドを呼ぶ（`CalendarWeekGrid.focusToday` と同じ責務分離）。
 */
function focusToday(): void {
  if (!containerEl.value) return
  const el = containerEl.value.querySelector('[data-agenda-today="true"]')
  if (el && 'scrollIntoView' in el) {
    (el as HTMLElement).scrollIntoView({ block: 'start' })
  }
}

defineExpose({ focusToday })

function onRowOpen(event: CalendarEventItem) {
  if (event.isReflection && event.referenceUuid && event.referenceKind) {
    emit('reflectionClick', event.referenceUuid, event.referenceKind)
    return
  }
  emit('eventClick', event.id, event.isPersonal)
}
</script>

<template>
  <div>
    <!-- ヘッダー（月ナビゲーション。行の一次元リストゆえドラッグ選択の受け口は持たない＝AC-22b） -->
    <div class="mb-2 flex items-center justify-between">
      <div class="flex items-center gap-1">
        <Button icon="pi pi-chevron-left" text rounded data-testid="agenda-prev" @click="emit('prevMonth')" />
        <h2 class="text-lg font-extrabold">{{ periodLabel }}</h2>
        <Button icon="pi pi-chevron-right" text rounded data-testid="agenda-next" @click="emit('nextMonth')" />
      </div>
      <Button
        :label="t('schedule.calendar.today')"
        text
        size="small"
        data-testid="calendar-today-button"
        @click="emit('today')"
      />
    </div>

    <div ref="containerEl" data-testid="agenda-list" class="max-h-[70vh] overflow-auto">
      <div v-if="isEmpty" data-testid="agenda-empty" class="py-10 text-center text-sm text-surface-500">
        {{ t('schedule.calendar.empty') }}
      </div>
      <template v-else>
        <div
          v-for="day in agendaDays"
          :key="day.dateStr"
          :data-testid="`agenda-day-${day.dateStr}`"
          :data-agenda-today="day.isToday ? 'true' : undefined"
        >
          <div
            class="sticky top-0 z-10 border-b border-surface-200 bg-surface-100 px-3 py-1.5 text-xs font-semibold text-surface-600 dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300"
            :class="{ 'text-primary': day.isToday }"
          >
            {{ day.label }}
          </div>
          <div
            v-for="ev in day.events"
            :key="ev.uniqueKey"
            class="flex items-stretch border-b border-surface-100 dark:border-surface-800"
            data-testid="agenda-row-wrap"
          >
            <!-- レイヤー色の縦バー（モバイルリストと同じ流儀。BE 解決済みの色をそのまま使う）。 -->
            <span
              class="w-1 shrink-0"
              data-testid="agenda-row-color-bar"
              :style="{ backgroundColor: ev.color ?? 'transparent' }"
            />
            <div class="min-w-0 flex-1">
              <ScheduleListRow
                :event="ev"
                :scope-type="scopeType"
                :scope-id="scopeId"
                @open="onRowOpen(ev)"
                @responded="emit('responded')"
              />
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
