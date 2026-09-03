<script setup lang="ts">
import type { ReceiptResponse } from '~/types/receipt'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const {
  getReceipts,
  issueReceipt,
  approveReceipt,
  voidReceipt,
  reissueReceipt,
  downloadPdf,
  sendReceiptEmail,
} = useReceiptApi()
const { success, error: showError } = useNotification()
const { formatDate } = useDatetime()

const receipts = ref<ReceiptResponse[]>([])
const loading = ref(false)
const totalRecords = ref(0)
const page = ref(0)
const rows = ref(20)

// 新規発行ダイアログ
const showIssueDialog = ref(false)
const issueForm = ref({
  recipientName: '',
  totalAmount: '',
  description: '',
  notes: '',
})
const issueSubmitting = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getReceipts({ page: page.value + 1, per_page: rows.value })
    receipts.value = res.data
    totalRecords.value = (res.meta?.total as number) ?? res.data.length
  } catch {
    showError(t('receipt.list.toast.loadFailed'))
  } finally {
    loading.value = false
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  load()
}

async function handleApprove(id: number) {
  try {
    await approveReceipt(id)
    success(t('receipt.list.toast.approved'))
    load()
  } catch {
    showError(t('receipt.list.toast.approveFailed'))
  }
}

async function handleVoid(id: number) {
  try {
    await voidReceipt(id)
    success(t('receipt.list.toast.voided'))
    load()
  } catch {
    showError(t('receipt.list.toast.voidFailed'))
  }
}

async function handleReissue(id: number) {
  try {
    await reissueReceipt(id)
    success(t('receipt.list.toast.reissued'))
    load()
  } catch {
    showError(t('receipt.list.toast.reissueFailed'))
  }
}

async function handleDownloadPdf(id: number) {
  try {
    await downloadPdf(id)
    success(t('receipt.list.toast.pdfDownloaded'))
  } catch {
    showError(t('receipt.list.toast.pdfDownloadFailed'))
  }
}

async function handleSendEmail(id: number) {
  try {
    await sendReceiptEmail(id)
    success(t('receipt.list.toast.emailSent'))
  } catch {
    showError(t('receipt.list.toast.emailSendFailed'))
  }
}

function openIssueDialog() {
  issueForm.value = { recipientName: '', totalAmount: '', description: '', notes: '' }
  showIssueDialog.value = true
}

async function submitIssue() {
  const amount = Number(issueForm.value.totalAmount)
  if (!issueForm.value.recipientName || !amount) return
  issueSubmitting.value = true
  try {
    await issueReceipt({
      recipientName: issueForm.value.recipientName,
      totalAmount: amount,
      description: issueForm.value.description,
      notes: issueForm.value.notes,
    })
    success(t('receipt.list.toast.issued'))
    showIssueDialog.value = false
    load()
  } catch {
    showError(t('receipt.list.toast.issueFailed'))
  } finally {
    issueSubmitting.value = false
  }
}

function statusSeverity(status: string): string {
  switch (status) {
    case 'DRAFT': return 'warning'
    case 'ISSUED': return 'success'
    default: return 'secondary'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'DRAFT': return t('receipt.list.status.DRAFT')
    case 'ISSUED': return t('receipt.list.status.ISSUED')
    default: return status
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('receipt.list.title')" />
      <div class="flex gap-2">
        <NuxtLink to="/admin/receipt-settings">
          <Button :label="t('receipt.list.settingsButton')" icon="pi pi-cog" severity="secondary" outlined />
        </NuxtLink>
        <Button :label="t('receipt.list.issueButton')" icon="pi pi-plus" @click="openIssueDialog" />
      </div>
    </div>

    <DataTable
      :value="receipts"
      :loading="loading"
      :lazy="true"
      :paginator="true"
      :rows="rows"
      :total-records="totalRecords"
      :first="page * rows"
      data-key="id"
      striped-rows
      @page="onPage"
    >
      <template #empty>
        <DashboardEmptyState icon="pi pi-file" :message="t('receipt.list.empty')" />
      </template>

      <Column :header="t('receipt.list.column.issuedAt')" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">
            {{ data.issuedAt ? formatDate(data.issuedAt) : '-' }}
          </span>
        </template>
      </Column>

      <Column field="receiptNumber" :header="t('receipt.list.column.receiptNumber')" style="width: 160px" />

      <Column field="recipientName" :header="t('receipt.list.column.recipientName')" />

      <Column :header="t('receipt.list.column.amount')" style="width: 120px">
        <template #body="{ data }">
          <span class="font-medium">{{ data.totalAmount.toLocaleString('ja-JP') }}円</span>
        </template>
      </Column>

      <Column :header="t('receipt.list.column.status')" style="width: 100px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>

      <Column :header="t('receipt.list.column.action')" style="width: 340px">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              v-if="data.status === 'DRAFT'"
              :label="t('receipt.list.action.approve')"
              size="small"
              severity="success"
              @click="handleApprove(data.id)"
            />
            <Button
              :label="t('receipt.list.action.void')"
              size="small"
              severity="danger"
              outlined
              @click="handleVoid(data.id)"
            />
            <Button
              :label="t('receipt.list.action.reissue')"
              size="small"
              severity="info"
              outlined
              @click="handleReissue(data.id)"
            />
            <Button
              v-tooltip="t('receipt.list.action.pdf')"
              icon="pi pi-file-pdf"
              size="small"
              severity="secondary"
              text
              @click="handleDownloadPdf(data.id)"
            />
            <Button
              v-tooltip="t('receipt.list.action.sendEmail')"
              icon="pi pi-envelope"
              size="small"
              severity="secondary"
              text
              @click="handleSendEmail(data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 新規発行ダイアログ -->
    <Dialog
      v-model:visible="showIssueDialog"
      :header="t('receipt.list.dialog.issueTitle')"
      :style="{ width: '480px' }"
      modal
      :draggable="false"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('receipt.list.dialog.recipientName') }} <span class="text-red-500">*</span></label>
          <InputText
            v-model="issueForm.recipientName"
            class="w-full"
            :placeholder="t('receipt.list.dialog.recipientNamePlaceholder')"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('receipt.list.dialog.amount') }} <span class="text-red-500">*</span></label>
          <InputText
            v-model="issueForm.totalAmount"
            type="number"
            class="w-full"
            :placeholder="t('receipt.list.dialog.amountPlaceholder')"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('receipt.list.dialog.description') }}</label>
          <InputText
            v-model="issueForm.description"
            class="w-full"
            :placeholder="t('receipt.list.dialog.descriptionPlaceholder')"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('receipt.list.dialog.notes') }}</label>
          <Textarea
            v-model="issueForm.notes"
            class="w-full"
            rows="3"
            :placeholder="t('receipt.list.dialog.notesPlaceholder')"
          />
        </div>
      </div>
      <template #footer>
        <Button :label="t('receipt.list.dialog.cancel')" severity="secondary" text @click="showIssueDialog = false" />
        <Button
          :label="t('receipt.list.dialog.submit')"
          icon="pi pi-check"
          :loading="issueSubmitting"
          :disabled="!issueForm.recipientName || !Number(issueForm.totalAmount)"
          @click="submitIssue"
        />
      </template>
    </Dialog>
  </div>
</template>
