<script setup lang="ts">
// F09.19.6 チームスコープ 与信枠 増額申請ページ。
// 組織版 (pages/organizations/[slug]/advertiser/credit-limit-requests.vue) を team scope で読み替えたもの。

import type { CreditLimitRequestResponse } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
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
    const res = await advertiserApi.getCreditLimitRequests('TEAM', teamSlug)
    requests.value = res.data
  }
  catch { requests.value = [] }
  finally { loading.value = false }
}

async function create() {
  if (!form.value.requestedLimit || !form.value.reason) return
  creating.value = true
  try {
    await advertiserApi.createCreditLimitRequest('TEAM', teamSlug, form.value)
    success(t('advertising.teams_page.credit_limit_requests.created_toast'))
    showCreate.value = false
    form.value = { requestedLimit: 0, reason: '' }
    await load()
  }
  catch { showError(t('advertising.teams_page.credit_limit_requests.create_failed_toast')) }
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
      <PageHeader :title="t('advertising.teams_page.credit_limit_requests.title')" :back-to="`/teams/${teamSlug}/advertiser`" />
      <Button :label="t('advertising.teams_page.credit_limit_requests.create_button')" icon="pi pi-plus" @click="showCreate = true" />
    </div>

    <div v-if="loading" class="flex justify-center py-10"><LoadingBounce /></div>

    <DataTable v-else :value="requests" striped-rows>
      <Column field="requestedLimit" :header="t('advertising.teams_page.credit_limit_requests.column_requested_limit')">
        <template #body="{ data }">¥{{ data.requestedLimit.toLocaleString() }}</template>
      </Column>
      <Column field="currentLimit" :header="t('advertising.teams_page.credit_limit_requests.column_current_limit')">
        <template #body="{ data }">¥{{ data.currentLimit.toLocaleString() }}</template>
      </Column>
      <Column field="reason" :header="t('advertising.teams_page.credit_limit_requests.column_reason')" />
      <Column field="status" :header="t('advertising.teams_page.credit_limit_requests.column_status')">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="reviewNote" :header="t('advertising.teams_page.credit_limit_requests.column_review_note')" />
      <Column field="createdAt" :header="t('advertising.teams_page.credit_limit_requests.column_created_at')">
        <template #body="{ data }">{{ data.createdAt?.substring(0, 10) }}</template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="showCreate" :header="t('advertising.teams_page.credit_limit_requests.dialog_title')" :style="{ width: '500px' }" modal>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.credit_limit_requests.form_requested_limit_label') }}</label>
        <InputNumber v-model="form.requestedLimit" :min="1" mode="currency" currency="JPY" locale="ja-JP" class="w-full" />
      </div>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.credit_limit_requests.form_reason_label') }}</label>
        <Textarea v-model="form.reason" rows="3" class="w-full" :placeholder="t('advertising.teams_page.credit_limit_requests.form_reason_placeholder')" />
      </div>
      <div class="flex justify-end">
        <Button :label="t('advertising.teams_page.credit_limit_requests.submit_button')" icon="pi pi-check" :loading="creating" @click="create" />
      </div>
    </Dialog>
  </div>
</template>
