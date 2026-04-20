<script setup lang="ts">
import type { GatesSummaryResponse } from '~/types/project'

defineProps<{
  summary: GatesSummaryResponse | null
}>()
</script>

<template>
  <div
    v-if="summary"
    class="rounded-lg border border-blue-200 bg-blue-50 p-4 dark:border-blue-700 dark:bg-blue-900/30"
  >
    <div class="mb-2 flex items-center justify-between">
      <h3 class="flex items-center gap-2 font-semibold">
        <i class="pi pi-lock-open" />
        {{ $t('project.gate_progress') }}
      </h3>
      <span class="text-lg font-bold">{{ summary.gateCompletionRate.toFixed(1) }}%</span>
    </div>
    <ProgressBar :value="summary.gateCompletionRate" class="mb-3" :show-value="false" />
    <div class="mb-2 grid grid-cols-3 gap-2 text-xs text-surface-500 dark:text-surface-300">
      <div>
        <span class="font-medium">{{ $t('project.total_milestones') }}:</span>
        {{ summary.totalMilestones }}
      </div>
      <div>
        <span class="font-medium">{{ $t('project.completed_milestones') }}:</span>
        {{ summary.completedMilestones }}
      </div>
      <div>
        <span class="font-medium">{{ $t('project.locked_milestones') }}:</span>
        {{ summary.lockedMilestones }}
      </div>
    </div>
    <div v-if="summary.nextGate" class="text-sm">
      <p>
        {{ $t('project.next_gate') }}:
        <strong>{{ summary.nextGate.title }}</strong>
      </p>
      <p
        v-if="summary.nextGate.lockedReasonMilestoneTitle"
        class="text-xs text-surface-500 dark:text-surface-300"
      >
        {{
          $t('project.next_gate_hint', { title: summary.nextGate.lockedReasonMilestoneTitle })
        }}
      </p>
    </div>
  </div>
</template>
