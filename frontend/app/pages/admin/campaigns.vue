<script setup lang="ts">
import type {
  DiscountCampaignResponse, CampaignCouponResponse, CampaignStatus,
  DiscountType, CampaignTarget, CouponUsageResponse,
} from '~/types/campaign'

definePageMeta({ middleware: 'auth' })

const campaignApi = useCampaignApi()
const { success, error: showError } = useNotification()

const activeTab = ref('campaigns')
const loading = ref(false)
const campaigns = ref<DiscountCampaignResponse[]>([])

const showCampaignDialog = ref(false)
const campaignSubmitting = ref(false)
const campaignForm = ref({
  name: '', description: '', discountType: 'PERCENTAGE' as DiscountType,
  discountValue: 10, target: 'ALL' as CampaignTarget,
  targetModuleId: '', targetPackageId: '', startDate: '', endDate: '',
})
const discountTypeOptions = [
  { label: '割合（%）', value: 'PERCENTAGE' },
  { label: '固定額（円）', value: 'FIXED_AMOUNT' },
]
const targetOptions = [
  { label: '全体', value: 'ALL' },
  { label: '特定モジュール', value: 'MODULE' },
  { label: '特定パッケージ', value: 'PACKAGE' },
]

const selectedCampaignId = ref<number | null>(null)
const coupons = ref<CampaignCouponResponse[]>([])
const couponsLoading = ref(false)
const showCouponDialog = ref(false)
const couponSubmitting = ref(false)
const couponForm = ref({ code: '', maxRedemptions: '' })

const showUsageDialog = ref(false)
const usageList = ref<CouponUsageResponse[]>([])
const usageLoading = ref(false)

async function loadCampaigns() {
  loading.value = true
  try { campaigns.value = (await campaignApi.getCampaigns()).data }
  catch { showError('キャンペーン一覧の取得に失敗しました') }
  finally { loading.value = false }
}

async function loadCoupons() {
  if (!selectedCampaignId.value) return
  couponsLoading.value = true
  try { coupons.value = (await campaignApi.getCampaignCoupons(selectedCampaignId.value)).data }
  catch { showError('クーポン一覧の取得に失敗しました') }
  finally { couponsLoading.value = false }
}

function openCampaignDialog() {
  campaignForm.value = {
    name: '', description: '', discountType: 'PERCENTAGE', discountValue: 10,
    target: 'ALL', targetModuleId: '', targetPackageId: '', startDate: '', endDate: '',
  }
  showCampaignDialog.value = true
}

async function submitCampaign() {
  const f = campaignForm.value
  if (!f.name || !f.startDate || !f.endDate) return
  campaignSubmitting.value = true
  try {
    await campaignApi.createCampaign({
      name: f.name, description: f.description || undefined,
      discountType: f.discountType, discountValue: f.discountValue, target: f.target,
      targetModuleId: f.targetModuleId || undefined,
      targetPackageId: f.targetPackageId || undefined,
      startDate: f.startDate, endDate: f.endDate,
    })
    success('キャンペーンを作成しました')
    showCampaignDialog.value = false
    loadCampaigns()
  } catch { showError('キャンペーンの作成に失敗しました') }
  finally { campaignSubmitting.value = false }
}

async function handleDelete(id: number) {
  try { await campaignApi.deleteCampaign(id); success('削除しました'); loadCampaigns() }
  catch { showError('削除に失敗しました') }
}

function selectForCoupons(id: number) {
  selectedCampaignId.value = id
  activeTab.value = 'coupons'
  loadCoupons()
}

async function submitCoupon() {
  if (!selectedCampaignId.value || !couponForm.value.code) return
  couponSubmitting.value = true
  try {
    await campaignApi.createCampaignCoupon(selectedCampaignId.value, {
      code: couponForm.value.code,
      maxRedemptions: couponForm.value.maxRedemptions ? Number(couponForm.value.maxRedemptions) : undefined,
    })
    success('クーポンを発行しました')
    showCouponDialog.value = false
    loadCoupons()
  } catch { showError('クーポンの発行に失敗しました') }
  finally { couponSubmitting.value = false }
}

