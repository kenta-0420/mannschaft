<script setup lang="ts">
// F09.19.6 チームスコープ 請求書一覧。
// 組織版 (pages/organizations/[slug]/advertiser/invoices.vue) を金型に、
// team scope で実装済みの一覧 API（GET /api/v1/teams/{teamId}/advertiser/invoices。
// F09.19.5 AC-5.2 / TeamAdvertiserDashboardController）のみを利用する。
//
// 請求書詳細ダイアログ・PDF ダウンロードは、team scope 向けの
// GET /api/v1/teams/{teamId}/advertiser/invoices/{id}(/pdf) が
// バックエンド未実装（AdvertiserDashboardController の該当 API は
// ScopeType.ORGANIZATION 固定のまま）のため、本ページでは提供しない。

import type { InvoiceSummaryResponse, InvoiceStatus } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()

const invoices = ref<InvoiceSummaryResponse[]>([])
const loading = ref(true)
const statusFilter = ref<InvoiceStatus | null>(null)

const statusOptions = [
  { label: 'すべて', value: null },
  { label: '下書き', value: 'DRAFT' },
  { label: '発行済み', value: 'ISSUED' },
  { label: '支払済み', value: 'PAID' },
  { label: '期限超過', value: 'OVERDUE' },
]

async function loadInvoices() {
  loading.value = true
  try {
    const params: Record<string, string> = {}
    if (statusFilter.value) params.status = statusFilter.value
    const res = await advertiserApi.getInvoices('TEAM', teamSlug, params)
    invoices.value = res.data
  }
  catch { invoices.value = [] }
  finally { loading.value = false }
}

function statusSeverity(status: InvoiceStatus) {
  const map: Record<InvoiceStatus, string> = { DRAFT: 'secondary', ISSUED: 'info', PAID: 'success', OVERDUE: 'danger' }
  return map[status]
}

watch(statusFilter, () => loadInvoices())
onMounted(loadInvoices)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('advertising.teams_page.invoices.title')" />
      <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" placeholder="ステータス" class="w-40" />
    </div>

    <Message severity="info" :closable="false" class="mb-4">
      {{ t('advertising.teams_page.invoices.write_ops_notice') }}
    </Message>

    <div v-if="loading" class="flex justify-center py-10"><LoadingBounce /></div>

    <DataTable v-else :value="invoices" striped-rows>
      <Column field="invoiceNumber" header="請求書番号" />
      <Column field="invoiceMonth" header="対象月" />
      <Column field="totalWithTax" header="税込合計">
        <template #body="{ data }">¥{{ data.totalWithTax.toLocaleString() }}</template>
      </Column>
      <Column field="status" header="ステータス">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="dueDate" header="支払期限" />
    </DataTable>
  </div>
</template>
