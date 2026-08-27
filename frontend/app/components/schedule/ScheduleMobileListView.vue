<script setup lang="ts">
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

/**
 * モバイル（<768px）共通のスケジュール・リストビュー（F03.19 §6.8・Wave 3-c）。
 *
 * 月ナビ＋`SectionCard`＋{@link ScheduleListRow} の繰り返し＋空状態、という
 * `teams/[slug]/schedule.vue` の `md:hidden` ブロックの「囲い」だけを共通化する。
 * 行描画自体（日付・時刻・タイトル・出欠回答）は {@link ScheduleListRow} に委譲し、
 * ここでは重複実装しない。
 *
 * 各行の左端にレイヤー色の縦バーを添える（モバイルではチップ列が畳まれがちなため、
 * 行そのものが色を持たないとレイヤーが分からなくなる — §6.8）。色は BE が解決済みの
 * {@link CalendarEventItem.color} をそのまま使い、FE 側では算出しない（色の正準は
 * BE の `CalendarLayerAutoColor`）。
 */
const props = defineProps<{
  /** 表示中の月の年（月ナビラベル用）。 */
  year: number
  /** 表示中の月（1-12・月ナビラベル用）。 */
  month: number
  /** 表示中の月のイベント（日付昇順は呼び出し側で整列済みであること）。 */
  events: CalendarEventItem[]
  /** {@link ScheduleListRow} へ渡す出欠回答 API 呼び出し先スコープ。 */
  scopeType: 'team' | 'organization'
  scopeId: string
  /** 空状態のメッセージ（呼び出し側が i18n キーを解決して渡す。ページごとに既存文言が異なるため）。 */
  emptyMessage: string
  /** true の間リストを薄く表示する（再取得中の演出。既存挙動と同一）。 */
  dimmed?: boolean
}>()

const emit = defineEmits<{
  prevMonth: []
  nextMonth: []
  /**
   * 行タップ（{@link ScheduleListRow} の `open` は数値 id のみを送出するため、reflection 行
   * （id が -1 で衝突しうる・§6.2/AC-21 の「id 非依存描画」）まで正しく判別できるよう、
   * ここでは v-for のクロージャで確定している元の {@link CalendarEventItem} をそのまま送る。
   */
  open: [event: CalendarEventItem]
  responded: []
}>()

const { t } = useI18n()

const periodLabel = computed(() => `${props.year}年${props.month}月`)
</script>

<template>
  <div>
    <!-- 月ナビ -->
    <div class="mb-3 flex items-center justify-center gap-3">
      <Button
        icon="pi pi-chevron-left"
        text
        rounded
        severity="secondary"
        :aria-label="t('schedule.list.prevMonth')"
        @click="emit('prevMonth')"
      />
      <span class="min-w-[110px] text-center text-sm font-semibold text-surface-700 dark:text-surface-300">
        {{ periodLabel }}
      </span>
      <Button
        icon="pi pi-chevron-right"
        text
        rounded
        severity="secondary"
        :aria-label="t('schedule.list.nextMonth')"
        @click="emit('nextMonth')"
      />
    </div>

    <SectionCard class="overflow-hidden p-0" :class="{ 'opacity-60': dimmed }">
      <div data-testid="schedule-list-view">
        <template v-if="events.length > 0">
          <div
            v-for="ev in events"
            :key="ev.uniqueKey"
            class="flex items-stretch"
            data-testid="schedule-list-row-wrap"
          >
            <!-- レイヤー色の縦バー（§6.8）。色は BE 解決済みの content.color をそのまま使う。 -->
            <span
              class="w-1 shrink-0"
              data-testid="schedule-list-row-color-bar"
              :style="{ backgroundColor: ev.color ?? 'transparent' }"
            />
            <div class="min-w-0 flex-1">
              <ScheduleListRow
                :event="ev"
                :scope-type="scopeType"
                :scope-id="scopeId"
                @open="emit('open', ev)"
                @responded="emit('responded')"
              />
            </div>
          </div>
        </template>
        <DashboardEmptyState
          v-else
          icon="pi pi-calendar"
          :message="emptyMessage"
          class="py-10"
        />
      </div>
    </SectionCard>
  </div>
</template>
