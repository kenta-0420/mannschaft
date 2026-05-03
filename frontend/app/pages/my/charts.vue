<script setup lang="ts">
import type { Chart } from '~/types/chart'

definePageMeta({ middleware: 'auth' })

const chartApi = useChartApi()
const notification = useNotification()

const charts = ref<Chart[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const selectedChart = ref<Chart | null>(null)
const showDetail = ref(false)

async function loadData(page = 0) {
  loading.value = true
  try {
    const res = await chartApi.listMyCharts({ page, size: 20 })
    charts.value = res.data
    totalRecords.value = res.meta.totalElements
  } catch {
    notification.error('カルテの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function handleSelect(chart: Chart) {
  try {
    selectedChart.value = (await chartApi.get(chart.teamId, chart.id)).data
    showDetail.value = true
  } catch {
    notification.error('カルテの詳細取得に失敗しました')
  }
}

onMounted(() => loadData())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader title="マイカルテ" />

    <PageLoading v-if="loading" />

    <div v-else class="flex flex-col gap-3">
      <div
        v-for="chart in charts"
        :key="chart.id"
        class="cursor-pointer rounded-xl border border-surface-300 bg-surface-0 p-4 transition-shadow hover:shadow-md"
        @click="handleSelect(chart)"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">
            {{ new Date(chart.visitDate).toLocaleDateString('ja-JP') }}
          </h3>
          <Badge
            :value="chart.status === 'DRAFT' ? '下書き' : '確定'"
            :severity="chart.status === 'DRAFT' ? 'warn' : 'success'"
          />
        </div>
        <p class="mt-1 text-xs text-surface-500">担当: {{ chart.staffName }}</p>
        <p v-if="chart.chiefComplaint" class="mt-1 text-sm text-surface-600 line-clamp-2">
          {{ chart.chiefComplaint }}
        </p>
      </div>
      <DashboardEmptyState v-if="charts.length === 0" icon="pi-file-edit" message="カルテがありません" />
    </div>

    <Dialog v-model:visible="showDetail" header="カルテ詳細" :modal="true" class="w-full max-w-2xl">
      <template v-if="selectedChart">
        <div class="space-y-3">
          <div class="grid gap-3 md:grid-cols-2">
            <div>
              <span class="text-sm text-surface-500">来店日:</span>
              {{ new Date(selectedChart.visitDate).toLocaleDateString('ja-JP') }}
            </div>
            <div>
              <span class="text-sm text-surface-500">担当:</span> {{ selectedChart.staffName }}
            </div>
          </div>
          <div v-if="selectedChart.chiefComplaint">
            <p class="text-sm text-surface-500">主訴・要望</p>
            <p>{{ selectedChart.chiefComplaint }}</p>
          </div>
          <div v-if="selectedChart.notes">
            <p class="text-sm text-surface-500">施術メモ</p>
            <p>{{ selectedChart.notes }}</p>
          </div>
          <div v-if="selectedChart.nextVisitRecommendation">
            <p class="text-sm text-surface-500">次回推奨</p>
            <p>{{ selectedChart.nextVisitRecommendation }}</p>
          </div>
          <div v-if="selectedChart.photos.length > 0">
            <p class="text-sm text-surface-500">写真</p>
            <div class="mt-2 grid grid-cols-3 gap-2">
              <img
                v-for="p in selectedChart.photos"
                :key="p.id"
                :src="p.photoUrl"
                :alt="p.caption ?? ''"
                class="h-24 w-full rounded-lg object-cover"
              >
            </div>
          </div>
        </div>
      </template>
    </Dialog>
  </div>
</template>
