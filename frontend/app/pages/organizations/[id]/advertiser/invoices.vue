<script setup lang="ts">
import type { InvoiceSummaryResponse, InvoiceDetailResponse, InvoiceStatus } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const advertiserApi = useAdvertiserApi()

const invoices = ref<InvoiceSummaryResponse[]>([])
const loading = ref(true)
const selectedInvoice = ref<InvoiceDetailResponse | null>(null)
const showDetail = ref(false)
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
    const res = await advertiserApi.getInvoices(orgId, params)
    invoices.value = res.data
  }
  catch { invoices.value = [] }
  finally { loading.value = false }
}

async function viewDetail(invoice: InvoiceSummaryResponse) {
  try {
    const res = await advertiserApi.getInvoiceDetail(invoice.id, orgId)
    selectedInvoice.value = res.data
    showDetail.value = true
  }
  catch { /* handled by global */ }
}

async function downloadPdf(invoiceId: number) {
  try {
    const blob = await advertiserApi.downloadInvoicePdf(invoiceId, orgId)
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
      <h1 class="text-2xl font-bold">請求書</h1>
      <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" placeholder="ステータス" class="w-40" />
    </div>

    <ProgressSpinner v-if="loading" class="flex justify-center py-10" />

    <DataTable v-else :value="invoices" striped-rows @row-click="(e: any) => viewDetail(e.data)">
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
      <Column header="">
        <template #body="{ data }">
          <Button icon="pi pi-download" text size="small" @click.stop="downloadPdf(data.id)" />
        </template>
      </Column>
    </DataTable>

    <!-- 詳細ダイアログ -->
    <Dialog v-model:visible="showDetail" header="請求書詳細" :style="{ width: '700px' }" modal>
      <div v-if="selectedInvoice">
        <div class="mb-4 grid grid-cols-2 gap-2 text-sm">
          <div><span class="text-surface-500">請求書番号:</span> {{ selectedInvoice.invoiceNumber }}</div>
          <div><span class="text-surface-500">対象月:</span> {{ selectedInvoice.invoiceMonth }}</div>
          <div><span class="text-surface-500">ステータス:</span> <Tag :value="selectedInvoice.status" :severity="statusSeverity(selectedInvoice.status)" /></div>
          <div><span class="text-surface-500">支払期限:</span> {{ selectedInvoice.dueDate || '-' }}</div>
        </div>
        <DataTable :value="selectedInvoice.items" striped-rows class="mb-4">
          <Column field="campaignName" header="キャンペーン" />
          <Column field="pricingModel" header="課金" />
          <Column field="impressions" header="imp">
            <template #body="{ data }">{{ data.impressions.toLocaleString() }}</template>
          </Column>
          <Column field="clicks" header="click">
            <template #body="{ data }">{{ data.clicks.toLocaleString() }}</template>
          </Column>
          <Column field="subtotal" header="小計">
            <template #body="{ data }">¥{{ data.subtotal.toLocaleString() }}</template>
          </Column>
        </DataTable>
        <div class="text-right">
          <p>小計: ¥{{ selectedInvoice.totalAmount.toLocaleString() }}</p>
          <p>消費税（{{ selectedInvoice.taxRate }}%）: ¥{{ selectedInvoice.taxAmount.toLocaleString() }}</p>
          <p class="text-xl font-bold">合計: ¥{{ selectedInvoice.totalWithTax.toLocaleString() }}</p>
        </div>
      </div>
    </Dialog>
  </div>
</template>
