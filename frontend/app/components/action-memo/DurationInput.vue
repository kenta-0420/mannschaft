<script setup lang="ts">
/**
 * F02.5 Phase 3: 実績時間入力コンポーネント（分単位）。
 *
 * <p>0〜1440分（24時間）の範囲で入力可能。クイックボタンで
 * 15 / 30 / 60 分を即座に設定できる。範囲外は赤ボーダーで警告。</p>
 */

const props = defineProps<{
  modelValue: number | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const { t } = useI18n()

const MIN = 0
const MAX = 1440

const inputValue = ref<string>(props.modelValue !== null ? String(props.modelValue) : '')

watch(
  () => props.modelValue,
  (next) => {
    inputValue.value = next !== null ? String(next) : ''
  },
)

const isOutOfRange = computed(() => {
  if (inputValue.value === '') return false
  const n = Number(inputValue.value)
  return isNaN(n) || n < MIN || n > MAX
})

function onInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value
  inputValue.value = raw
  if (raw === '') {
    emit('update:modelValue', null)
    return
  }
  const n = Number(raw)
  if (!isNaN(n) && n >= MIN && n <= MAX) {
    emit('update:modelValue', n)
  }
}

function setQuick(minutes: number) {
  if (props.disabled) return
  inputValue.value = String(minutes)
  emit('update:modelValue', minutes)
}

const quickButtons = [
  { label: 'action_memo.phase3.duration.quick_15', minutes: 15 },
  { label: 'action_memo.phase3.duration.quick_30', minutes: 30 },
  { label: 'action_memo.phase3.duration.quick_60', minutes: 60 },
  { label: 'action_memo.phase3.duration.quick_120', minutes: 120 },
]
</script>

<template>
  <div class="flex flex-col gap-1.5" data-testid="duration-input">
    <label class="text-xs font-medium text-surface-600 dark:text-surface-300">
      {{ t('action_memo.phase3.duration.label') }}
    </label>
    <div class="flex items-center gap-2">
      <input
        type="number"
        :value="inputValue"
        :min="MIN"
        :max="MAX"
        :disabled="disabled"
        :placeholder="t('action_memo.phase3.duration.placeholder')"
        :class="[
          'w-24 rounded-lg border px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:bg-transparent',
          isOutOfRange
            ? 'border-rose-400 focus:ring-rose-400 dark:border-rose-500'
            : 'border-surface-200 dark:border-surface-700',
          disabled ? 'cursor-not-allowed opacity-50' : '',
        ]"
        data-testid="duration-input-field"
        @input="onInput"
      >
      <span class="text-xs text-surface-400">{{ t('action_memo.phase3.duration.unit') }}</span>
    </div>
    <div class="flex flex-wrap gap-1">
      <button
        v-for="btn in quickButtons"
        :key="btn.minutes"
        type="button"
        :disabled="disabled"
        :class="[
          'rounded px-2 py-0.5 text-xs text-primary transition-colors hover:bg-primary/10',
          disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
        ]"
        :data-testid="`duration-quick-${btn.minutes}`"
        @click="setQuick(btn.minutes)"
      >
        {{ t(btn.label) }}
      </button>
    </div>
  </div>
</template>
