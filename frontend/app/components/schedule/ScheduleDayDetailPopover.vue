<script setup lang="ts">
/**
 * 日別ポップオーバー（設計書 F03.19 §6.2・`data-testid="day-detail-popover"`）。
 *
 * 月ビュー（`CalendarGrid.vue`）と週ビュー（`CalendarWeekGrid.vue`）の「+N件」から開く、
 * 「その日の予定を全件並べる」共通部品。**両ビューで同じ実装を共有するために切り出した**
 * （FRONTEND_CODING_CONVENTION.md §3a「同じコードが2箇所以上に出現したら共通化する」）。
 *
 * 複製のまま放置していた間、その日に掛かる予定の抽出条件が月と週で別々に書かれており、
 * 片方だけが日付文字列の包含比較のまま「8/3 22:00〜8/4 00:00 の予定が 8/4 にも出る」欠陥を
 * 抱えていた（Codex 検分二巡目 [1]）。抽出は `eventOccupiesDate` 一本に統一してある。
 */
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import { eventOccupiesDate } from '~/utils/calendarWeek'

const props = defineProps<{
  /** そのカレンダーが表示している予定の全件。日付での絞り込みは本コンポーネントが行う。 */
  events: CalendarEventItem[]
}>()

const emit = defineEmits<{
  /** 行がクリックされた。詳細を開く経路は呼び出し元（月/週ビュー）の責務。 */
  rowOpen: [event: CalendarEventItem]
}>()

const { t } = useI18n()

const popover = ref<{ show: (ev: Event) => void; hide: () => void } | null>(null)
const dateStr = ref('')

/** 対象日に存在する予定の全件（終日・時刻付き・レーンから省かれた分をすべて含む）。 */
const dayEvents = computed<CalendarEventItem[]>(() => {
  if (!dateStr.value) return []
  return props.events.filter(e => eventOccupiesDate(e, dateStr.value))
})

/** 指定日のポップオーバーを開く。`ev` は表示位置の基準となるクリックイベント。 */
function open(targetDate: string, ev: Event): void {
  dateStr.value = targetDate
  popover.value?.show(ev)
}

function close(): void {
  popover.value?.hide()
}

function onRowOpen(event: CalendarEventItem): void {
  close()
  emit('rowOpen', event)
}

defineExpose({ open, close })
</script>

<template>
  <Popover ref="popover">
    <div data-testid="day-detail-popover" class="flex flex-col" style="min-width: 260px; max-width: 320px">
      <div class="px-2 pb-1 text-xs font-semibold text-surface-500">
        {{ t('schedule.calendar.dayDetail.title', { date: dateStr }) }}
      </div>
      <div class="max-h-80 overflow-y-auto">
        <ScheduleListRow
          v-for="event in dayEvents"
          :key="event.uniqueKey"
          :event="event"
          scope-type="team"
          :scope-id="''"
          @open="onRowOpen(event)"
        />
      </div>
    </div>
  </Popover>
</template>
