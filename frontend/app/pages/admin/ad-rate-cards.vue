<script setup lang="ts">
import type { AdRateCardResponse, PricingModel } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const advertiserApi = useAdvertiserApi()
const { success, error: showError } = useNotification()
const { t } = useI18n()

const rateCards = ref<AdRateCardResponse[]>([])
const loading = ref(true)
/** 取得失敗は「料金カードなし」ではない。空状態へフォールバックせずエラー状態を出す。 */
const loadError = ref<unknown>(null)
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
  loadError.value = null
  try {
    const res = await advertiserApi.adminGetRateCards()
    rateCards.value = res.data
  }
  catch (error) {
    rateCards.value = []
    loadError.value = error
  }
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
    success(t('advertising.rate_cards.created'))
    showCreate.value = false
    await load()
  }
  catch { showError(t('advertising.rate_cards.create_failed')) }
  finally { creating.value = false }
}

async function remove(id: number) {
  try {
    await advertiserApi.adminDeleteRateCard(id)
    success(t('advertising.rate_cards.deleted'))
    await load()
  }
  catch { showError(t('advertising.rate_cards.delete_failed')) }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="$t('advertising.rate_cards.title')" />
      <Button :label="$t('advertising.actions.create')" icon="pi pi-plus" @click="showCreate = true" />
    </div>

    <div v-if="loading" class="flex justify-center py-10"><LoadingBounce /></div>

    <DashboardErrorState
      v-else-if="loadError"
      :error="loadError"
      testid="ad-rate-cards-error-state"
      @retry="load"
    />

    <DataTable v-else :value="rateCards" striped-rows>
      <Column field="targetPrefecture" :header="$t('advertising.rate_cards.col_prefecture')">
        <template #body="{ data }">{{ data.targetPrefecture || $t('advertising.rate_cards.nationwide') }}</template>
      </Column>
      <Column field="targetTemplate" :header="$t('advertising.rate_cards.col_template')">
        <template #body="{ data }">{{ data.targetTemplate || $t('advertising.rate_cards.common_template') }}</template>
      </Column>
      <Column field="pricingModel" :header="$t('advertising.rate_cards.col_pricing_model')" />
      <Column field="unitPrice" :header="$t('advertising.rate_cards.col_unit_price')" />
      <Column field="minDailyBudget" :header="$t('advertising.rate_cards.col_min_daily_budget')">
        <template #body="{ data }">¥{{ data.minDailyBudget.toLocaleString() }}</template>
      </Column>
      <Column field="effectiveFrom" :header="$t('advertising.rate_cards.col_effective_from')" />
      <Column field="effectiveUntil" :header="$t('advertising.rate_cards.col_effective_until')">
        <template #body="{ data }">{{ data.effectiveUntil || $t('advertising.rate_cards.unlimited') }}</template>
      </Column>
      <Column header="">
        <template #body="{ data }">
          <Button icon="pi pi-trash" severity="danger" text size="small" @click="remove(data.id)" />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="showCreate" :header="$t('advertising.rate_cards.dialog_title')" :style="{ width: '500px' }" modal>
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('advertising.rate_cards.label_prefecture') }}</label>
          <InputText v-model="form.targetPrefecture" class="w-full" :placeholder="$t('advertising.rate_cards.placeholder_prefecture')" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('advertising.rate_cards.label_template') }}</label>
          <InputText v-model="form.targetTemplate" class="w-full" :placeholder="$t('advertising.rate_cards.placeholder_template')" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('advertising.rate_cards.label_pricing_model') }}</label>
          <Select v-model="form.pricingModel" :options="pricingOptions" option-label="label" option-value="value" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('advertising.rate_cards.col_unit_price') }}</label>
          <InputNumber v-model="form.unitPrice" :min="0.01" :max-fraction-digits="4" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('advertising.rate_cards.label_min_daily_budget') }}</label>
          <InputNumber v-model="form.minDailyBudget" :min="100" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('advertising.rate_cards.label_effective_from') }}</label>
          <InputText v-model="form.effectiveFrom" type="date" class="w-full" />
        </div>
      </div>
      <div class="mt-4 flex justify-end">
        <Button :label="$t('button.create')" icon="pi pi-check" :loading="creating" @click="create" />
      </div>
    </Dialog>
  </div>
</template>
