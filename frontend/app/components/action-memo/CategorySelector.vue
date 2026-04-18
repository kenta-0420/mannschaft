<script setup lang="ts">
import type { ActionMemoCategory } from '~/types/actionMemo'

/**
 * F02.5 Phase 3: カテゴリ選択コンポーネント。
 *
 * <p>WORK / PRIVATE / OTHER の3択トグルボタングループ。
 * WORK カテゴリのメモのみチーム投稿が可能になる。</p>
 */

const props = defineProps<{
  modelValue: ActionMemoCategory
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ActionMemoCategory]
}>()

const { t } = useI18n()

interface CategoryOption {
  value: ActionMemoCategory
  labelKey: string
  icon: string
}

const options: CategoryOption[] = [
  { value: 'WORK', labelKey: 'action_memo.phase3.category.work', icon: 'pi-briefcase' },
  { value: 'PRIVATE', labelKey: 'action_memo.phase3.category.private', icon: 'pi-lock' },
  { value: 'OTHER', labelKey: 'action_memo.phase3.category.other', icon: 'pi-circle' },
]

function select(value: ActionMemoCategory) {
  if (!props.disabled) {
    emit('update:modelValue', value)
  }
}
</script>

<template>
  <div
    class="flex items-center gap-1"
    role="group"
    :aria-label="t('action_memo.phase3.category.label')"
    data-testid="category-selector"
  >
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      :aria-pressed="modelValue === opt.value"
      :disabled="disabled"
      :data-testid="`category-selector-${opt.value.toLowerCase()}`"
      :class="[
        'flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors',
        modelValue === opt.value
          ? 'border-primary bg-primary/10 text-primary dark:border-primary dark:bg-primary/20 dark:text-primary'
          : 'border-surface-200 bg-transparent text-surface-500 hover:border-primary/50 hover:text-primary dark:border-surface-700 dark:text-surface-400 dark:hover:border-primary/50',
        disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
      ]"
      @click="select(opt.value)"
    >
      <i :class="`pi ${opt.icon} text-xs`" />
      <span>{{ t(opt.labelKey) }}</span>
    </button>
  </div>
</template>
