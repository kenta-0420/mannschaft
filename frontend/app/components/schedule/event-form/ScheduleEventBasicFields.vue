<script setup lang="ts">
import type { ScheduleEventFormState, TimeHistoryEntry, TimeOption } from './types'

defineProps<{
  fieldErrors: Record<string, string>
  isPersonalScope: boolean
  timeHistory: TimeHistoryEntry[]
  timeOptions: TimeOption[]
}>()

const form = defineModel<ScheduleEventFormState>('form', { required: true })
</script>

<template>
  <div>
    <label class="mb-1 block text-sm font-medium"
      >タイトル <span class="text-red-500">*</span></label
    >
    <InputText
      v-model="form.title"
      class="w-full"
      :class="{ 'p-invalid': fieldErrors.title }"
    />
    <small v-if="fieldErrors.title" class="text-red-500">{{ fieldErrors.title }}</small>
  </div>
  <div class="flex items-center gap-4">
    <div v-if="!isPersonalScope" class="flex items-center gap-2">
      <Checkbox v-model="form.attendanceRequired" input-id="attendance-required" :binary="true" />
      <label for="attendance-required" class="text-sm cursor-pointer">出欠確認する</label>
    </div>
    <div class="flex items-center gap-2">
      <ToggleSwitch v-model="form.allDay" />
      <label class="text-sm">終日</label>
    </div>
  </div>
  <!-- よく使う時間（履歴クイック選択） -->
  <div v-if="timeHistory.length > 0 && !form.allDay" class="flex flex-wrap gap-1.5 mb-1">
    <span class="text-xs text-surface-400 self-center">履歴:</span>
    <button
      v-for="h in timeHistory"
      :key="`${h.startTime}-${h.endTime}`"
      type="button"
      class="text-xs px-2 py-0.5 rounded-full bg-surface-100 hover:bg-surface-200 dark:bg-surface-700 dark:hover:bg-surface-600 border border-surface-200 dark:border-surface-600"
      @click="form.startTime = h.startTime; form.endTime = h.endTime"
    >
      {{ h.startTime }}〜{{ h.endTime }}
    </button>
  </div>
  <div class="grid grid-cols-2 gap-3">
    <div>
      <label for="schedule-start-date" class="mb-1 block text-sm font-medium">開始日</label>
      <DatePicker v-model="form.startDate" input-id="schedule-start-date" date-format="yy/mm/dd" class="w-full" show-icon />
    </div>
    <div v-if="!form.allDay">
      <label class="mb-1 block text-sm font-medium">開始時刻</label>
      <Select
        v-model="form.startTime"
        :options="timeOptions"
        option-label="label"
        option-value="value"
        filter
        class="w-full"
      />
    </div>
  </div>
  <div class="grid grid-cols-2 gap-3">
    <div>
      <label for="schedule-end-date" class="mb-1 block text-sm font-medium">終了日</label>
      <DatePicker v-model="form.endDate" input-id="schedule-end-date" date-format="yy/mm/dd" class="w-full" show-icon />
    </div>
    <div v-if="!form.allDay">
      <label class="mb-1 block text-sm font-medium">終了時刻</label>
      <Select
        v-model="form.endTime"
        :options="timeOptions"
        option-label="label"
        option-value="value"
        filter
        class="w-full"
      />
    </div>
  </div>
  <div>
    <label class="mb-1 block text-sm font-medium">場所</label>
    <InputText v-model="form.location" class="w-full" placeholder="場所（任意）" />
  </div>
</template>
