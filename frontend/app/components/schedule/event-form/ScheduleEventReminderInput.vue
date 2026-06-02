<script setup lang="ts">
import type { ReminderFormEntry, ScheduleEventFormState } from './types'

const form = defineModel<ScheduleEventFormState>('form', { required: true })

const { t } = useI18n()

const MAX_REMINDERS = 5

const kindOptions = computed(() => [
  { label: t('schedule.reminder.kind_relative'), value: 'RELATIVE' as const },
  { label: t('schedule.reminder.kind_absolute'), value: 'ABSOLUTE' as const },
])

const unitOptions = computed(() => [
  { label: t('schedule.reminder.unit_minutes'), value: 'MINUTES' as const },
  { label: t('schedule.reminder.unit_hours'), value: 'HOURS' as const },
  { label: t('schedule.reminder.unit_days'), value: 'DAYS' as const },
])

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
      class="flex flex-wrap items-center gap-2"
    >
      <Select
        v-model="reminder.kind"
        :options="kindOptions"
        option-label="label"
        option-value="value"
        class="w-32"
        :aria-label="$t('schedule.reminder.kind_label')"
      />

      <!-- 相対指定: 値 + 単位 -->
      <template v-if="reminder.kind === 'RELATIVE'">
        <InputNumber
          v-model="reminder.relativeValue"
          :min="1"
          :max="9999"
          class="w-24"
          input-class="text-center"
          :aria-label="$t('schedule.reminder.relative_value_label')"
        />
        <Select
          v-model="reminder.relativeUnit"
          :options="unitOptions"
          option-label="label"
          option-value="value"
          class="w-28"
          :aria-label="$t('schedule.reminder.unit_label')"
        />
        <span class="text-sm text-surface-500">{{ $t('schedule.reminder.before') }}</span>
      </template>

      <!-- 絶対指定: 日時ピッカー -->
      <template v-else>
        <DatePicker
          v-model="reminder.absoluteAt"
          show-time
          hour-format="24"
          date-format="yy/mm/dd"
          class="flex-1 min-w-[12rem]"
          show-icon
          :aria-label="$t('schedule.reminder.absolute_at_label')"
        />
      </template>

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
