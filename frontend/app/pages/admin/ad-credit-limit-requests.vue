<script setup lang="ts">
import type { CreditLimitRequestDetailResponse, CreditLimitRequestStatus } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const advertiserApi = useAdvertiserApi()
const { success, error: showError } = useNotification()

const requests = ref<CreditLimitRequestDetailResponse[]>([])
const loading = ref(true)
const statusFilter = ref<CreditLimitRequestStatus | null>(null)
const showRejectDialog = ref(false)
const selectedRequestId = ref<number>(0)
const rejectNote = ref('')

const statusOptions = [
  { label: 'すべて', value: null },
  { label: '審査待ち', value: 'PENDING' },
  { label: '承認済み', value: 'APPROVED' },
  { label: '却下', value: 'REJECTED' },
]

async function load() {
  loading.value = true
  try {
    const params: any = {}
    if (statusFilter.value) params.status = statusFilter.value
    const res = await advertiserApi.adminGetCreditLimitRequests(params)
    requests.value = res.data
  }
  catch { requests.value = [] }
  finally { loading.value = false }
}

async function approve(id: number) {
  try {
    await advertiserApi.adminApproveCreditLimitRequest(id)
    success('承認しました。与信枠が更新されました。')
    await load()
  }
  catch { showError('承認に失敗しました') }
}

function openReject(id: number) {
  selectedRequestId.value = id
  rejectNote.value = ''
  showRejectDialog.value = true
}

async function reject() {
  try {
    await advertiserApi.adminRejectCreditLimitRequest(selectedRequestId.value, {
      reviewNote: rejectNote.value || undefined,
    })
    success('却下しました')
    showRejectDialog.value = false
    await load()
  }
  catch { showError('却下に失敗しました') }
}

function statusSeverity(status: CreditLimitRequestStatus) {
  return status === 'APPROVED' ? 'success' : status === 'REJECTED' ? 'danger' : 'warn'
}

watch(statusFilter, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">与信枠 増額申請管理</h1>
      <Select v-model="statusFilter" :options="statusOptions" optionLabel="label" optionValue="value" class="w-40" />
    </div>

    <ProgressSpinner v-if="loading" class="flex justify-center py-10" />

    <DataTable v-else :value="requests" stripedRows>
      <Column field="companyName" header="広告主" />
      <Column field="currentLimit" header="現在の限度額">
        <template #body="{ data }">¥{{ data.currentLimit.toLocaleString() }}</template>
      </Column>
      <Column field="requestedLimit" header="希望額">
        <template #body="{ data }">¥{{ data.requestedLimit.toLocaleString() }}</template>
      </Column>
      <Column field="reason" header="理由" style="max-width: 200px">
        <template #body="{ data }">
          <span class="line-clamp-2">{{ data.reason }}</span>
        </template>
      </Column>
      <Column field="status" header="ステータス">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="createdAt" header="申請日">
        <template #body="{ data }">{{ data.createdAt?.substring(0, 10) }}</template>
      </Column>
      <Column header="操作">
        <template #body="{ data }">
          <div v-if="data.status === 'PENDING'" class="flex gap-1">
            <Button label="承認" size="small" severity="success" @click="approve(data.id)" />
            <Button label="却下" size="small" severity="danger" @click="openReject(data.id)" />
          </div>
          <span v-else-if="data.reviewNote" class="text-xs text-surface-400">{{ data.reviewNote }}</span>
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="showRejectDialog" header="申請却下" :style="{ width: '400px' }" modal>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">却下理由（任意）</label>
        <Textarea v-model="rejectNote" rows="3" class="w-full" placeholder="却下理由を記入してください" />
      </div>
      <div class="flex justify-end gap-2">
        <Button label="キャンセル" severity="secondary" @click="showRejectDialog = false" />
        <Button label="却下する" severity="danger" @click="reject" />
      </div>
    </Dialog>
  </div>
</template>
