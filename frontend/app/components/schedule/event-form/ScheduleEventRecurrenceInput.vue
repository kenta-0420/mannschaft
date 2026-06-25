<script setup lang="ts">
import type { ScheduleEventFormState } from './types'

const form = defineModel<ScheduleEventFormState>('form', { required: true })
const { t } = useI18n()

function toggleDay(day: string) {
  const idx = form.value.recurrenceDaysOfWeek.indexOf(day)
  if (idx >= 0) {
    form.value.recurrenceDaysOfWeek.splice(idx, 1)
  } else {
    form.value.recurrenceDaysOfWeek.push(day)
  }
}
</script>

<template>
  <!-- 繰り返し -->
  <div class="flex flex-col gap-3 rounded-lg border border-surface-200 dark:border-surface-600 p-3">
    <div class="flex items-center justify-between">
      <label class="text-sm font-medium">{{ t('schedule.recurrence.label') }}</label>
      <ToggleSwitch v-model="form.recurrence" />
    </div>

    <template v-if="form.recurrence">
      <!-- 種別 + 間隔 -->
      <div class="flex items-center gap-2">
        <InputNumber
          v-model="form.recurrenceInterval"
          :min="1" :max="99"
          class="w-20"
          input-class="text-center"
        />
        <Select
          v-model="form.recurrenceType"
          :options="[
            { label: t('schedule.recurrence.interval.DAILY'),   value: 'DAILY' },
            { label: t('schedule.recurrence.interval.WEEKLY'),  value: 'WEEKLY' },
            { label: t('schedule.recurrence.interval.MONTHLY'), value: 'MONTHLY' },
            { label: t('schedule.recurrence.interval.YEARLY'),  value: 'YEARLY' },
          ]"
          option-label="label"
          option-value="value"
          class="flex-1"
        />
      </div>

      <!-- 曜日選択（WEEKLY のみ） -->
      <div v-if="form.recurrenceType === 'WEEKLY'" class="flex gap-1.5 flex-wrap">
        <button
          v-for="d in [
            { label: t('schedule.recurrence.days.SUNDAY'),    value: 'SUNDAY' },
            { label: t('schedule.recurrence.days.MONDAY'),    value: 'MONDAY' },
            { label: t('schedule.recurrence.days.TUESDAY'),   value: 'TUESDAY' },
            { label: t('schedule.recurrence.days.WEDNESDAY'), value: 'WEDNESDAY' },
            { label: t('schedule.recurrence.days.THURSDAY'),  value: 'THURSDAY' },
            { label: t('schedule.recurrence.days.FRIDAY'),    value: 'FRIDAY' },
            { label: t('schedule.recurrence.days.SATURDAY'),  value: 'SATURDAY' },
          ]"
          :key="d.value"
          type="button"
          class="h-8 w-8 rounded-full text-xs font-medium border transition-colors"
          :class="form.recurrenceDaysOfWeek.includes(d.value)
            ? 'bg-primary text-white border-primary'
            : 'border-surface-300 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:border-primary'"
          @click="toggleDay(d.value)"
        >
          {{ d.label }}
        </button>
      </div>

      <!-- 終了条件 -->
      <div class="flex flex-col gap-2">
        <label class="text-xs text-surface-500">{{ t('schedule.recurrence.end_label') }}</label>
        <div class="flex flex-col gap-1.5">
          <label class="flex items-center gap-2 text-sm cursor-pointer">
            <RadioButton v-model="form.recurrenceEndType" value="NEVER" />
            {{ t('schedule.recurrence.end_never') }}
          </label>
          <label class="flex items-center gap-2 text-sm cursor-pointer">
            <RadioButton v-model="form.recurrenceEndType" value="DATE" />
            <span class="shrink-0">{{ t('schedule.recurrence.end_date') }}</span>
            <DatePicker
              v-if="form.recurrenceEndType === 'DATE'"
              v-model="form.recurrenceEndDate"
              date-format="yy/mm/dd"
              class="flex-1"
              show-icon
            />
          </label>
          <label class="flex items-center gap-2 text-sm cursor-pointer">
            <RadioButton v-model="form.recurrenceEndType" value="COUNT" />
            <span class="shrink-0">{{ t('schedule.recurrence.end_count') }}</span>
            <InputNumber
              v-if="form.recurrenceEndType === 'COUNT'"
              v-model="form.recurrenceCount"
              :min="1" :max="365"
              class="w-20"
              input-class="text-center"
              :suffix="` ${t('schedule.recurrence.end_count_suffix')}`"
            />
          </label>
        </div>
      </div>
    </template>
  </div>
</template>
