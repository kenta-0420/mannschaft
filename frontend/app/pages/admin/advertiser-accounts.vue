<script setup lang="ts">
import type { AdvertiserAccountDetailResponse, AdvertiserAccountStatus } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const advertiserApi = useAdvertiserApi()
const { success, error: showError } = useNotification()

const accounts = ref<AdvertiserAccountDetailResponse[]>([])
const loading = ref(true)
const statusFilter = ref<AdvertiserAccountStatus | null>(null)
const showCreditDialog = ref(false)
const selectedAccountId = ref<number>(0)
const newCreditLimit = ref<number>(0)
const showSuspendDialog = ref(false)
const suspendReason = ref('')

const statusOptions = [
  { label: 'すべて', value: null },
  { label: '審査待ち', value: 'PENDING' },
  { label: '有効', value: 'ACTIVE' },
  { label: '停止', value: 'SUSPENDED' },
]

async function load() {
  loading.value = true
  try {
    const params: any = {}
    if (statusFilter.value) params.status = statusFilter.value
    const res = await advertiserApi.adminGetAdvertiserAccounts(params)
    accounts.value = res.data
  }
  catch { accounts.value = [] }
  finally { loading.value = false }
}

async function approve(id: number) {
  try {
    await advertiserApi.adminApproveAccount(id)
    success('承認しました')
    await load()
  }
  catch { showError('承認に失敗しました') }
}

function openSuspend(id: number) {
  selectedAccountId.value = id
  suspendReason.value = ''
  showSuspendDialog.value = true
}

async function suspend() {
  try {
    await advertiserApi.adminSuspendAccount(selectedAccountId.value, { reason: suspendReason.value || undefined })
    success('停止しました')
    showSuspendDialog.value = false
    await load()
  }
  catch { showError('停止に失敗しました') }
}

function openCreditLimit(id: number, currentLimit: number) {
  selectedAccountId.value = id
  newCreditLimit.value = currentLimit
  showCreditDialog.value = true
}

async function updateCreditLimit() {
  try {
    await advertiserApi.adminUpdateCreditLimit(selectedAccountId.value, { creditLimit: newCreditLimit.value })
    success('与信枠を更新しました')
    showCreditDialog.value = false
    await load()
  }
  catch { showError('更新に失敗しました') }
}

function statusSeverity(status: AdvertiserAccountStatus) {
  return status === 'ACTIVE' ? 'success' : status === 'PENDING' ? 'warn' : 'danger'
}

watch(statusFilter, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">広告主アカウント管理</h1>
      <Select v-model="statusFilter" :options="statusOptions" optionLabel="label" optionValue="value" class="w-40" />
    </div>

    <ProgressSpinner v-if="loading" class="flex justify-center py-10" />

    <DataTable v-else :value="accounts" stripedRows>
      <Column field="companyName" header="会社名" />
      <Column field="contactEmail" header="メール" />
      <Column field="billingMethod" header="決済" />
      <Column field="creditLimit" header="与信枠">
        <template #body="{ data }">¥{{ data.creditLimit.toLocaleString() }}</template>
      </Column>
      <Column field="status" header="ステータス">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="createdAt" header="登録日">
        <template #body="{ data }">{{ data.createdAt?.substring(0, 10) }}</template>
      </Column>
      <Column header="操作">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button v-if="data.status === 'PENDING'" label="承認" size="small" severity="success" @click="approve(data.id)" />
            <Button v-if="data.status === 'ACTIVE'" label="停止" size="small" severity="danger" @click="openSuspend(data.id)" />
            <Button icon="pi pi-wallet" size="small" severity="secondary" text @click="openCreditLimit(data.id, data.creditLimit)" />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 停止ダイアログ -->
    <Dialog v-model:visible="showSuspendDialog" header="アカウント停止" :style="{ width: '400px' }" modal>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">停止理由（任意）</label>
        <Textarea v-model="suspendReason" rows="3" class="w-full" />
      </div>
      <div class="flex justify-end gap-2">
        <Button label="キャンセル" severity="secondary" @click="showSuspendDialog = false" />
        <Button label="停止する" severity="danger" @click="suspend" />
      </div>
    </Dialog>

    <!-- 与信枠ダイアログ -->
    <Dialog v-model:visible="showCreditDialog" header="与信枠変更" :style="{ width: '400px' }" modal>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">新しい与信枠（円）</label>
        <InputNumber v-model="newCreditLimit" :min="1" mode="currency" currency="JPY" locale="ja-JP" class="w-full" />
      </div>
      <div class="flex justify-end gap-2">
        <Button label="キャンセル" severity="secondary" @click="showCreditDialog = false" />
        <Button label="更新" @click="updateCreditLimit" />
      </div>
    </Dialog>
  </div>
</template>
