<script setup lang="ts">
import type {
  ModulePricingResponse,
  ModulePricingHistoryResponse,
} from '~/types/module-pricing'

definePageMeta({ middleware: 'auth' })

const modulePricingApi = useModulePricingApi()
const { success, error: showError } = useNotification()

const modules = ref<ModulePricingResponse[]>([])
const loading = ref(true)

// 編集ダイアログ
const showEditDialog = ref(false)
const editTarget = ref<ModulePricingResponse | null>(null)
const editForm = ref({ monthlyPrice: 0, yearlyPrice: 0, trialDays: 0 })
const submitting = ref(false)

// 履歴ダイアログ
const showHistoryDialog = ref(false)
const historyTarget = ref<ModulePricingResponse | null>(null)
const historyItems = ref<ModulePricingHistoryResponse[]>([])
const historyLoading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await modulePricingApi.getModulePricingList()
    modules.value = res.data
  } catch {
    showError('モジュール価格一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openEdit(mod: ModulePricingResponse) {
  editTarget.value = mod
  editForm.value = {
    monthlyPrice: mod.monthlyPrice,
    yearlyPrice: mod.yearlyPrice,
    trialDays: mod.trialDays,
  }
  showEditDialog.value = true
}

async function submitEdit() {
  if (!editTarget.value) return
  submitting.value = true
  try {
    await modulePricingApi.updateModulePricing(editTarget.value.moduleId, editForm.value)
    success('価格を更新しました')
    showEditDialog.value = false
    await load()
  } catch {
    showError('価格の更新に失敗しました')
  } finally {
    submitting.value = false
  }
}

async function openHistory(mod: ModulePricingResponse) {
  historyTarget.value = mod
  historyItems.value = []
  showHistoryDialog.value = true
  historyLoading.value = true
  try {
    const res = await modulePricingApi.getModulePricingHistory(mod.moduleId)
    historyItems.value = res.data
  } catch {
    showError('変更履歴の取得に失敗しました')
  } finally {
    historyLoading.value = false
  }
}

function formatCurrency(value: number, currency: string): string {
  return new Intl.NumberFormat('ja-JP', { style: 'currency', currency }).format(value)
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('ja-JP')
}

function fieldLabel(field: string): string {
  switch (field) {
    case 'monthlyPrice': return '月額'
    case 'yearlyPrice': return '年額'
    case 'trialDays': return 'トライアル日数'
    default: return field
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">モジュール価格管理</h1>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="modules" striped-rows data-key="moduleId">
      <template #empty>
        <div class="py-8 text-center text-surface-500">モジュールがありません</div>
      </template>

      <Column field="moduleName" header="モジュール名" />
      <Column field="moduleSlug" header="スラグ" />

      <Column header="月額" style="width: 140px">
        <template #body="{ data }">
          {{ formatCurrency(data.monthlyPrice, data.currency) }}
        </template>
      </Column>

      <Column header="年額" style="width: 140px">
        <template #body="{ data }">
          {{ formatCurrency(data.yearlyPrice, data.currency) }}
        </template>
      </Column>

      <Column header="トライアル" style="width: 120px">
        <template #body="{ data }">
          {{ data.trialDays }}日
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

      <Column header="操作" style="width: 160px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button label="編集" size="small" severity="info" text @click="openEdit(data)" />
            <Button label="履歴" size="small" severity="secondary" text @click="openHistory(data)" />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 価格編集ダイアログ -->
    <Dialog
      v-model:visible="showEditDialog"
      header="価格設定を編集"
      :style="{ width: '480px' }"
      modal
      :draggable="false"
    >
      <div v-if="editTarget" class="flex flex-col gap-4">
        <div class="rounded-lg bg-surface-100 p-3">
          <p class="text-sm font-medium">{{ editTarget.moduleName }}</p>
          <p class="text-xs text-surface-500">{{ editTarget.moduleSlug }}</p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">月額価格（円）</label>
          <InputNumber v-model="editForm.monthlyPrice" :min="0" class="w-full" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">年額価格（円）</label>
          <InputNumber v-model="editForm.yearlyPrice" :min="0" class="w-full" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">トライアル日数</label>
          <InputNumber v-model="editForm.trialDays" :min="0" :max="365" class="w-full" />
        </div>
      </div>

      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showEditDialog = false" />
        <Button
          label="保存する"
          icon="pi pi-check"
          :loading="submitting"
          @click="submitEdit"
        />
      </template>
    </Dialog>

    <!-- 変更履歴ダイアログ -->
    <Dialog
      v-model:visible="showHistoryDialog"
      header="価格変更履歴"
      :style="{ width: '640px' }"
      modal
      :draggable="false"
    >
      <div v-if="historyTarget" class="mb-3 rounded-lg bg-surface-100 p-3">
        <p class="text-sm font-medium">{{ historyTarget.moduleName }}</p>
      </div>

      <PageLoading v-if="historyLoading" />

      <DataTable v-else :value="historyItems" striped-rows data-key="id">
        <template #empty>
          <div class="py-6 text-center text-surface-500">変更履歴がありません</div>
        </template>

        <Column header="項目" style="width: 120px">
          <template #body="{ data }">
            {{ fieldLabel(data.field) }}
          </template>
        </Column>

        <Column field="oldValue" header="変更前" style="width: 100px" />
        <Column field="newValue" header="変更後" style="width: 100px" />
        <Column field="changedByName" header="変更者" style="width: 120px" />

        <Column header="日時" style="width: 160px">
          <template #body="{ data }">
            <span class="text-sm">{{ formatDate(data.changedAt) }}</span>
          </template>
        </Column>
      </DataTable>
    </Dialog>
  </div>
</template>
