<script setup lang="ts">
/**
 * F20.1 U-6: シスアド課金マスタ管理。
 *
 * `pages/admin/storage-plans.vue`（TabView + DataTable + Dialog CRUD）の様式を踏襲する。
 * plans / feature_catalog の CRUD・人数バンド一括置換・プラン→機能一括置換・手動付与フォーム・
 * 契約横断検索を1画面に集約する。
 *
 * 【既知の制約】シスアド admin API には人数バンド/プラン機能構成の個別 GET が無く
 * （`BillingPlanAdminResponse` に priceBands/features フィールドが無い）、置換は PUT のみで
 * 現在値の一括取得手段が無い。既存値の下見は公開カタログ `GET /billing/plans`
 * （enabled プランのみ・legacy プレフィックス除外）から可能な範囲で補完する
 * （disabled プランは下見できず空欄から編集開始になる）。
 */
import type {
  BillingPlanAdminResponse,
  BillingFeatureAdminResponse,
  BillingPriceBandInput,
  BillingPagedContractResponse,
} from '~/composables/useBillingApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const billingApi = useBillingApi()

const activeTab = ref(0)
const loading = ref(true)

// === プラン ===
const plans = ref<BillingPlanAdminResponse[]>([])
const catalogPlansByKey = ref<Record<string, { features: string[]; bands: BillingPriceBandInput[] }>>({})

async function loadPlans() {
  const res = await billingApi.listPlansAdmin()
  plans.value = res.data
}

async function loadCatalogHints() {
  // 下見用（既知の制約セクション参照）。失敗しても致命的ではないため握りつぶす。
  try {
    const res = await billingApi.getPlanCatalog()
    const map: Record<string, { features: string[]; bands: BillingPriceBandInput[] }> = {}
    for (const p of res.data.plans ?? []) {
      if (!p.planKey) continue
      map[p.planKey] = {
        features: (p.features ?? []).map(f => f.featureKey).filter((k): k is string => !!k),
        bands: (p.priceBands ?? []).map(b => ({
          scopeKind: b.scopeKind ?? 'TEAM',
          bandNo: b.bandNo ?? 1,
          minMembers: b.minMembers ?? 1,
          maxMembers: b.maxMembers,
          monthlyPriceJpy: b.monthlyPriceJpy,
        })),
      }
    }
    catalogPlansByKey.value = map
  }
  catch { /* 下見失敗は非致命的 */ }
}

const planDialogVisible = ref(false)
const planEditingKey = ref<string | null>(null)
const planForm = ref({ planKey: '', displayNameKey: '', descriptionKey: '', baseMonthlyPriceJpy: null as number | null, sortOrder: 0, enabled: true })
const planSubmitting = ref(false)

function openNewPlan() {
  planEditingKey.value = null
  planForm.value = { planKey: '', displayNameKey: '', descriptionKey: '', baseMonthlyPriceJpy: null, sortOrder: 0, enabled: true }
  planDialogVisible.value = true
}

function openEditPlan(plan: BillingPlanAdminResponse) {
  planEditingKey.value = plan.planKey ?? null
  planForm.value = {
    planKey: plan.planKey ?? '',
    displayNameKey: plan.displayNameKey ?? '',
    descriptionKey: plan.descriptionKey ?? '',
    baseMonthlyPriceJpy: plan.baseMonthlyPriceJpy ?? null,
    sortOrder: plan.sortOrder ?? 0,
    enabled: plan.enabled ?? true,
  }
  planDialogVisible.value = true
}

