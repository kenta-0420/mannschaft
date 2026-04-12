<script setup lang="ts">
import type { OnboardingProgress } from '~/types/onboarding'

definePageMeta({
  middleware: 'auth',
})

const onboardingApi = useOnboardingApi()
const notification = useNotification()

const progresses = ref<OnboardingProgress[]>([])
const loading = ref(true)

async function loadProgresses() {
  loading.value = true
  try {
    progresses.value = await onboardingApi.listMyProgresses()
  } catch {
    notification.error('オンボーディング情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function handleComplete(progressId: number, stepId: number) {
  try {
    await onboardingApi.completeStep(progressId, stepId)
    notification.success('ステップを完了しました')
    await loadProgresses()
  } catch {
    notification.error('ステップの完了に失敗しました')
  }
}

const activeProgresses = computed(() => progresses.value.filter((p) => p.status === 'IN_PROGRESS'))
const completedProgresses = computed(() =>
  progresses.value.filter((p) => p.status !== 'IN_PROGRESS'),
)

onMounted(loadProgresses)
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader title="オンボーディング" />

    <PageLoading v-if="loading" />

    <template v-else-if="progresses.length === 0">
      <DashboardEmptyState icon="pi-check-circle" message="現在オンボーディングはありません" />
    </template>

    <template v-else>
      <div v-if="activeProgresses.length > 0" class="space-y-6">
        <h2 class="text-lg font-semibold">進行中</h2>
        <OnboardingChecklist
          v-for="progress in activeProgresses"
          :key="progress.id"
          :progress="progress"
          @complete="handleComplete"
        />
      </div>

      <div v-if="completedProgresses.length > 0" class="mt-8 space-y-6">
        <h2 class="text-lg font-semibold text-surface-500">完了済み</h2>
        <OnboardingChecklist
          v-for="progress in completedProgresses"
          :key="progress.id"
          :progress="progress"
          @complete="handleComplete"
        />
      </div>
    </template>
  </div>
</template>
