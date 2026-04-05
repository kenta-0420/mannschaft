<script setup lang="ts">
import type { OnboardingProgress, OnboardingProgressStatus } from '~/types/onboarding'

defineProps<{
  progresses: OnboardingProgress[]
  loading?: boolean
}>()

const emit = defineEmits<{
  select: [progress: OnboardingProgress]
  remind: []
}>()

const statusFilter = ref<OnboardingProgressStatus | 'ALL'>('ALL')

const statusOptions = [
  { label: 'すべて', value: 'ALL' },
  { label: '進行中', value: 'IN_PROGRESS' },
  { label: '完了', value: 'COMPLETED' },
  { label: 'スキップ', value: 'SKIPPED' },
]

const statusSeverity = (status: OnboardingProgressStatus) => {
  const map: Record<string, string> = { IN_PROGRESS: 'info', COMPLETED: 'success', SKIPPED: 'warn' }
  return map[status] ?? 'info'
}

const statusLabel = (status: OnboardingProgressStatus) => {
  const map: Record<string, string> = { IN_PROGRESS: '進行中', COMPLETED: '完了', SKIPPED: 'スキップ' }
  return map[status] ?? status
}

const filteredProgresses = computed(() => {
  return (props: { progresses: OnboardingProgress[] }) => {
    if (statusFilter.value === 'ALL') return props.progresses
    return props.progresses.filter(p => p.status === statusFilter.value)
  }
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <SelectButton v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" />
      <Button label="未完了者にリマインダー" icon="pi pi-bell" size="small" severity="warn" outlined @click="emit('remind')" />
    </div>

    <DataTable
      :value="statusFilter === 'ALL' ? progresses : progresses.filter(p => p.status === statusFilter)"
      :loading="loading"
      data-key="id"
      striped-rows
      :row-class="() => 'cursor-pointer'"
      @row-click="(e: { data: OnboardingProgress }) => emit('select', e.data)"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">オンボーディング中のメンバーはいません</div>
      </template>
      <Column field="userName" header="メンバー" />
      <Column header="進捗">
        <template #body="{ data }">
          <div class="flex items-center gap-2">
            <ProgressBar :value="Math.round((data.completedSteps / data.totalSteps) * 100)" :show-value="false" class="w-24" style="height: 8px" />
            <span class="text-xs text-surface-500">{{ data.completedSteps }}/{{ data.totalSteps }}</span>
          </div>
        </template>
      </Column>
      <Column header="ステータス">
        <template #body="{ data }">
          <Badge :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="期限">
        <template #body="{ data }">
          <span v-if="data.deadlineAt" class="text-sm">
            {{ new Date(data.deadlineAt).toLocaleDateString('ja-JP') }}
          </span>
          <span v-else class="text-surface-400">-</span>
        </template>
      </Column>
      <Column header="開始日">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.startedAt).toLocaleDateString('ja-JP') }}</span>
        </template>
      </Column>
    </DataTable>
  </div>
</template>
