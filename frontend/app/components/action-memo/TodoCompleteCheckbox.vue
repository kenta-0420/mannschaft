<script setup lang="ts">
/**
 * F02.5 Phase 3: TODO完了チェックボックス。
 *
 * <p>{@code relatedTodoId} が null の場合は非表示（v-show="false"）。
 * チェックするとメモ保存時に関連 TODO が完了状態になる。</p>
 */

const props = defineProps<{
  modelValue: boolean
  relatedTodoId: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const { t } = useI18n()

function onChange(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement).checked)
}
</script>

<template>
  <div
    v-show="relatedTodoId !== null"
    class="flex items-center gap-2"
    data-testid="todo-complete-checkbox"
  >
    <label
      class="flex cursor-pointer items-center gap-2 text-sm text-surface-700 dark:text-surface-200"
      :title="t('action_memo.phase3.completes_todo.tooltip')"
    >
      <input
        type="checkbox"
        :checked="modelValue"
        class="h-4 w-4 cursor-pointer rounded border-surface-300 accent-primary dark:border-surface-600"
        data-testid="todo-complete-checkbox-input"
        @change="onChange"
      >
      <span>{{ t('action_memo.phase3.completes_todo.label') }}</span>
    </label>
  </div>
</template>
