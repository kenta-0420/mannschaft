<script setup lang="ts">
/**
 * F02.5 Phase 3: 進捗率スライダーコンポーネント。
 *
 * <p>0〜100 の範囲でスライダーと数値入力が連動する。
 * {@code relatedTodoId} が null の場合は disabled になり tooltip を表示する。</p>
 */

const props = defineProps<{
  modelValue: number | null
  relatedTodoId: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const { t } = useI18n()

const isDisabled = computed(() => props.relatedTodoId === null)

const sliderValue = computed(() => props.modelValue ?? 0)

function onSliderChange(event: Event) {
  const n = Number((event.target as HTMLInputElement).value)
  emit('update:modelValue', n)
}

function onNumberChange(event: Event) {
  const raw = (event.target as HTMLInputElement).value
  if (raw === '') {
    emit('update:modelValue', null)
    return
  }
  const n = Number(raw)
  if (!isNaN(n) && n >= 0 && n <= 100) {
    emit('update:modelValue', n)
  }
}
</script>

<template>
  <div
    class="flex flex-col gap-1.5"
    :title="isDisabled ? t('action_memo.phase3.progress_rate.requires_todo') : undefined"
    data-testid="progress-rate-slider"
  >
    <label
      class="text-xs font-medium"
      :class="isDisabled ? 'text-surface-400 dark:text-surface-500' : 'text-surface-600 dark:text-surface-300'"
    >
      {{ t('action_memo.phase3.progress_rate.label') }}
    </label>
    <div class="flex items-center gap-3">
      <input
        type="range"
        :value="sliderValue"
        min="0"
        max="100"
        step="1"
        :disabled="isDisabled"
        :class="[
          'h-2 w-full cursor-pointer appearance-none rounded-full bg-surface-200 accent-primary dark:bg-surface-700',
          isDisabled ? 'cursor-not-allowed opacity-40' : '',
        ]"
        data-testid="progress-rate-slider-range"
        @input="onSliderChange"
      >
      <input
        type="number"
        :value="modelValue ?? ''"
        min="0"
        max="100"
        :disabled="isDisabled"
        :class="[
          'w-16 rounded-lg border px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:bg-transparent',
          isDisabled
            ? 'cursor-not-allowed border-surface-200 opacity-40 dark:border-surface-700'
            : 'border-surface-200 dark:border-surface-700',
        ]"
        data-testid="progress-rate-input"
        @change="onNumberChange"
      >
      <span
        class="text-xs"
        :class="isDisabled ? 'text-surface-400' : 'text-surface-500'"
      >%</span>
    </div>
    <p
      v-if="isDisabled"
      class="text-xs text-surface-400 dark:text-surface-500"
      data-testid="progress-rate-requires-todo"
    >
      {{ t('action_memo.phase3.progress_rate.requires_todo') }}
    </p>
  </div>
</template>
