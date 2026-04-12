<script setup lang="ts">
import type { AdRateCardResponse, PricingModel } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const advertiserApi = useAdvertiserApi()
const { success, error: showError } = useNotification()

const rateCards = ref<AdRateCardResponse[]>([])
const loading = ref(true)
const showCreate = ref(false)
const creating = ref(false)

const form = ref({
  targetPrefecture: '',
  targetTemplate: '',
  pricingModel: 'CPM' as PricingModel,
  unitPrice: 0,
  minDailyBudget: 500,
  effectiveFrom: '',
})

const pricingOptions = [
  { label: 'CPM', value: 'CPM' },
  { label: 'CPC', value: 'CPC' },
]

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.adminGetRateCards()
    rateCards.value = res.data
  }
  catch { rateCards.value = [] }
  finally { loading.value = false }
}

async function create() {
  if (!form.value.unitPrice || !form.value.effectiveFrom) return
  creating.value = true
  try {
    await advertiserApi.adminCreateRateCard({
      targetPrefecture: form.value.targetPrefecture || undefined,
      targetTemplate: form.value.targetTemplate || undefined,
      pricingModel: form.value.pricingModel,
      unitPrice: form.value.unitPrice,
      minDailyBudget: form.value.minDailyBudget,
      effectiveFrom: form.value.effectiveFrom,
    })
    success('料金カードを作成しました')
    showCreate.value = false
    await load()
  }
  catch { showError('作成に失敗しました') }
  finally { creating.value = false }
}

async function remove(id: number) {
  try {
    await advertiserApi.adminDeleteRateCard(id)
    success('削除しました')
    await load()
  }
  catch { showError('削除に失敗しました（過去の料金は削除できません）') }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="広告料金カード管理" />
      <Button label="新規作成" icon="pi pi-plus" @click="showCreate = true" />
    </div>

    <ProgressSpinner v-if="loading" class="flex justify-center py-10" />

    <DataTable v-else :value="rateCards" striped-rows>
      <Column field="targetPrefecture" header="都道府県">
        <template #body="{ data }">{{ data.targetPrefecture || '全国' }}</template>
      </Column>
      <Column field="targetTemplate" header="テンプレート">
        <template #body="{ data }">{{ data.targetTemplate || '共通' }}</template>
      </Column>
      <Column field="pricingModel" header="課金モデル" />
      <Column field="unitPrice" header="単価" />
      <Column field="minDailyBudget" header="最低日予算">
        <template #body="{ data }">¥{{ data.minDailyBudget.toLocaleString() }}</template>
      </Column>
      <Column field="effectiveFrom" header="適用開始" />
      <Column field="effectiveUntil" header="適用終了">
        <template #body="{ data }">{{ data.effectiveUntil || '無期限' }}</template>
      </Column>
      <Column header="">
        <template #body="{ data }">
          <Button icon="pi pi-trash" severity="danger" text size="small" @click="remove(data.id)" />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="showCreate" header="料金カード作成" :style="{ width: '500px' }" modal>
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">都道府県（空欄=全国）</label>
          <InputText v-model="form.targetPrefecture" class="w-full" placeholder="例: 東京都" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">テンプレート（空欄=共通）</label>
          <InputText v-model="form.targetTemplate" class="w-full" placeholder="例: SPORTS" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">課金モデル</label>
          <Select v-model="form.pricingModel" :options="pricingOptions" option-label="label" option-value="value" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">単価</label>
          <InputNumber v-model="form.unitPrice" :min="0.01" :max-fraction-digits="4" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">最低日予算（円）</label>
          <InputNumber v-model="form.minDailyBudget" :min="100" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">適用開始日</label>
          <InputText v-model="form.effectiveFrom" type="date" class="w-full" />
        </div>
      </div>
      <div class="mt-4 flex justify-end">
        <Button label="作成" icon="pi pi-check" :loading="creating" @click="create" />
      </div>
    </Dialog>
  </div>
</template>
