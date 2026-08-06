<script setup lang="ts">
import dayjs from 'dayjs'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import { useAttendanceResponder } from '~/composables/schedule/useAttendanceResponder'

/**
 * モバイルのスケジュールリストビュー 1 行。
 *
 * - 日付・時刻・タイトル・スコープを 1 行で即時可視化する（カレンダーはタップしないと
 *   詳細が見えず即時性が無いため、390px 既定はこのリスト表示にする）。
 * - 出欠必須イベント（{@link CalendarEventItem.attendanceRequired}）は行内に
 *   出席/欠席/未定ボタンを描画し、タップで {@link useAttendanceResponder} 経由で回答する。
 *   回答ロジックは AttendancePanel と共有し二重実装しない。
 */
const props = defineProps<{
  event: CalendarEventItem
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

const emit = defineEmits<{ open: [id: number]; responded: [] }>()

const { t } = useI18n()
const { responding, respond } = useAttendanceResponder(props.scopeType, props.scopeId)
const { userTimezone, formatTime } = useDatetime()

// 本人の出欠選択状態。API 失敗時は更新せず既存状態を保つ（楽観更新しない）。
const localStatus = ref<string | null>(props.event.myAttendance ?? null)

// status は BE 正準（ATTENDING/ABSENT/UNDECIDED）と完全一致させる。ラベルは i18n キーのまま不変。
const attendanceButtons = [
  { status: 'ATTENDING', label: t('schedule.attendance.yes'), on: 'bg-green-600 text-white border-green-600' },
  { status: 'ABSENT', label: t('schedule.attendance.no'), on: 'bg-red-600 text-white border-red-600' },
  { status: 'UNDECIDED', label: t('schedule.attendance.maybe'), on: 'bg-amber-500 text-white border-amber-500' },
] as const

async function onRespond(status: string) {
  const ok = await respond(props.event.id, status)
  // 成功時のみ選択状態を反映する（500 等の失敗時は元の選択を維持する）。
  if (ok) {
    localStatus.value = status
    emit('responded')
  }
}

const dateLabel = computed(() => {
  if (!props.event.startAt) return ''
  return dayjs(props.event.startAt).tz(userTimezone.value).format('M/D (ddd)')
})

const timeLabel = computed(() => {
  if (props.event.allDay) return t('schedule.list.allDay')
  if (!props.event.startAt) return ''
  const start = formatTime(props.event.startAt)
  const end = props.event.endAt ? formatTime(props.event.endAt) : ''
  return end ? `${start} 〜 ${end}` : start
})
</script>

<template>
  <div
    data-testid="schedule-list-row"
    class="flex flex-col gap-2 border-b border-surface-200 px-4 py-3 last:border-b-0 dark:border-surface-700"
  >
    <!-- 日付・時刻・タイトル -->
    <button
      type="button"
      class="flex w-full items-start gap-3 text-left"
      @click="emit('open', event.id)"
    >
      <div class="flex w-16 shrink-0 flex-col">
        <span class="text-xs font-semibold text-surface-500 dark:text-surface-400">{{ dateLabel }}</span>
        <span class="text-sm font-bold text-primary">{{ timeLabel }}</span>
      </div>
      <div class="min-w-0 flex-1">
        <p class="truncate text-sm font-medium text-surface-800 dark:text-surface-200">
          {{ event.title }}
        </p>
        <span
          v-if="event.scopeName"
          class="mt-0.5 block truncate text-xs text-surface-400"
        >
          {{ event.scopeName }}
        </span>
      </div>
    </button>

    <!-- 行内 出欠回答（出欠必須イベントのみ） -->
    <div
      v-if="event.attendanceRequired"
      class="flex flex-wrap items-center gap-2 pl-16"
    >
      <button
        v-for="btn in attendanceButtons"
        :key="btn.status"
        type="button"
        :disabled="responding"
        class="min-h-[44px] rounded-md border px-3 text-sm font-medium transition-colors disabled:opacity-60"
        :class="
          localStatus === btn.status
            ? btn.on
            : 'border-surface-300 bg-surface-0 text-surface-700 hover:bg-surface-50 dark:border-surface-600 dark:bg-surface-900 dark:text-surface-300 dark:hover:bg-surface-800'
        "
        @click="onRespond(btn.status)"
      >
        {{ btn.label }}
      </button>
    </div>
  </div>
</template>
