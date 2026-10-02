<script setup lang="ts">
import type { ScheduleEventFormState } from './types'

const form = defineModel<ScheduleEventFormState>('form', { required: true })
const { t } = useI18n()

// 繰り返し間隔の手入力を 1〜99 にクランプする。
// native の number input は min/max を入力時に強制しない（フォーム送信時に検証されるのみ）ため、
// BE の @Min(1)/@Max(99) で 400 になる前にクライアント側でも丸める。
function clampInterval() {
  const v = form.value.recurrenceInterval
  if (!Number.isFinite(v) || v < 1) {
    form.value.recurrenceInterval = 1
  } else if (v > 99) {
    form.value.recurrenceInterval = 99
  } else {
    form.value.recurrenceInterval = Math.floor(v)
  }
}

function toggleDay(day: string) {
  const idx = form.value.recurrenceDaysOfWeek.indexOf(day)
  if (idx >= 0) {
    form.value.recurrenceDaysOfWeek.splice(idx, 1)
  } else {
    form.value.recurrenceDaysOfWeek.push(day)
  }
}

const countOptions = computed(() =>
  Array.from({ length: 100 }, (_, i) => ({
    label: `${i + 1}${t('schedule.recurrence.end_count_suffix')}`,
    value: i + 1,
  }))
)
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
        <input
          v-model.number="form.recurrenceInterval"
          type="number"
          min="1"
          max="99"
          class="h-10 w-16 rounded-lg border border-surface-300 dark:border-surface-600 bg-surface-100 dark:bg-surface-800 px-2 text-center text-sm text-surface-900 dark:text-surface-100 focus:outline-none focus:border-primary-400"
          @blur="clampInterval"
        >
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
            <Select
              v-if="form.recurrenceEndType === 'COUNT'"
              v-model="form.recurrenceCount"
              :options="countOptions"
              option-label="label"
              option-value="value"
              class="w-28"
            />
          </label>
        </div>
      </div>
    </template>
  </div>
</template>