async function submitPlan() {
  if (!planForm.value.planKey.trim()) return
  planSubmitting.value = true
  try {
    const body = {
      displayNameKey: planForm.value.displayNameKey,
      descriptionKey: planForm.value.descriptionKey,
      baseMonthlyPriceJpy: planForm.value.baseMonthlyPriceJpy ?? undefined,
      sortOrder: planForm.value.sortOrder,
      enabled: planForm.value.enabled,
    }
    if (planEditingKey.value) {
      await billingApi.updatePlanAdmin(planEditingKey.value, body)
      notification.success(t('billing.admin.plansTab.updateSuccess'))
    }
    else {
      await billingApi.createPlanAdmin(planForm.value.planKey.trim(), body)
      notification.success(t('billing.admin.plansTab.createSuccess'))
    }
    planDialogVisible.value = false
    await loadPlans()
  }
  catch (err) {
    handleApiError(err, 'billing.admin.plan.submit')
  }
  finally {
    planSubmitting.value = false
  }
}

async function deletePlan(plan: BillingPlanAdminResponse) {
  if (!plan.planKey) return
  if (!confirm(t('billing.admin.plansTab.deleteConfirm'))) return
  try {
    await billingApi.deletePlanAdmin(plan.planKey)
    notification.success(t('billing.admin.plansTab.deleteSuccess'))
    await loadPlans()
  }
  catch (err) {
    handleApiError(err, 'billing.admin.plan.delete')
  }
}

// === 人数バンド編集 ===
const bandsDialogVisible = ref(false)
const bandsEditingPlanKey = ref<string | null>(null)
const bandsForm = ref<BillingPriceBandInput[]>([])
const bandsSubmitting = ref(false)

function openEditBands(plan: BillingPlanAdminResponse) {
  if (!plan.planKey) return
  bandsEditingPlanKey.value = plan.planKey
  bandsForm.value = catalogPlansByKey.value[plan.planKey]?.bands.map(b => ({ ...b })) ?? []
  bandsDialogVisible.value = true
}

function addBandRow() {
  const nextNo = bandsForm.value.length > 0 ? Math.max(...bandsForm.value.map(b => b.bandNo ?? 0)) + 1 : 1
  // 生成型 BillingPriceBandInput の任意項目は number | undefined（null 非許容）。未入力は undefined で表す。
  bandsForm.value.push({ scopeKind: 'TEAM', bandNo: nextNo, minMembers: 1, maxMembers: undefined, monthlyPriceJpy: undefined })
}

function removeBandRow(index: number) {
  bandsForm.value.splice(index, 1)
}

async function submitBands() {
  if (!bandsEditingPlanKey.value) return
  bandsSubmitting.value = true
  try {
    await billingApi.replacePriceBandsAdmin(bandsEditingPlanKey.value, { bands: bandsForm.value })
    notification.success(t('billing.admin.priceBandsTab.saveSuccess'))
    bandsDialogVisible.value = false
  }
  catch (err) {
    handleApiError(err, 'billing.admin.bands.submit')
  }
  finally {
    bandsSubmitting.value = false
  }
}

// === プラン機能構成編集 ===
const planFeaturesDialogVisible = ref(false)
const planFeaturesEditingKey = ref<string | null>(null)
const planFeaturesForm = ref<string[]>([])
const planFeaturesSubmitting = ref(false)

function openEditPlanFeatures(plan: BillingPlanAdminResponse) {
  if (!plan.planKey) return
  planFeaturesEditingKey.value = plan.planKey
  planFeaturesForm.value = [...(catalogPlansByKey.value[plan.planKey]?.features ?? [])]
  planFeaturesDialogVisible.value = true
}

async function submitPlanFeatures() {
  if (!planFeaturesEditingKey.value) return
  planFeaturesSubmitting.value = true
  try {
    await billingApi.replacePlanFeaturesAdmin(planFeaturesEditingKey.value, { featureKeys: planFeaturesForm.value })
    notification.success(t('billing.admin.planFeaturesTab.saveSuccess'))
    planFeaturesDialogVisible.value = false
  }
  catch (err) {
    handleApiError(err, 'billing.admin.planFeatures.submit')
  }
  finally {
    planFeaturesSubmitting.value = false
  }
}

// === 機能カタログ ===
const features = ref<BillingFeatureAdminResponse[]>([])

