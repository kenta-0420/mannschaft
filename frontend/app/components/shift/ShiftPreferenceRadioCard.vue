<script setup lang="ts">
import type { ShiftPreference } from '~/types/shift'
import { preferenceToColor, preferenceToI18nKey } from '~/utils/shiftPreference'

/**
 * F03.5 シフト希望ラジオカード（5段階）
 *
 * ADHD 配慮:
 * - 5色で視覚区別（緑/灰/黄/橙/赤）
 * - 最小タップサイズ 44x44px
 * - 選択状態が明確なボーダー・背景変化
 */

interface Props {
  /** 現在選択されている preference */
  modelValue: ShiftPreference
  /** スロットID（ユニークキー用） */
  slotId?: number
  /** 無効状態 */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: ShiftPreference]
}>()

const { t } = useI18n()

const preferences: ShiftPreference[] = [
  'PREFERRED',
  'AVAILABLE',
  'WEAK_REST',
  'STRONG_REST',
  'ABSOLUTE_REST',
]

function select(pref: ShiftPreference) {
  if (!props.disabled) {
    emit('update:modelValue', pref)
  }
}

function isSelected(pref: ShiftPreference): boolean {
  return props.modelValue === pref
}

function cardClass(pref: ShiftPreference): string {
  const base =
    'flex flex-col items-center justify-center rounded-lg border-2 cursor-pointer transition-all select-none'
  const sizeClass = 'min-h-[44px] min-w-[44px] px-2 py-2 text-xs font-medium'
  const colorClass = preferenceToColor(pref)
  const selectedClass = isSelected(pref)
    ? 'ring-2 ring-offset-1 ring-primary scale-105 shadow-md'
    : 'opacity-70 hover:opacity-100'
  const disabledClass = props.disabled ? 'cursor-not-allowed opacity-50' : ''
  return [base, sizeClass, colorClass, selectedClass, disabledClass].filter(Boolean).join(' ')
}
</script>

<template>
  <div class="flex flex-wrap gap-1.5" role="radiogroup" :aria-label="t('shift.field.preference')">
    <button
      v-for="pref in preferences"
      :key="pref"
      type="button"
      role="radio"
      :aria-checked="isSelected(pref)"
      :disabled="disabled"
      :class="cardClass(pref)"
      @click="select(pref)"
    >
      <span class="text-center leading-tight">{{ t(preferenceToI18nKey(pref)) }}</span>
    </button>
  </div>
</template>
