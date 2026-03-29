<script setup lang="ts">
import type { Chart, CreateChartRequest } from '~/types/chart'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const chartApi = useChartApi()
const notification = useNotification()
const { loadPermissions } = useRoleAccess('team', teamId)

const charts = ref<Chart[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const showFormDialog = ref(false)
const showDetailDialog = ref(false)
const editingChart = ref<Chart | undefined>()
const selectedChart = ref<Chart | null>(null)

async function loadData(page = 0) {
  loading.value = true
  try {
    await loadPermissions()
    const res = await chartApi.list(teamId.value, { page, size: 20 })
    charts.value = res.data
    totalRecords.value = res.meta.totalElements
  } catch {
    notification.error('カルテの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingChart.value = undefined
  showFormDialog.value = true
}

async function handleSelect(chart: Chart) {
  try {
    selectedChart.value = (await chartApi.get(teamId.value, chart.id)).data
    showDetailDialog.value = true
  } catch {
    notification.error('カルテの詳細取得に失敗しました')
  }
}

async function handleSave(data: CreateChartRequest) {
  try {
    if (editingChart.value) {
      await chartApi.update(teamId.value, editingChart.value.id, {
        ...data,
        version: editingChart.value.version,
      })
      notification.success('カルテを更新しました')
    } else {
      await chartApi.create(teamId.value, data)
      notification.success('カルテを作成しました')
    }
    showFormDialog.value = false
    await loadData()
  } catch {
    notification.error('カルテの保存に失敗しました')
  }
}

async function handlePin(chartId: number) {
  try {
    await chartApi.togglePin(teamId.value, chartId)
    await loadData()
  } catch {
    notification.error('ピン留めに失敗しました')
  }
}

async function handlePhotoUpload(file: File, type: string) {
  if (!selectedChart.value) return
  try {
    await chartApi.uploadPhoto(teamId.value, selectedChart.value.id, file, type)
    selectedChart.value = (await chartApi.get(teamId.value, selectedChart.value.id)).data
    notification.success('写真をアップロードしました')
  } catch {
    notification.error('アップロードに失敗しました')
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">カルテ管理</h1>

    <div v-if="loading" class="flex justify-center py-12"><ProgressSpinner /></div>

    <template v-else>
      <ChartList
        :charts="charts"
        :loading="loading"
        :total-records="totalRecords"
        @create="openCreate"
        @select="handleSelect"
        @pin="handlePin"
        @page="(e) => loadData(e.page)"
      />
    </template>

    <Dialog
      v-model:visible="showFormDialog"
      :header="editingChart ? 'カルテ編集' : '新規カルテ'"
      :modal="true"
      class="w-full max-w-2xl"
    >
      <ChartForm :chart="editingChart" @save="handleSave" @cancel="showFormDialog = false" />
    </Dialog>

    <Dialog
      v-model:visible="showDetailDialog"
      header="カルテ詳細"
      :modal="true"
      class="w-full max-w-3xl"
    >
      <template v-if="selectedChart">
        <div class="space-y-4">
          <div class="grid gap-4 md:grid-cols-2">
            <div>
              <span class="text-sm text-surface-500">顧客:</span>
              <strong>{{ selectedChart.clientName }}</strong>
            </div>
            <div>
              <span class="text-sm text-surface-500">来店日:</span>
              {{ new Date(selectedChart.visitDate).toLocaleDateString('ja-JP') }}
            </div>
            <div>
              <span class="text-sm text-surface-500">担当:</span> {{ selectedChart.staffName }}
            </div>
            <div>
              <span class="text-sm text-surface-500">次回推奨:</span>
              {{ selectedChart.nextVisitRecommendation ?? '-' }}
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
          <BeforeAfterPhoto
            v-if="selectedChart.sections.beforeAfter"
            :chart-id="selectedChart.id"
            :photos="selectedChart.photos"
            @upload="handlePhotoUpload"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
