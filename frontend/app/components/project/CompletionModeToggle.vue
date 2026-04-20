<script setup lang="ts">
import type { MilestoneCompletionMode } from '~/types/project'

defineProps<{
  mode: MilestoneCompletionMode
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:mode': [value: MilestoneCompletionMode]
}>()

const { t } = useI18n()

const options = computed(() => [
  { label: t('project.completion_mode_auto'), value: 'AUTO' as const },
  { label: t('project.completion_mode_manual'), value: 'MANUAL' as const },
])

function onChange(value: MilestoneCompletionMode | null | undefined) {
  if (value === 'AUTO' || value === 'MANUAL') {
    emit('update:mode', value)
  }
}
</script>

<template>
  <div class="inline-flex items-center gap-2">
    <span class="text-xs text-surface-500">{{ $t('project.completion_mode_label') }}:</span>
    <SelectButton
      :model-value="mode"
      :options="options"
      option-label="label"
      option-value="value"
      :disabled="disabled"
      :allow-empty="false"
      data-testid="completion-mode-toggle"
      @update:model-value="onChange"
    />
  </div>
</template>
