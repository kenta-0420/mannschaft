<script setup lang="ts">
import type { ContentReportResponse } from '~/types/moderation'
definePageMeta({ middleware: 'auth' })
const { getReports } = useModerationApi()
const { showError } = useNotification()
const { relativeTime } = useRelativeTime()
const reports = ref<ContentReportResponse[]>([])
const loading = ref(false)
const statusFilter = ref<string | undefined>(undefined)
async function load() {
  loading.value = true
  try {
    const res = await getReports({ status: statusFilter.value })
    reports.value = res.data
  } catch {
    showError('通報一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}
function getStatusClass(s: string) {
  switch (s) {
    case 'PENDING':
      return 'bg-red-100 text-red-700'
    case 'REVIEWING':
      return 'bg-yellow-100 text-yellow-700'
    case 'RESOLVED':
      return 'bg-green-100 text-green-700'
    default:
      return 'bg-surface-100 text-surface-500'
  }
}
watch(statusFilter, () => load())
onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="通報・モデレーション" />
      <Select
        v-model="statusFilter"
        :options="[
          { label: 'すべて', value: undefined },
          { label: '未対応', value: 'PENDING' },
          { label: '対応中', value: 'REVIEWING' },
          { label: '解決済み', value: 'RESOLVED' },
        ]"
        option-label="label"
        option-value="value"
        placeholder="ステータス"
        class="w-36"
      />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-2">
      <div
        v-for="r in reports"
        :key="r.id"
        class="flex items-center gap-4 rounded-xl border border-surface-300 bg-surface-0 p-4"
      >
        <div class="flex-1">
          <div class="flex items-center gap-2">
            <span
              :class="getStatusClass(r.status)"
              class="rounded px-2 py-0.5 text-xs font-medium"
              >{{ r.status }}</span
            ><span class="text-xs text-surface-400">{{ r.targetType }}</span>
          </div>
          <p class="mt-1 text-sm">{{ r.reason }}</p>
          <p class="text-xs text-surface-400">
            報告者: {{ r.reporterName }} ・ {{ relativeTime(r.createdAt) }}
          </p>
        </div>
      </div>
      <DashboardEmptyState v-if="reports.length === 0" icon="pi pi-shield" message="通報はありません" />
    </div>
  </div>
</template>
