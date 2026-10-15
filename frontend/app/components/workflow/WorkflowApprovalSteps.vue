<script setup lang="ts">
import type { RequestStepResponse } from '~/types/workflow'

defineProps<{
  steps: RequestStepResponse[]
}>()

const { statusLabel, statusSeverity, decisionLabel, formatDateTime } = useWorkflowStatus()
</script>

<template>
  <Card class="mb-4">
    <template #title>承認フロー</template>
    <template #content>
      <div v-if="steps.length > 0" class="space-y-4">
        <div
          v-for="step in steps"
          :key="step.id"
          class="flex items-start gap-4 rounded-lg border p-3"
        >
          <div
            class="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-primary/10 font-bold text-primary"
          >
            {{ step.stepOrder }}
          </div>
          <div class="flex-1">
            <div class="flex items-center gap-2">
              <Tag :value="statusLabel(step.status)" :severity="statusSeverity(step.status)" />
              <span v-if="step.completedAt" class="text-sm text-surface-500">{{
                formatDateTime(step.completedAt)
              }}</span>
            </div>
            <div v-if="step.approvers && step.approvers.length > 0" class="mt-2 space-y-1">
              <div v-for="approver in step.approvers" :key="approver.id" class="text-sm">
                <span class="text-surface-500">承認者 #{{ approver.approverUserId }}:</span>
                <span class="ml-1 font-medium">{{ decisionLabel(approver.decision) }}</span>
                <span v-if="approver.decisionComment" class="ml-2 text-surface-500"
                  >「{{ approver.decisionComment }}」</span
                >
              </div>
            </div>
          </div>
        </div>
      </div>
      <p v-else class="text-surface-500">承認ステップはありません</p>
    </template>
  </Card>
</template>
