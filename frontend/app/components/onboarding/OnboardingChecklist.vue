<script setup lang="ts">
import type { OnboardingProgress } from '~/types/onboarding'

const props = defineProps<{
  progress: OnboardingProgress
}>()

const emit = defineEmits<{
  complete: [progressId: number, stepId: number]
}>()

const progressPercent = computed(() => {
  if (props.progress.totalSteps === 0) return 0
  return Math.round((props.progress.completedSteps / props.progress.totalSteps) * 100)
})

const statusSeverity = computed(() => {
  const map: Record<string, string> = { IN_PROGRESS: 'info', COMPLETED: 'success', SKIPPED: 'warn' }
  return map[props.progress.status] ?? 'info'
})

const statusLabel = computed(() => {
  const map: Record<string, string> = { IN_PROGRESS: '進行中', COMPLETED: '完了', SKIPPED: 'スキップ' }
  return map[props.progress.status] ?? props.progress.status
})

function isCompleted(stepId: number) {
  return props.progress.stepCompletions.some(sc => sc.stepId === stepId && sc.completedAt)
}

function stepIcon(stepType: string) {
  const map: Record<string, string> = {
    MANUAL: 'pi pi-check-square',
    URL: 'pi pi-external-link',
    FORM: 'pi pi-file-edit',
    KNOWLEDGE_BASE: 'pi pi-book',
    PROFILE_COMPLETION: 'pi pi-user',
  }
  return map[stepType] ?? 'pi pi-circle'
}
</script>

<template>
  <Card>
    <template #header>
      <div class="px-4 pt-4">
        <div class="mb-2 flex items-center justify-between">
          <h3 class="text-lg font-semibold">{{ progress.templateName }}</h3>
          <Badge :value="statusLabel" :severity="statusSeverity" />
        </div>
        <ProgressBar :value="progressPercent" :show-value="true" class="mb-2" />
        <p class="text-sm text-surface-500">
          {{ progress.completedSteps }} / {{ progress.totalSteps }} ステップ完了
        </p>
        <p v-if="progress.deadlineAt" class="text-xs text-surface-400">
          期限: {{ new Date(progress.deadlineAt).toLocaleDateString('ja-JP') }}
        </p>
      </div>
    </template>
    <template #content>
      <div class="space-y-3">
        <div
          v-for="step in progress.stepCompletions"
          :key="step.stepId"
          class="flex items-start gap-3 rounded-lg p-3 transition-colors"
          :class="isCompleted(step.stepId) ? 'bg-green-50 dark:bg-green-950/20' : 'bg-surface-50 dark:bg-surface-800'"
        >
          <div class="mt-0.5">
            <i
              v-if="isCompleted(step.stepId)"
              class="pi pi-check-circle text-green-500"
            />
            <i
              v-else
              :class="stepIcon(step.stepType)"
              class="text-surface-400"
            />
          </div>
          <div class="flex-1">
            <p class="text-sm font-medium" :class="isCompleted(step.stepId) ? 'line-through text-surface-400' : ''">
              {{ step.stepTitle }}
            </p>
            <p v-if="step.description" class="mt-1 text-xs text-surface-500">
              {{ step.description }}
            </p>
            <div v-if="!isCompleted(step.stepId) && progress.status === 'IN_PROGRESS'" class="mt-2 flex gap-2">
              <a
                v-if="step.stepType === 'URL' && step.referenceUrl"
                :href="step.referenceUrl"
                target="_blank"
                class="text-xs text-primary hover:underline"
              >
                <i class="pi pi-external-link mr-1" />リンクを開く
              </a>
              <Button
                v-if="step.stepType === 'MANUAL' || step.stepType === 'URL'"
                label="完了にする"
                icon="pi pi-check"
                size="small"
                severity="success"
                outlined
                @click="emit('complete', progress.id, step.stepId)"
              />
            </div>
          </div>
        </div>
      </div>
    </template>
  </Card>
</template>
