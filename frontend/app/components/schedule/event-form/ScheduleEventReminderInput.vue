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

const reminderPresets = computed(() => [
  { label: t('schedule.reminder.preset_minutes_before', { n: 5 }),   minutes: 5 },
  { label: t('schedule.reminder.preset_minutes_before', { n: 10 }),  minutes: 10 },
  { label: t('schedule.reminder.preset_minutes_before', { n: 15 }),  minutes: 15 },
  { label: t('schedule.reminder.preset_minutes_before', { n: 30 }),  minutes: 30 },
  { label: t('schedule.reminder.preset_hours_before',   { n: 1 }),   minutes: 60 },
  { label: t('schedule.reminder.preset_hours_before',   { n: 3 }),   minutes: 180 },
  { label: t('schedule.reminder.preset_days_before',    { n: 1 }),   minutes: 1440 },
  { label: t('schedule.reminder.preset_days_before',    { n: 2 }),   minutes: 2880 },
  { label: t('schedule.reminder.preset_weeks_before',   { n: 1 }),   minutes: 10080 },
])

function toMinutes(entry: ReminderFormEntry): number {
  return entry.relativeValue * UNIT_TO_MINUTES[entry.relativeUnit]
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
        :options="reminderPresets"
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
