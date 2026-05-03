<script setup lang="ts">
import type { FiscalYearResponse, BudgetSummary } from '~/types/budget'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)

const notification = useNotification()
const { getFiscalYears, getSummary, createFiscalYear } = useBudgetApi()

const fiscalYears = ref<FiscalYearResponse[]>([])
const selectedFy = ref<FiscalYearResponse | null>(null)
const summary = ref<BudgetSummary | null>(null)
const loading = ref(false)

const showCreateDialog = ref(false)
const saving = ref(false)

const currentYear = new Date().getFullYear()
const form = ref({
  name: `${currentYear}年度`,
  startDate: `${currentYear}-04-01`,
  endDate: `${currentYear + 1}-03-31`,
})

function openCreateDialog() {
  const y = new Date().getFullYear()
  form.value = {
    name: `${y}年度`,
    startDate: `${y}-04-01`,
    endDate: `${y + 1}-03-31`,
  }
  showCreateDialog.value = true
}

async function handleCreate() {
  if (!form.value.name.trim() || !form.value.startDate || !form.value.endDate) return
  saving.value = true
  try {
    await createFiscalYear('team', teamId, {
      name: form.value.name,
      startDate: form.value.startDate,
      endDate: form.value.endDate,
    })
    notification.success('年度を追加しました')
    showCreateDialog.value = false
    await load()
  } catch {
    notification.error('年度の追加に失敗しました')
  } finally {
    saving.value = false
  }
}

async function load() {
  try {
    const res = await getFiscalYears('team', teamId)
    fiscalYears.value = res.data
    if (res.data.length > 0) selectFy(res.data[0])
  } catch {
    notification.error('予算情報の取得に失敗しました')
  }
}

async function selectFy(fy: FiscalYearResponse) {
  selectedFy.value = fy
  loading.value = true
  try {
    const res = await getSummary('team', teamId, fy.id)
    summary.value = res.data
  } catch {
    notification.error('予算サマリーの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="予算・会計" />
      <Button label="費目を追加" icon="pi pi-plus" size="small" @click="openCreateDialog" />
    </div>
    <div v-if="fiscalYears.length > 0" class="mb-4">
      <SelectButton
        :model-value="selectedFy?.id"
        :options="fiscalYears.map((fy) => ({ label: fy.name, value: fy.id }))"
        option-label="label"
        option-value="value"
        @update:model-value="
          (id: number) => {
            const fy = fiscalYears.find((f) => f.id === id)
            if (fy) selectFy(fy)
          }
        "
      />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else-if="summary" class="grid gap-4 sm:grid-cols-3">
      <SectionCard>
        <p class="text-xs text-surface-400">収入</p>
        <p class="text-2xl font-bold text-green-600">¥{{ summary.totalIncome.toLocaleString() }}</p>
      </SectionCard>
      <SectionCard>
        <p class="text-xs text-surface-400">支出</p>
        <p class="text-2xl font-bold text-red-500">¥{{ summary.totalExpense.toLocaleString() }}</p>
      </SectionCard>
      <SectionCard>
        <p class="text-xs text-surface-400">残高</p>
        <p
          class="text-2xl font-bold"
          :class="summary.balance >= 0 ? 'text-primary' : 'text-red-500'"
        >
          ¥{{ summary.balance.toLocaleString() }}
        </p>
      </SectionCard>
    </div>
    <div v-if="!loading && summary" class="mt-4 flex flex-col gap-2">
      <div
        v-for="cat in summary.byCategory"
        :key="cat.categoryId"
        class="flex items-center justify-between rounded-lg border border-surface-100 px-4 py-2"
      >
        <span class="text-sm">{{ cat.categoryName }}</span>
        <div class="flex items-center gap-4 text-sm">
          <span class="text-surface-400">予算 ¥{{ cat.allocated.toLocaleString() }}</span>
          <span :class="cat.categoryType === 'INCOME' ? 'text-green-600' : 'text-red-500'"
            >実績 ¥{{ cat.actual.toLocaleString() }}</span
          >
          <span
            class="text-xs"
            :class="cat.burnPercent > 100 ? 'text-red-500 font-bold' : 'text-surface-400'"
            >{{ cat.burnPercent }}%</span
          >
        </div>
      </div>
    </div>
    <Dialog v-model:visible="showCreateDialog" modal header="年度を追加" :style="{ width: '26rem' }">
      <div class="flex flex-col gap-4 py-2">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">年度名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.name" placeholder="例: 2026年度" class="w-full" />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">開始日 <span class="text-red-500">*</span></label>
          <InputText v-model="form.startDate" type="date" class="w-full" />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">終了日 <span class="text-red-500">*</span></label>
          <InputText v-model="form.endDate" type="date" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text :disabled="saving" @click="showCreateDialog = false" />
        <Button label="追加" icon="pi pi-check" :loading="saving" @click="handleCreate" />
      </template>
    </Dialog>
  </div>
</template>