async function viewUsage(couponId: number) {
  if (!selectedCampaignId.value) return
  usageLoading.value = true
  showUsageDialog.value = true
  try { usageList.value = (await campaignApi.getCouponUsage(selectedCampaignId.value, couponId)).data }
  catch { showError('利用状況の取得に失敗しました') }
  finally { usageLoading.value = false }
}

const statusMap: Record<CampaignStatus, { label: string; severity: string }> = {
  DRAFT: { label: '下書き', severity: 'secondary' },
  ACTIVE: { label: '有効', severity: 'success' },
  PAUSED: { label: '一時停止', severity: 'warning' },
  ENDED: { label: '終了', severity: 'info' },
  CANCELLED: { label: 'キャンセル', severity: 'danger' },
}
function discountLabel(type: DiscountType, v: number) {
  return type === 'PERCENTAGE' ? `${v}%` : `${v.toLocaleString('ja-JP')}円`
}
const targetMap: Record<CampaignTarget, string> = { ALL: '全体', MODULE: 'モジュール', PACKAGE: 'パッケージ' }

onMounted(loadCampaigns)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">割引キャンペーン・クーポン管理</h1>
    <PageLoading v-if="loading" />
    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="campaigns">キャンペーン</Tab>
          <Tab value="coupons">クーポン</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="campaigns">
            <div class="mb-3 flex justify-end">
              <Button label="新規作成" icon="pi pi-plus" @click="openCampaignDialog" />
            </div>
            <DataTable :value="campaigns" data-key="id" striped-rows>
              <template #empty>
                <div class="py-12 text-center"><p class="text-surface-400">キャンペーンがありません</p></div>
              </template>
              <Column field="name" header="キャンペーン名" />
              <Column header="ステータス" style="width: 110px">
                <template #body="{ data }">
                  <Tag :value="statusMap[data.status as CampaignStatus]?.label ?? data.status" :severity="statusMap[data.status as CampaignStatus]?.severity ?? 'secondary'" />
                </template>
              </Column>
              <Column header="割引" style="width: 110px">
                <template #body="{ data }"><span class="text-sm font-medium">{{ discountLabel(data.discountType, data.discountValue) }}</span></template>
              </Column>
              <Column header="対象" style="width: 100px">
                <template #body="{ data }"><span class="text-sm">{{ targetMap[data.target as CampaignTarget] ?? data.target }}</span></template>
              </Column>
              <Column header="期間" style="width: 220px">
                <template #body="{ data }"><span class="text-sm">{{ new Date(data.startDate).toLocaleDateString('ja-JP') }} 〜 {{ new Date(data.endDate).toLocaleDateString('ja-JP') }}</span></template>
              </Column>
              <Column header="操作" style="width: 170px">
                <template #body="{ data }">
                  <div class="flex gap-1">
                    <Button label="クーポン" size="small" severity="info" outlined @click="selectForCoupons(data.id)" />
                    <Button icon="pi pi-trash" size="small" severity="danger" text v-tooltip="'削除'" @click="handleDelete(data.id)" />
                  </div>
                </template>
              </Column>
            </DataTable>
          </TabPanel>

          <TabPanel value="coupons">
            <div v-if="!selectedCampaignId" class="py-12 text-center">
              <p class="text-surface-400">キャンペーン一覧から対象を選択してください</p>
            </div>
            <template v-else>
              <div class="mb-3 flex items-center justify-between">
                <span class="text-sm text-surface-500">キャンペーンID: {{ selectedCampaignId }}</span>
                <Button label="クーポン発行" icon="pi pi-plus" @click="couponForm = { code: '', maxRedemptions: '' }; showCouponDialog = true" />
              </div>
              <PageLoading v-if="couponsLoading" />
              <DataTable v-else :value="coupons" data-key="id" striped-rows>
                <template #empty>
                  <div class="py-12 text-center"><p class="text-surface-400">クーポンがありません</p></div>
                </template>
                <Column field="code" header="コード" />
                <Column header="利用状況" style="width: 140px">
                  <template #body="{ data }"><span class="text-sm">{{ data.currentRedemptions }} / {{ data.maxRedemptions ?? '無制限' }}</span></template>
                </Column>
                <Column header="状態" style="width: 90px">
                  <template #body="{ data }"><Tag :value="data.isActive ? '有効' : '無効'" :severity="data.isActive ? 'success' : 'secondary'" /></template>
                </Column>
                <Column header="発行日" style="width: 140px">
                  <template #body="{ data }"><span class="text-sm">{{ new Date(data.createdAt).toLocaleDateString('ja-JP') }}</span></template>
                </Column>
                <Column header="操作" style="width: 110px">
                  <template #body="{ data }"><Button label="利用履歴" size="small" severity="secondary" outlined @click="viewUsage(data.id)" /></template>
                </Column>
              </DataTable>
            </template>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>

    <!-- Campaign Create -->
    <Dialog v-model:visible="showCampaignDialog" header="キャンペーンを作成" :style="{ width: '560px' }" modal :draggable="false">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">キャンペーン名 <span class="text-red-500">*</span></label>
          <InputText v-model="campaignForm.name" class="w-full" placeholder="例: 春の新規登録キャンペーン" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="campaignForm.description" class="w-full" rows="2" placeholder="キャンペーンの詳細" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">割引タイプ</label>
            <Select v-model="campaignForm.discountType" :options="discountTypeOptions" option-label="label" option-value="value" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">割引値</label>
            <InputNumber v-model="campaignForm.discountValue" :min="1" class="w-full" />
          </div>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">対象</label>
          <Select v-model="campaignForm.target" :options="targetOptions" option-label="label" option-value="value" class="w-full" />
        </div>
        <div v-if="campaignForm.target === 'MODULE'">
          <label class="mb-1 block text-sm font-medium">モジュールID</label>
          <InputText v-model="campaignForm.targetModuleId" class="w-full" placeholder="例: CHAT" />
        </div>
        <div v-if="campaignForm.target === 'PACKAGE'">
          <label class="mb-1 block text-sm font-medium">パッケージID</label>
          <InputText v-model="campaignForm.targetPackageId" class="w-full" placeholder="例: PREMIUM" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">開始日 <span class="text-red-500">*</span></label>
            <InputText v-model="campaignForm.startDate" type="date" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">終了日 <span class="text-red-500">*</span></label>
            <InputText v-model="campaignForm.endDate" type="date" class="w-full" />
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showCampaignDialog = false" />
        <Button label="作成する" icon="pi pi-check" :loading="campaignSubmitting" :disabled="!campaignForm.name || !campaignForm.startDate || !campaignForm.endDate" @click="submitCampaign" />
      </template>
    </Dialog>

    <!-- Coupon Create -->
    <Dialog v-model:visible="showCouponDialog" header="クーポンを発行" :style="{ width: '440px' }" modal :draggable="false">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">クーポンコード <span class="text-red-500">*</span></label>
          <InputText v-model="couponForm.code" class="w-full" placeholder="例: SPRING2026" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">利用上限（空欄=無制限）</label>
          <InputText v-model="couponForm.maxRedemptions" type="number" class="w-full" placeholder="例: 100" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showCouponDialog = false" />
        <Button label="発行する" icon="pi pi-check" :loading="couponSubmitting" :disabled="!couponForm.code" @click="submitCoupon" />
      </template>
    </Dialog>

    <!-- Usage -->
    <Dialog v-model:visible="showUsageDialog" header="クーポン利用履歴" :style="{ width: '520px' }" modal :draggable="false">
      <PageLoading v-if="usageLoading" />
      <DataTable v-else :value="usageList" data-key="id" striped-rows>
        <template #empty><div class="py-8 text-center"><p class="text-surface-400">利用履歴がありません</p></div></template>
        <Column field="userDisplayName" header="ユーザー" />
        <Column header="利用日時" style="width: 180px">
          <template #body="{ data }"><span class="text-sm">{{ new Date(data.redeemedAt).toLocaleString('ja-JP') }}</span></template>
        </Column>
      </DataTable>
    </Dialog>
  </div>
</template>
