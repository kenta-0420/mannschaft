<script setup lang="ts">
const props = defineProps<{
  startDate: string
  endDate: string
  useTimeRange: boolean
  startHour: number | null
  endHour: number | null
  periodText: string
  timeRangeError: string | null
}>()

const emit = defineEmits<{
  'update:startDate': [value: string]
  'update:endDate': [value: string]
  'update:useTimeRange': [value: boolean]
  'update:startHour': [value: number | null]
  'update:endHour': [value: number | null]
  'set-today': []
}>()

const HOURS = Array.from({ length: 24 }, (_, i) => i) // 0〜23

const startDateModel = computed({
  get: () => props.startDate,
  set: (v: string) => emit('update:startDate', v),
})
const endDateModel = computed({
  get: () => props.endDate,
  set: (v: string) => emit('update:endDate', v),
})
const useTimeRangeModel = computed({
  get: () => props.useTimeRange,
  set: (v: boolean) => emit('update:useTimeRange', v),
})
const startHourModel = computed({
  get: () => props.startHour,
  set: (v: number | null) => emit('update:startHour', v),
})
const endHourModel = computed({
  get: () => props.endHour,
  set: (v: number | null) => emit('update:endHour', v),
})
</script>

<template>
  <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
    <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.period') }}</h3>
    <div class="flex flex-wrap items-end gap-3">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.start_date') }}</label>
        <input
          v-model="startDateModel"
          type="date"
          class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
        >
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.end_date') }}</label>
        <input
          v-model="endDateModel"
          type="date"
          :min="startDate"
          class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
        >
      </div>
      <Button
        :label="$t('emergency_closure.button.today_only')"
        icon="pi pi-calendar"
        size="small"
        severity="secondary"
        outlined
        @click="emit('set-today')"
      />
    </div>

    <!-- 時間帯指定（部分時間帯休業）-->
    <div class="mt-4 border-t border-surface-100 pt-3 dark:border-surface-700">
      <div class="flex items-center gap-2">
        <Checkbox v-model="useTimeRangeModel" input-id="use-time-range" :binary="true" />
        <label for="use-time-range" class="cursor-pointer text-sm">
          {{ $t('emergency_closure.label.partial_time') }}
        </label>
      </div>
      <p class="mt-1 text-xs text-surface-400">
        {{ $t('emergency_closure.hint.all_day') }}
      </p>

      <div v-if="useTimeRange" class="mt-3 flex flex-wrap items-end gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.start_time') }}</label>
          <select
            v-model.number="startHourModel"
            class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
          >
            <option :value="null">{{ $t('emergency_closure.placeholder.select') }}</option>
            <option v-for="h in HOURS" :key="`s${h}`" :value="h">
              {{ String(h).padStart(2, '0') }}:00
            </option>
          </select>
        </div>
        <span class="pb-2 text-surface-400">〜</span>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.end_time') }}</label>
          <select
            v-model.number="endHourModel"
            class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
          >
            <option :value="null">{{ $t('emergency_closure.placeholder.select') }}</option>
            <option v-for="h in HOURS" :key="`e${h}`" :value="h">
              {{ String(h).padStart(2, '0') }}:00
            </option>
          </select>
        </div>
      </div>

      <p v-if="timeRangeError" class="mt-2 text-xs text-red-500">
        <i class="pi pi-exclamation-circle mr-1" />{{ timeRangeError }}
      </p>
    </div>

    <p v-if="startDate" class="mt-3 text-sm text-surface-500">
      {{ $t('emergency_closure.label.target_period') }}: <span class="font-medium text-surface-700 dark:text-surface-200">{{ periodText }}</span>
    </p>
  </section>
</template>
