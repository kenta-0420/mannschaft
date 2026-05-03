<script setup lang="ts">
import type { ReceiptResponse } from '~/types/receipt'

definePageMeta({ middleware: 'auth' })

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
    showError('領収書一覧の取得に失敗しました')
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
    success('承認しました')
    load()
  } catch {
    showError('承認に失敗しました')
  }
}

async function handleVoid(id: number) {
  try {
    await voidReceipt(id)
    success('無効化しました')
    load()
  } catch {
    showError('無効化に失敗しました')
  }
}

async function handleReissue(id: number) {
  try {
    await reissueReceipt(id)
    success('再発行しました')
    load()
  } catch {
    showError('再発行に失敗しました')
  }
}

async function handleDownloadPdf(id: number) {
  try {
    await downloadPdf(id)
    success('PDFをダウンロードしました')
  } catch {
    showError('PDFダウンロードに失敗しました')
  }
}

async function handleSendEmail(id: number) {
  try {
    await sendReceiptEmail(id)
    success('メールを送信しました')
  } catch {
    showError('メール送信に失敗しました')
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
    success('領収書を発行しました')
    showIssueDialog.value = false
    load()
  } catch {
    showError('領収書の発行に失敗しました')
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
    case 'DRAFT': return '下書き'
    case 'ISSUED': return '発行済'
    default: return status
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="領収書管理" />
      <Button label="新規発行" icon="pi pi-plus" @click="openIssueDialog" />
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
        <DashboardEmptyState icon="pi pi-file" message="領収書がありません" />
      </template>

      <Column header="発行日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">
            {{ data.issuedAt ? new Date(data.issuedAt).toLocaleDateString('ja-JP') : '-' }}
          </span>
        </template>
      </Column>

      <Column field="receiptNumber" header="領収書番号" style="width: 160px" />

      <Column field="recipientName" header="宛名" />

      <Column header="金額" style="width: 120px">
        <template #body="{ data }">
          <span class="font-medium">{{ data.totalAmount.toLocaleString('ja-JP') }}円</span>
        </template>
      </Column>

      <Column header="ステータス" style="width: 100px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>

      <Column header="操作" style="width: 340px">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              v-if="data.status === 'DRAFT'"
              label="承認"
              size="small"
              severity="success"
              @click="handleApprove(data.id)"
            />
            <Button
              label="無効化"
              size="small"
              severity="danger"
              outlined
              @click="handleVoid(data.id)"
            />
            <Button
              label="再発行"
              size="small"
              severity="info"
              outlined
              @click="handleReissue(data.id)"
            />
            <Button
              v-tooltip="'PDF'"
              icon="pi pi-file-pdf"
              size="small"
              severity="secondary"
              text
              @click="handleDownloadPdf(data.id)"
            />
            <Button
              v-tooltip="'メール送信'"
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
      header="領収書を発行"
      :style="{ width: '480px' }"
      modal
      :draggable="false"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">宛名 <span class="text-red-500">*</span></label>
          <InputText
            v-model="issueForm.recipientName"
            class="w-full"
            placeholder="例: 山田 太郎"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">金額（円） <span class="text-red-500">*</span></label>
          <InputText
            v-model="issueForm.totalAmount"
            type="number"
            class="w-full"
            placeholder="例: 10000"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">品目・摘要</label>
          <InputText
            v-model="issueForm.description"
            class="w-full"
            placeholder="例: 月会費"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">メモ</label>
          <Textarea
            v-model="issueForm.notes"
            class="w-full"
            rows="3"
            placeholder="備考など"
          />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showIssueDialog = false" />
        <Button
          label="発行する"
          icon="pi pi-check"
          :loading="issueSubmitting"
          :disabled="!issueForm.recipientName || !Number(issueForm.totalAmount)"
          @click="submitIssue"
        />
      </template>
    </Dialog>
  </div>
</template>
