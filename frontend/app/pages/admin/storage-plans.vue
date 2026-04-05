<script setup lang="ts">
import type {
  StoragePlanResponse,
  CreateStoragePlanRequest,
  TeamStorageUsageResponse,
} from '~/types/storage-plan'

definePageMeta({ middleware: 'auth' })

const {
  getStoragePlans,
  createStoragePlan,
  updateStoragePlan,
  deleteStoragePlan,
  getStorageUsage,
} = useStoragePlanApi()
const { success, error: showError } = useNotification()

const plans = ref<StoragePlanResponse[]>([])
const usageList = ref<TeamStorageUsageResponse[]>([])
const loading = ref(true)
const activeTab = ref(0)

// 作成・編集ダイアログ
const showDialog = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const emptyForm = (): CreateStoragePlanRequest => ({
  name: '', freeQuotaBytes: 0, monthlyPrice: 0, yearlyPrice: 0, overageUnitPrice: 0, hardCapBytes: 0,
})
const form = ref<CreateStoragePlanRequest>(emptyForm())

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`
}

function formatYen(value: number): string {
  return `¥${value.toLocaleString('ja-JP')}`
}

function usageSeverity(percent: number): string {
  if (percent >= 90) return 'danger'
  if (percent >= 70) return 'warning'
  return 'success'
}

async function loadPlans() {
  loading.value = true
  try {
    const res = await getStoragePlans()
    plans.value = res.data
  } catch {
    showError('ストレージプラン一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadUsage() {
  try {
    const res = await getStorageUsage()
    usageList.value = res.data
  } catch {
    showError('ストレージ使用状況の取得に失敗しました')
  }
}

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showDialog.value = true
}

function openEdit(plan: StoragePlanResponse) {
  editingId.value = plan.id
  const { name, freeQuotaBytes, monthlyPrice, yearlyPrice, overageUnitPrice, hardCapBytes } = plan
  form.value = { name, freeQuotaBytes, monthlyPrice, yearlyPrice, overageUnitPrice, hardCapBytes }
  showDialog.value = true
}

async function submitForm() {
  if (!form.value.name) return
  submitting.value = true
  try {
    if (editingId.value) {
      await updateStoragePlan(editingId.value, form.value)
      success('プランを更新しました')
    } else {
      await createStoragePlan(form.value)
      success('プランを作成しました')
    }
    showDialog.value = false
    await loadPlans()
  } catch {
    showError(editingId.value ? '更新に失敗しました' : '作成に失敗しました')
  } finally {
    submitting.value = false
  }
}

async function handleDelete(id: number) {
  try {
    await deleteStoragePlan(id)
    success('プランを削除しました')
    await loadPlans()
  } catch {
    showError('削除に失敗しました')
  }
}

onMounted(async () => {
  await Promise.all([loadPlans(), loadUsage()])
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">ストレージプラン管理</h1>
      <Button label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <TabView v-model:active-index="activeTab">
      <!-- プラン一覧タブ -->
      <TabPanel header="プラン一覧">
        <PageLoading v-if="loading" size="40px" />

        <DataTable v-else :value="plans" data-key="id" striped-rows>
          <template #empty>
            <div class="py-12 text-center">
              <i class="pi pi-database mb-3 text-4xl text-surface-300" />
              <p class="text-surface-400">プランがありません</p>
            </div>
          </template>

          <Column field="name" header="プラン名" />

          <Column header="無料枠" style="width: 120px">
            <template #body="{ data }">
              {{ formatBytes(data.freeQuotaBytes) }}
            </template>
          </Column>

          <Column header="月額" style="width: 110px">
            <template #body="{ data }">
              {{ formatYen(data.monthlyPrice) }}
            </template>
          </Column>

          <Column header="年額" style="width: 110px">
            <template #body="{ data }">
              {{ formatYen(data.yearlyPrice) }}
            </template>
          </Column>

          <Column header="超過単価" style="width: 110px">
            <template #body="{ data }">
              {{ formatYen(data.overageUnitPrice) }}/GB
            </template>
          </Column>

          <Column header="ハードキャップ" style="width: 130px">
            <template #body="{ data }">
              {{ formatBytes(data.hardCapBytes) }}
            </template>
          </Column>

          <Column header="状態" style="width: 80px">
            <template #body="{ data }">
              <Tag
                :value="data.isActive ? '有効' : '無効'"
                :severity="data.isActive ? 'success' : 'secondary'"
              />
            </template>
          </Column>

          <Column header="操作" style="width: 120px">
            <template #body="{ data }">
              <div class="flex gap-1">
                <Button
                  icon="pi pi-pencil"
                  size="small"
                  severity="secondary"
                  text
                  v-tooltip="'編集'"
                  @click="openEdit(data)"
                />
                <Button
                  icon="pi pi-trash"
                  size="small"
                  severity="danger"
                  text
                  v-tooltip="'削除'"
                  @click="handleDelete(data.id)"
                />
              </div>
            </template>
          </Column>
        </DataTable>
      </TabPanel>

      <!-- チーム使用状況タブ -->
      <TabPanel header="チーム使用状況">
        <DataTable :value="usageList" data-key="teamId" striped-rows>
          <template #empty>
            <div class="py-12 text-center">
              <i class="pi pi-chart-bar mb-3 text-4xl text-surface-300" />
              <p class="text-surface-400">使用状況データがありません</p>
            </div>
          </template>

          <Column field="teamName" header="チーム名" />
          <Column field="planName" header="プラン" style="width: 140px" />

          <Column header="使用量" style="width: 180px">
            <template #body="{ data }">
              {{ formatBytes(data.usedBytes) }} / {{ formatBytes(data.totalBytes) }}
            </template>
          </Column>

          <Column header="使用率" style="width: 200px">
            <template #body="{ data }">
              <div class="flex items-center gap-2">
                <ProgressBar
                  :value="data.usagePercent"
                  :show-value="false"
                  class="h-2 flex-1"
                  :class="{ 'p-progressbar-danger': data.usagePercent >= 90 }"
                />
                <Tag
                  :value="`${data.usagePercent}%`"
                  :severity="usageSeverity(data.usagePercent)"
                  class="min-w-[3.5rem] text-center"
                />
              </div>
            </template>
          </Column>
        </DataTable>
      </TabPanel>
    </TabView>

    <!-- 作成・編集ダイアログ -->
    <Dialog
      v-model:visible="showDialog"
      :header="editingId ? 'プランを編集' : 'プランを作成'"
      :style="{ width: '520px' }"
      modal
      :draggable="false"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">
            プラン名 <span class="text-red-500">*</span>
          </label>
          <InputText v-model="form.name" class="w-full" placeholder="例: スタンダード" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">無料枠 (bytes)</label>
          <InputNumber v-model="form.freeQuotaBytes" :min="0" class="w-full" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">月額 (円)</label>
            <InputNumber v-model="form.monthlyPrice" :min="0" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">年額 (円)</label>
            <InputNumber v-model="form.yearlyPrice" :min="0" class="w-full" />
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">超過従量単価 (円/GB)</label>
            <InputNumber v-model="form.overageUnitPrice" :min="0" :max-fraction-digits="2" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">ハードキャップ (bytes)</label>
            <InputNumber v-model="form.hardCapBytes" :min="0" class="w-full" />
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showDialog = false" />
        <Button
          :label="editingId ? '更新する' : '作成する'"
          icon="pi pi-check"
          :loading="submitting"
          :disabled="!form.name"
          @click="submitForm"
        />
      </template>
    </Dialog>
  </div>
</template>