async function loadFeatures() {
  const res = await billingApi.listFeaturesAdmin()
  features.value = res.data
}

const featureDialogVisible = ref(false)
const featureEditingKey = ref<string | null>(null)
const featureForm = ref({
  featureKey: '',
  category: 'INTERNAL' as 'INTERNAL' | 'REVENUE',
  addonAvailable: false,
  addonPriceJpy: null as number | null,
  freeForNonprofit: false,
  displayNameKey: '',
  descriptionKey: '',
  sortOrder: 0,
  enabled: true,
})
const featureSubmitting = ref(false)

function openNewFeature() {
  featureEditingKey.value = null
  featureForm.value = {
    featureKey: '',
    category: 'INTERNAL',
    addonAvailable: false,
    addonPriceJpy: null,
    freeForNonprofit: false,
    displayNameKey: '',
    descriptionKey: '',
    sortOrder: 0,
    enabled: true,
  }
  featureDialogVisible.value = true
}

function openEditFeature(feature: BillingFeatureAdminResponse) {
  featureEditingKey.value = feature.featureKey ?? null
  featureForm.value = {
    featureKey: feature.featureKey ?? '',
    category: (feature.category as 'INTERNAL' | 'REVENUE') ?? 'INTERNAL',
    addonAvailable: feature.addonAvailable ?? false,
    addonPriceJpy: feature.addonPriceJpy ?? null,
    freeForNonprofit: feature.freeForNonprofit ?? false,
    displayNameKey: feature.displayNameKey ?? '',
    descriptionKey: feature.descriptionKey ?? '',
    sortOrder: feature.sortOrder ?? 0,
    enabled: feature.enabled ?? true,
  }
  featureDialogVisible.value = true
}

const featureFreeForNonprofitBlocked = computed(() => featureForm.value.category === 'REVENUE' && featureForm.value.freeForNonprofit)

async function submitFeature() {
  if (!featureForm.value.featureKey.trim()) return
  featureSubmitting.value = true
  try {
    const body = {
      category: featureForm.value.category,
      addonAvailable: featureForm.value.addonAvailable,
      addonPriceJpy: featureForm.value.addonPriceJpy ?? undefined,
      freeForNonprofit: featureForm.value.freeForNonprofit,
      displayNameKey: featureForm.value.displayNameKey,
      descriptionKey: featureForm.value.descriptionKey,
      sortOrder: featureForm.value.sortOrder,
      enabled: featureForm.value.enabled,
    }
    if (featureEditingKey.value) {
      await billingApi.updateFeatureAdmin(featureEditingKey.value, body)
      notification.success(t('billing.admin.featuresTab.updateSuccess'))
    }
    else {
      await billingApi.createFeatureAdmin(featureForm.value.featureKey.trim(), body)
      notification.success(t('billing.admin.featuresTab.createSuccess'))
    }
    featureDialogVisible.value = false
    await loadFeatures()
  }
  catch (err) {
    handleApiError(err, 'billing.admin.feature.submit')
  }
  finally {
    featureSubmitting.value = false
  }
}

// === 手動付与 ===
const grantForm = ref({
  scopeKind: 'TEAM' as 'USER' | 'TEAM' | 'ORG',
  scopeId: null as number | null,
  contractKind: 'PLAN' as 'PLAN' | 'ADDON',
  planKey: '',
  featureKey: '',
  note: '',
})
const grantSubmitting = ref(false)

async function submitGrant() {
  if (!grantForm.value.scopeId) return
  grantSubmitting.value = true
  try {
    await billingApi.grantAdmin({
      scopeKind: grantForm.value.scopeKind,
      scopeId: grantForm.value.scopeId,
      contractKind: grantForm.value.contractKind,
      planKey: grantForm.value.contractKind === 'PLAN' ? grantForm.value.planKey : undefined,
      featureKey: grantForm.value.contractKind === 'ADDON' ? grantForm.value.featureKey : undefined,
      note: grantForm.value.note || undefined,
    })
    notification.success(t('billing.admin.grantsTab.submitSuccess'))
    grantForm.value = { scopeKind: 'TEAM', scopeId: null, contractKind: 'PLAN', planKey: '', featureKey: '', note: '' }
  }
  catch (err) {
    handleApiError(err, 'billing.admin.grant.submit')
  }
  finally {
    grantSubmitting.value = false
  }
}

