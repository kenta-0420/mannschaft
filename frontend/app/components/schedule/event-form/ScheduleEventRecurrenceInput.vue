<script setup lang="ts">
import type { ScheduleEventFormState } from './types'

const form = defineModel<ScheduleEventFormState>('form', { required: true })

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
      <label class="text-sm font-medium">繰り返し</label>
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
            { label: '日ごと',  value: 'DAILY' },
            { label: '週ごと',  value: 'WEEKLY' },
            { label: 'ヶ月ごと', value: 'MONTHLY' },
            { label: '年ごと',  value: 'YEARLY' },
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
            { label: '日', value: 'SUNDAY' },
            { label: '月', value: 'MONDAY' },
            { label: '火', value: 'TUESDAY' },
            { label: '水', value: 'WEDNESDAY' },
            { label: '木', value: 'THURSDAY' },
            { label: '金', value: 'FRIDAY' },
            { label: '土', value: 'SATURDAY' },
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
        <label class="text-xs text-surface-500">終了</label>
        <div class="flex flex-col gap-1.5">
          <label class="flex items-center gap-2 text-sm cursor-pointer">
            <RadioButton v-model="form.recurrenceEndType" value="NEVER" />
            指定なし
          </label>
          <label class="flex items-center gap-2 text-sm cursor-pointer">
            <RadioButton v-model="form.recurrenceEndType" value="DATE" />
            <span class="shrink-0">日付</span>
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
            <span class="shrink-0">回数</span>
            <InputNumber
              v-if="form.recurrenceEndType === 'COUNT'"
              v-model="form.recurrenceCount"
              :min="1" :max="365"
              class="w-20"
              input-class="text-center"
              suffix=" 回"
            />
          </label>
        </div>
      </div>
    </template>
  </div>
</template>
