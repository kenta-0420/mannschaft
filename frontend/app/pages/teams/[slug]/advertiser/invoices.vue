<script setup lang="ts">
// F09.19.6 チームスコープ 請求書一覧ページ。
// 組織版 (pages/organizations/[slug]/advertiser/invoices.vue) を team scope で読み替えたもの。

import type { InvoiceSummaryResponse, InvoiceDetailResponse, InvoiceStatus } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()

const invoices = ref<InvoiceSummaryResponse[]>([])
const loading = ref(true)
const selectedInvoice = ref<InvoiceDetailResponse | null>(null)
const showDetail = ref(false)
const statusFilter = ref<InvoiceStatus | null>(null)

const statusOptions = computed(() => [
  { label: t('advertising.teams_page.invoices.status_all'), value: null },
  { label: t('advertising.teams_page.invoices.status_draft'), value: 'DRAFT' },
  { label: t('advertising.teams_page.invoices.status_issued'), value: 'ISSUED' },
  { label: t('advertising.teams_page.invoices.status_paid'), value: 'PAID' },
  { label: t('advertising.teams_page.invoices.status_overdue'), value: 'OVERDUE' },
])

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

async function viewDetail(invoice: InvoiceSummaryResponse) {
  try {
    const res = await advertiserApi.getInvoiceDetail('TEAM', teamSlug, invoice.id)
    selectedInvoice.value = res.data
    showDetail.value = true
  }
  catch { /* handled by global */ }
}

async function downloadPdf(invoiceId: number) {
  try {
    const blob = await advertiserApi.downloadInvoicePdf('TEAM', teamSlug, invoiceId)
    const url = URL.createObjectURL(blob as Blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `invoice_${invoiceId}.pdf`
    a.click()
    URL.revokeObjectURL(url)
  }
  catch { /* handled by global */ }
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
      <PageHeader :title="t('advertising.teams_page.invoices.title')" :back-to="`/teams/${teamSlug}/advertiser`" />
      <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" :placeholder="t('advertising.teams_page.invoices.status_filter_placeholder')" class="w-40" />
    </div>

    <div v-if="loading" class="flex justify-center py-10"><LoadingBounce /></div>

    <DataTable v-else :value="invoices" striped-rows @row-click="(e: { data: InvoiceSummaryResponse }) => viewDetail(e.data)">
      <Column field="invoiceNumber" :header="t('advertising.teams_page.invoices.column_invoice_number')" />
      <Column field="invoiceMonth" :header="t('advertising.teams_page.invoices.column_invoice_month')" />
      <Column field="totalWithTax" :header="t('advertising.teams_page.invoices.column_total_with_tax')">
        <template #body="{ data }">¥{{ data.totalWithTax.toLocaleString() }}</template>
      </Column>
      <Column field="status" :header="t('advertising.teams_page.invoices.column_status')">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="dueDate" :header="t('advertising.teams_page.invoices.column_due_date')" />
      <Column header="">
        <template #body="{ data }">
          <Button icon="pi pi-download" text size="small" @click.stop="downloadPdf(data.id)" />
        </template>
      </Column>
    </DataTable>

    <!-- 詳細ダイアログ -->
    <Dialog v-model:visible="showDetail" :header="t('advertising.teams_page.invoices.detail_title')" :style="{ width: '700px' }" modal>
      <div v-if="selectedInvoice">
        <div class="mb-4 grid grid-cols-2 gap-2 text-sm">
          <div><span class="text-surface-500">{{ t('advertising.teams_page.invoices.detail_invoice_number') }}</span> {{ selectedInvoice.invoiceNumber }}</div>
          <div><span class="text-surface-500">{{ t('advertising.teams_page.invoices.detail_invoice_month') }}</span> {{ selectedInvoice.invoiceMonth }}</div>
          <div><span class="text-surface-500">{{ t('advertising.teams_page.invoices.detail_status') }}</span> <Tag :value="selectedInvoice.status" :severity="statusSeverity(selectedInvoice.status)" /></div>
          <div><span class="text-surface-500">{{ t('advertising.teams_page.invoices.detail_due_date') }}</span> {{ selectedInvoice.dueDate || '-' }}</div>
        </div>
        <DataTable :value="selectedInvoice.items" striped-rows class="mb-4">
          <Column field="campaignName" :header="t('advertising.teams_page.invoices.detail_column_campaign')" />
          <Column field="pricingModel" :header="t('advertising.teams_page.invoices.detail_column_pricing_model')" />
          <Column field="impressions" :header="t('advertising.teams_page.invoices.detail_column_impressions')">
            <template #body="{ data }">{{ data.impressions.toLocaleString() }}</template>
          </Column>
          <Column field="clicks" :header="t('advertising.teams_page.invoices.detail_column_clicks')">
            <template #body="{ data }">{{ data.clicks.toLocaleString() }}</template>
          </Column>
          <Column field="subtotal" :header="t('advertising.teams_page.invoices.detail_column_subtotal')">
            <template #body="{ data }">¥{{ data.subtotal.toLocaleString() }}</template>
          </Column>
        </DataTable>
        <div class="text-right">
          <p>{{ t('advertising.teams_page.invoices.subtotal_label') }} ¥{{ selectedInvoice.totalAmount.toLocaleString() }}</p>
          <p>{{ t('advertising.teams_page.invoices.tax_label', { rate: selectedInvoice.taxRate }) }} ¥{{ selectedInvoice.taxAmount.toLocaleString() }}</p>
          <p class="text-xl font-bold">{{ t('advertising.teams_page.invoices.total_label') }} ¥{{ selectedInvoice.totalWithTax.toLocaleString() }}</p>
        </div>
      </div>
    </Dialog>
  </div>
</template>