// === 契約横断検索 ===
const searchForm = ref({ scopeKind: '', scopeId: null as number | null, status: '' })
const searchResult = ref<BillingPagedContractResponse | null>(null)
const searchLoading = ref(false)

async function submitSearch() {
  searchLoading.value = true
  try {
    const res = await billingApi.searchContractsAdmin({
      scopeKind: searchForm.value.scopeKind || undefined,
      scopeId: searchForm.value.scopeId ?? undefined,
      status: searchForm.value.status || undefined,
      page: 0,
      size: 20,
    })
    searchResult.value = res.data
  }
  catch (err) {
    handleApiError(err, 'billing.admin.contracts.search')
  }
  finally {
    searchLoading.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([loadPlans(), loadFeatures(), loadCatalogHints()])
  }
  catch (err) {
    handleApiError(err, 'billing.admin.load')
  }
  finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader :title="t('billing.admin.title')" />

    <Message severity="warn" :closable="false" class="mb-4 text-sm">
      {{ t('billing.admin.priceWarning') }}
    </Message>

    <PageLoading v-if="loading" />

    <TabView v-else v-model:active-index="activeTab">
      <!-- プラン -->
      <TabPanel :header="t('billing.admin.tabs.plans')" value="0">
        <div class="mb-3 flex justify-end">
          <Button :label="t('billing.admin.plansTab.newPlan')" icon="pi pi-plus" @click="openNewPlan" />
        </div>
        <DataTable :value="plans" data-key="planKey" striped-rows>
          <Column field="planKey" :header="t('billing.admin.plansTab.planKey')" />
          <Column field="displayNameKey" :header="t('billing.admin.plansTab.displayNameKey')" />
          <Column :header="t('billing.admin.plansTab.baseMonthlyPriceJpy')">
            <template #body="{ data }">
              {{ data.baseMonthlyPriceJpy == null ? t('billing.admin.plansTab.priceUndecided') : `¥${data.baseMonthlyPriceJpy.toLocaleString('ja-JP')}` }}
            </template>
          </Column>
          <Column :header="t('billing.admin.plansTab.enabled')" style="width: 90px">
            <template #body="{ data }">
              <Tag :value="data.enabled ? t('billing.admin.plansTab.enabled') : '—'" :severity="data.enabled ? 'success' : 'secondary'" />
            </template>
          </Column>
          <Column header="" style="width: 220px">
            <template #body="{ data }">
              <div class="flex gap-1">
                <Button v-tooltip="t('billing.admin.plansTab.editPlan')" icon="pi pi-pencil" size="small" severity="secondary" text @click="openEditPlan(data)" />
                <Button v-tooltip="t('billing.admin.plansTab.editFeatures')" icon="pi pi-list-check" size="small" severity="secondary" text @click="openEditPlanFeatures(data)" />
                <Button v-tooltip="t('billing.admin.plansTab.editPriceBands')" icon="pi pi-users" size="small" severity="secondary" text @click="openEditBands(data)" />
                <Button v-tooltip="t('button.delete')" icon="pi pi-trash" size="small" severity="danger" text @click="deletePlan(data)" />
              </div>
            </template>
          </Column>
        </DataTable>
      </TabPanel>

      <!-- 機能カタログ -->
      <TabPanel :header="t('billing.admin.tabs.features')" value="1">
        <div class="mb-3 flex justify-end">
          <Button :label="t('billing.admin.featuresTab.newFeature')" icon="pi pi-plus" @click="openNewFeature" />
        </div>
        <DataTable :value="features" data-key="featureKey" striped-rows>
          <Column field="featureKey" :header="t('billing.admin.featuresTab.featureKey')" />
          <Column :header="t('billing.admin.featuresTab.category')" style="width: 110px">
            <template #body="{ data }">
              <Tag :value="data.category === 'REVENUE' ? t('billing.admin.featuresTab.categoryRevenue') : t('billing.admin.featuresTab.categoryInternal')" :severity="data.category === 'REVENUE' ? 'warn' : 'secondary'" />
            </template>
          </Column>
          <Column :header="t('billing.admin.featuresTab.addonAvailable')" style="width: 110px">
            <template #body="{ data }">
              <i v-if="data.addonAvailable" class="pi pi-check text-primary" />
            </template>
          </Column>
          <Column :header="t('billing.admin.featuresTab.addonPriceJpy')" style="width: 130px">
            <template #body="{ data }">
              {{ data.addonPriceJpy == null ? '—' : `¥${data.addonPriceJpy.toLocaleString('ja-JP')}` }}
            </template>
          </Column>
          <Column header="" style="width: 90px">
            <template #body="{ data }">
              <Button v-tooltip="t('billing.admin.featuresTab.editFeature')" icon="pi pi-pencil" size="small" severity="secondary" text @click="openEditFeature(data)" />
            </template>
          </Column>
        </DataTable>
      </TabPanel>

      <!-- 手動付与 -->
      <TabPanel :header="t('billing.admin.tabs.grants')" value="2">
        <div class="max-w-md space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.grantsTab.scopeKind') }}</label>
            <Select v-model="grantForm.scopeKind" :options="['USER', 'TEAM', 'ORG']" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.grantsTab.scopeId') }}</label>
            <InputNumber v-model="grantForm.scopeId" class="w-full" :use-grouping="false" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.grantsTab.contractKind') }}</label>
            <Select
              v-model="grantForm.contractKind"
              :options="['PLAN', 'ADDON']"
              :option-label="(v: string) => v === 'PLAN' ? t('billing.admin.grantsTab.contractKindPlan') : t('billing.admin.grantsTab.contractKindAddon')"
              class="w-full"
            />
          </div>
          <div v-if="grantForm.contractKind === 'PLAN'">
            <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.grantsTab.planKey') }}</label>
            <InputText v-model="grantForm.planKey" class="w-full" />
          </div>
          <div v-else>
            <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.grantsTab.featureKey') }}</label>
            <InputText v-model="grantForm.featureKey" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.grantsTab.note') }}</label>
            <Textarea v-model="grantForm.note" class="w-full" rows="2" :placeholder="t('billing.admin.grantsTab.notePlaceholder')" />
          </div>
          <Button :label="t('billing.admin.grantsTab.submit')" :loading="grantSubmitting" :disabled="!grantForm.scopeId" @click="submitGrant" />
        </div>
      </TabPanel>

      <!-- 契約横断検索 -->
      <TabPanel :header="t('billing.admin.tabs.contracts')" value="3">
        <div class="mb-4 flex flex-wrap items-end gap-3">
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.admin.contractsTab.scopeKind') }}</label>
            <Select v-model="searchForm.scopeKind" :options="['', 'USER', 'TEAM', 'ORG']" class="w-36" />
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.admin.contractsTab.scopeId') }}</label>
            <InputNumber v-model="searchForm.scopeId" class="w-36" :use-grouping="false" />
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.admin.contractsTab.status') }}</label>
            <Select v-model="searchForm.status" :options="['', 'PENDING', 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED']" class="w-40" />
          </div>
          <Button :label="t('billing.admin.contractsTab.search')" icon="pi pi-search" :loading="searchLoading" @click="submitSearch" />
        </div>
        <DataTable :value="searchResult?.content ?? []" data-key="contractId" striped-rows>
          <template #empty>
            <p class="py-6 text-center text-sm text-surface-500">{{ t('billing.admin.contractsTab.empty') }}</p>
          </template>
          <Column field="contractId" :header="t('billing.admin.contractsTab.contractId')" />
          <Column :header="t('billing.admin.contractsTab.planOrFeature')">
            <template #body="{ data }">{{ data.planKey ?? data.featureKey }}</template>
          </Column>
          <Column field="status" :header="t('billing.admin.contractsTab.status')" style="width: 110px" />
          <Column field="contractedAt" :header="t('billing.admin.contractsTab.contractedAt')" />
          <Column :header="t('billing.admin.contractsTab.priceJpySnapshot')">
            <template #body="{ data }">
              {{ data.priceJpySnapshot == null ? t('billing.admin.contractsTab.priceFree') : `¥${data.priceJpySnapshot.toLocaleString('ja-JP')}` }}
            </template>
          </Column>
        </DataTable>
      </TabPanel>
    </TabView>

    <!-- プラン作成・編集ダイアログ -->
    <Dialog v-model:visible="planDialogVisible" :header="planEditingKey ? t('billing.admin.plansTab.editPlan') : t('billing.admin.plansTab.newPlan')" :style="{ width: '480px' }" modal :draggable="false">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.plansTab.planKey') }}</label>
          <InputText v-model="planForm.planKey" class="w-full" :disabled="!!planEditingKey" placeholder="FULL" />
          <p v-if="!planEditingKey" class="mt-1 text-xs text-surface-400">{{ t('billing.admin.plansTab.planKeyExistsNote') }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.plansTab.displayNameKey') }}</label>
          <InputText v-model="planForm.displayNameKey" class="w-full" placeholder="billing.plans.full.name" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.plansTab.descriptionKey') }}</label>
          <InputText v-model="planForm.descriptionKey" class="w-full" placeholder="billing.plans.full.description" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.plansTab.baseMonthlyPriceJpy') }}</label>
          <InputNumber v-model="planForm.baseMonthlyPriceJpy" class="w-full" :use-grouping="false" :placeholder="t('billing.admin.plansTab.priceUndecided')" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.plansTab.sortOrder') }}</label>
          <InputNumber v-model="planForm.sortOrder" class="w-full" :use-grouping="false" />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="planForm.enabled" />
          <span class="text-sm">{{ t('billing.admin.plansTab.enabled') }}</span>
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="planDialogVisible = false" />
        <Button :label="planEditingKey ? t('button.update') : t('button.create')" :loading="planSubmitting" :disabled="!planForm.planKey.trim()" @click="submitPlan" />
      </template>
    </Dialog>

    <!-- 人数バンド編集ダイアログ -->
    <Dialog v-model:visible="bandsDialogVisible" :header="t('billing.admin.plansTab.editPriceBands')" :style="{ width: '620px' }" modal :draggable="false">
      <p class="mb-3 text-xs text-surface-500">{{ t('billing.admin.priceBandsTab.validationNote') }}</p>
      <div class="space-y-3">
        <div v-for="(band, i) in bandsForm" :key="i" class="grid grid-cols-6 items-end gap-2 border-b border-surface-100 pb-3 dark:border-surface-800">
          <div>
            <label class="mb-1 block text-xs">{{ t('billing.admin.priceBandsTab.scopeKind') }}</label>
            <Select v-model="band.scopeKind" :options="['TEAM', 'ORG']" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-xs">{{ t('billing.admin.priceBandsTab.bandNo') }}</label>
            <InputNumber v-model="band.bandNo" class="w-full" :use-grouping="false" :min="1" />
          </div>
          <div>
            <label class="mb-1 block text-xs">{{ t('billing.admin.priceBandsTab.minMembers') }}</label>
            <InputNumber v-model="band.minMembers" class="w-full" :use-grouping="false" :min="1" />
          </div>
          <div>
            <label class="mb-1 block text-xs">{{ t('billing.admin.priceBandsTab.maxMembers') }}</label>
            <InputNumber v-model="band.maxMembers" class="w-full" :use-grouping="false" :placeholder="t('billing.admin.priceBandsTab.maxMembersUnlimited')" />
          </div>
          <div>
            <label class="mb-1 block text-xs">{{ t('billing.admin.priceBandsTab.monthlyPriceJpy') }}</label>
            <InputNumber v-model="band.monthlyPriceJpy" class="w-full" :use-grouping="false" />
          </div>
          <Button icon="pi pi-trash" severity="danger" text @click="removeBandRow(i)" />
        </div>
        <Button :label="t('billing.admin.priceBandsTab.addBand')" icon="pi pi-plus" text @click="addBandRow" />
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="bandsDialogVisible = false" />
        <Button :label="t('button.save')" :loading="bandsSubmitting" @click="submitBands" />
      </template>
    </Dialog>

    <!-- プラン機能構成編集ダイアログ -->
    <Dialog v-model:visible="planFeaturesDialogVisible" :header="t('billing.admin.planFeaturesTab.title')" :style="{ width: '480px' }" modal :draggable="false">
      <div class="space-y-2">
        <div v-for="feature in features" :key="feature.featureKey" class="flex items-center gap-2">
          <Checkbox
            v-model="planFeaturesForm"
            :value="feature.featureKey"
            :input-id="`pf-${feature.featureKey}`"
          />
          <label :for="`pf-${feature.featureKey}`" class="text-sm">{{ feature.featureKey }}</label>
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="planFeaturesDialogVisible = false" />
        <Button :label="t('button.save')" :loading="planFeaturesSubmitting" @click="submitPlanFeatures" />
      </template>
    </Dialog>

    <!-- 機能作成・編集ダイアログ -->
    <Dialog v-model:visible="featureDialogVisible" :header="featureEditingKey ? t('billing.admin.featuresTab.editFeature') : t('billing.admin.featuresTab.newFeature')" :style="{ width: '480px' }" modal :draggable="false">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.featuresTab.featureKey') }}</label>
          <InputText v-model="featureForm.featureKey" class="w-full" :disabled="!!featureEditingKey" placeholder="ads.hide" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.plansTab.displayNameKey') }}</label>
          <InputText v-model="featureForm.displayNameKey" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.plansTab.descriptionKey') }}</label>
          <InputText v-model="featureForm.descriptionKey" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.featuresTab.category') }}</label>
          <Select
            v-model="featureForm.category"
            :options="['INTERNAL', 'REVENUE']"
            :option-label="(v: string) => v === 'REVENUE' ? t('billing.admin.featuresTab.categoryRevenue') : t('billing.admin.featuresTab.categoryInternal')"
            class="w-full"
          />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="featureForm.addonAvailable" />
          <span class="text-sm">{{ t('billing.admin.featuresTab.addonAvailable') }}</span>
        </div>
        <div v-if="featureForm.addonAvailable">
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.featuresTab.addonPriceJpy') }}</label>
          <InputNumber v-model="featureForm.addonPriceJpy" class="w-full" :use-grouping="false" />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="featureForm.freeForNonprofit" />
          <span class="text-sm">{{ t('billing.admin.featuresTab.freeForNonprofit') }}</span>
        </div>
        <Message v-if="featureFreeForNonprofitBlocked" severity="warn" :closable="false" class="text-xs">
          {{ t('billing.admin.featuresTab.freeForNonprofitRevenueWarning') }}
        </Message>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.admin.plansTab.sortOrder') }}</label>
          <InputNumber v-model="featureForm.sortOrder" class="w-full" :use-grouping="false" />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="featureForm.enabled" />
          <span class="text-sm">{{ t('billing.admin.plansTab.enabled') }}</span>
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="featureDialogVisible = false" />
        <Button
          :label="featureEditingKey ? t('button.update') : t('button.create')"
          :loading="featureSubmitting"
          :disabled="!featureForm.featureKey.trim() || featureFreeForNonprofitBlocked"
          @click="submitFeature"
        />
      </template>
    </Dialog>
  </div>
</template>
