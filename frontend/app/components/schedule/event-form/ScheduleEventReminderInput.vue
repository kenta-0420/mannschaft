<script setup lang="ts">
import type { RelativeReminderUnit, ReminderFormEntry, ScheduleEventFormState } from './types'

const form = defineModel<ScheduleEventFormState>('form', { required: true })

const { t } = useI18n()

const MAX_REMINDERS = 5

const UNIT_TO_MINUTES: Record<RelativeReminderUnit, number> = {
  MINUTES: 1,
  HOURS: 60,
  DAYS: 1440,
}

const kindOptions = computed(() => [
  { label: t('schedule.reminder.kind_relative'), value: 'RELATIVE' as const },
  { label: t('schedule.reminder.kind_absolute'), value: 'ABSOLUTE' as const },
])

// プリセット（開始前の分数）。よく使う間隔のみを厳選して入力摩擦を抑える。
const BASE_PRESET_MINUTES = [5, 10, 15, 30, 60, 180, 1440, 2880, 10080]

// 分数を「○分前 / ○時間前 / ○日前 / ○週間前」の表示ラベルへ変換する。
// プリセットに無い任意の分数（既存予定の編集で現れる 120 分=2時間 や 45 分など）も
// 割り切れる最大単位を選んでラベル化し、適切に表示できるようにする。
function labelForMinutes(minutes: number): string {
  if (minutes % 10080 === 0) return t('schedule.reminder.preset_weeks_before', { n: minutes / 10080 })
  if (minutes % 1440 === 0) return t('schedule.reminder.preset_days_before', { n: minutes / 1440 })
  if (minutes % 60 === 0) return t('schedule.reminder.preset_hours_before', { n: minutes / 60 })
  return t('schedule.reminder.preset_minutes_before', { n: minutes })
}

function toMinutes(entry: ReminderFormEntry): number {
  return entry.relativeValue * UNIT_TO_MINUTES[entry.relativeUnit]
}

// リマインダー1件分の選択肢を返す。既存値がプリセット集合に無い場合は、その値を
// 選択肢へ補って必ずどれか1つに一致させる（プリセット外の既存値が「未選択」表示に
// なる不具合の根治。一方向バインドの :model-value が options に無いと空表示になるため）。
function optionsFor(entry: ReminderFormEntry): Array<{ label: string, minutes: number }> {
  const current = toMinutes(entry)
  const minutesList = current > 0 && !BASE_PRESET_MINUTES.includes(current)
    ? [current, ...BASE_PRESET_MINUTES].sort((a, b) => a - b)
    : [...BASE_PRESET_MINUTES]
  return minutesList.map(minutes => ({ label: labelForMinutes(minutes), minutes }))
}

function setPreset(entry: ReminderFormEntry, minutes: number): void {
  entry.relativeValue = minutes
  entry.relativeUnit = 'MINUTES'
}

function createEntry(): ReminderFormEntry {
  return {
    key: `rem-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    kind: 'RELATIVE',
    relativeValue: 30,
    relativeUnit: 'MINUTES',
    absoluteAt: null,
  }
}

function addReminder() {
  if (form.value.reminders.length >= MAX_REMINDERS) return
  form.value.reminders.push(createEntry())
}

function removeReminder(index: number) {
  form.value.reminders.splice(index, 1)
}
</script>

<template>
  <div class="flex flex-col gap-3 rounded-lg border border-surface-200 dark:border-surface-600 p-3">
    <div class="flex items-center justify-between">
      <label class="text-sm font-medium">{{ $t('schedule.reminder.label') }}</label>
      <span class="text-xs text-surface-500">
        {{ form.reminders.length }} / {{ MAX_REMINDERS }}
      </span>
    </div>

    <div
      v-for="(reminder, index) in form.reminders"
      :key="reminder.key"
      class="flex items-center gap-2"
    >
      <Select
        v-model="reminder.kind"
        :options="kindOptions"
        option-label="label"
        option-value="value"
        class="w-28"
        :aria-label="$t('schedule.reminder.kind_label')"
      />

      <!-- 相対指定: プリセット選択 -->
      <Select
        v-if="reminder.kind === 'RELATIVE'"
        :model-value="toMinutes(reminder)"
        :options="optionsFor(reminder)"
        option-label="label"
        option-value="minutes"
        class="flex-1"
        :aria-label="$t('schedule.reminder.relative_value_label')"
        @update:model-value="(val: number) => setPreset(reminder, val)"
      />

      <!-- 絶対指定: 日時ピッカー -->
      <DatePicker
        v-else
        v-model="reminder.absoluteAt"
        show-time
        hour-format="24"
        date-format="yy/mm/dd"
        class="flex-1 min-w-[12rem]"
        show-icon
        :aria-label="$t('schedule.reminder.absolute_at_label')"
      />

      <Button
        icon="pi pi-trash"
        text
        severity="danger"
        :aria-label="$t('schedule.common_delete')"
        @click="removeReminder(index)"
      />
    </div>

    <Button
      v-if="form.reminders.length < MAX_REMINDERS"
      icon="pi pi-plus"
      :label="$t('schedule.reminder.add')"
      text
      size="small"
      class="self-start"
      @click="addReminder"
    />
    <p v-else class="text-xs text-surface-500">
      {{ $t('schedule.reminder.max_reached') }}
    </p>
  </div>
</template>
