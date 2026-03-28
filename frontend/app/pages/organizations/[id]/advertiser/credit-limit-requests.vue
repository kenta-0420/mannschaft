<script setup lang="ts">
import type { CreditLimitRequestResponse } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const advertiserApi = useAdvertiserApi()
const { success, error: showError } = useNotification()

const requests = ref<CreditLimitRequestResponse[]>([])
const loading = ref(true)
const showCreate = ref(false)
const creating = ref(false)
const form = ref({ requestedLimit: 0, reason: '' })

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.getCreditLimitRequests(orgId)
    requests.value = res.data
  }
  catch { requests.value = [] }
  finally { loading.value = false }
}

async function create() {
  if (!form.value.requestedLimit || !form.value.reason) return
  creating.value = true
  try {
    await advertiserApi.createCreditLimitRequest(orgId, form.value)
    success('増額申請を送信しました')
    showCreate.value = false
    form.value = { requestedLimit: 0, reason: '' }
    await load()
  }
  catch { showError('申請に失敗しました') }
  finally { creating.value = false }
}

function statusSeverity(status: string) {
  return status === 'APPROVED' ? 'success' : status === 'REJECTED' ? 'danger' : 'warn'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">与信枠 増額申請</h1>
      <Button label="新規申請" icon="pi pi-plus" @click="showCreate = true" />
    </div>

    <ProgressSpinner v-if="loading" class="flex justify-center py-10" />

    <DataTable v-else :value="requests" stripedRows>
      <Column field="requestedLimit" header="希望額">
        <template #body="{ data }">¥{{ data.requestedLimit.toLocaleString() }}</template>
      </Column>
      <Column field="currentLimit" header="申請時の限度額">
        <template #body="{ data }">¥{{ data.currentLimit.toLocaleString() }}</template>
      </Column>
      <Column field="reason" header="理由" />
      <Column field="status" header="ステータス">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="reviewNote" header="審査メモ" />
      <Column field="createdAt" header="申請日">
        <template #body="{ data }">{{ data.createdAt?.substring(0, 10) }}</template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="showCreate" header="与信枠 増額申請" :style="{ width: '500px' }" modal>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">希望額（円）</label>
        <InputNumber v-model="form.requestedLimit" :min="1" mode="currency" currency="JPY" locale="ja-JP" class="w-full" />
      </div>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">申請理由</label>
        <Textarea v-model="form.reason" rows="3" class="w-full" placeholder="増額が必要な理由を記入してください" />
      </div>
      <div class="flex justify-end">
        <Button label="申請" icon="pi pi-check" :loading="creating" @click="create" />
      </div>
    </Dialog>
  </div>
</template>
