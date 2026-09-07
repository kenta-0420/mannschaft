<script setup lang="ts">
/**
 * F08.4 領収書一覧（運営・管理者向け）。
 *
 * BE `ReceiptAdminController` は admin 系エンドポイントで scopeType/scopeId を必須要求するため、
 * 現在スコープが確定するまで API を叩かない（空の scopeId を Long へ送ると 400 になる。
 * 発行者設定画面 `receipt-settings.vue` と同じ作法）。
 */
import type { ReceiptResponse } from '~/types/receipt'
import type { ReceiptScopeType } from '~/composables/useReceiptApi'

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
const { success } = useNotification()
const { handleApiError } = useErrorHandler()
const { formatDate } = useDatetime()

const scopeStore = useScopeStore()
const scopeId = computed(() => scopeStore.current.id ?? '')
const scopeType = computed((): ReceiptScopeType =>
  scopeStore.current.type === 'organization' ? 'ORGANIZATION' : 'TEAM',
)
// 領収書はチーム／組織スコープのみが対象（F08.4 §2）。個人スコープでは案内を出して終える。
const isPersonalScope = computed(() => scopeStore.current.type === 'personal')
const scopeReady = computed(() => !isPersonalScope.value && !!scopeId.value)

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

// 無効化ダイアログ（BE `VoidReceiptRequest.reason` は @NotBlank なので理由を必ず取る）
const showVoidDialog = ref(false)
const voidTargetId = ref<number | null>(null)
const voidReason = ref('')
const voidReasonError = ref<string | null>(null)
const voidSubmitting = ref(false)

async function load() {
  if (!scopeReady.value) return
  loading.value = true
  try {
    // BE は 0 起点の `page` と `size` を受ける（1 起点の page / per_page ではない）。
    const res = await getReceipts(scopeType.value, scopeId.value, {
      page: page.value,
      size: rows.value,
    })
    receipts.value = res.data
    totalRecords.value = res.meta?.total ?? res.data.length
  } catch (err) {
    // 握りつぶさない: 原因はコンソールへ、利用者にはサーバーが返した理由・エラーコードを見せる。
    console.error('[receipts] 領収書一覧の取得に失敗しました', err)
    handleApiError(err, 'receipts.load')
  } finally {
    loading.value = false
  }
}

// スコープが確定してから初回発火する（空の scopeId を Long へ送ると 400 になるため）。
watch([scopeId, isPersonalScope], () => {
  page.value = 0
  load()
}, { immediate: true })

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  load()
}

async function handleApprove(id: number) {
  try {
    await approveReceipt(scopeType.value, scopeId.value, id)
    success(t('receipt.list.toast.approved'))
    load()
  } catch (err) {
    console.error('[receipts] 承認に失敗しました', err)
    handleApiError(err, 'receipts.approve')
  }
}

function openVoidDialog(id: number) {
  voidTargetId.value = id
  voidReason.value = ''
  voidReasonError.value = null
  showVoidDialog.value = true
}

async function submitVoid() {
  const id = voidTargetId.value
  const reason = voidReason.value.trim()
  if (id === null) return
  if (!reason) {
    voidReasonError.value = t('receipt.list.validation.voidReasonRequired')
    return
  }
  voidSubmitting.value = true
  try {
    await voidReceipt(scopeType.value, scopeId.value, id, { reason })
    success(t('receipt.list.toast.voided'))
    showVoidDialog.value = false
    load()
  } catch (err) {
    console.error('[receipts] 無効化に失敗しました', err)
    handleApiError(err, 'receipts.void')
  } finally {
    voidSubmitting.value = false
  }
}

async function handleReissue(id: number) {
  try {
    await reissueReceipt(scopeType.value, scopeId.value, id)
    success(t('receipt.list.toast.reissued'))
    load()
  } catch (err) {
    console.error('[receipts] 再発行に失敗しました', err)
    handleApiError(err, 'receipts.reissue')
  }
}

async function handleDownloadPdf(id: number) {
  try {
    await downloadPdf(scopeType.value, scopeId.value, id)
    success(t('receipt.list.toast.pdfDownloaded'))
  } catch (err) {
    console.error('[receipts] PDF 取得に失敗しました', err)
    handleApiError(err, 'receipts.pdf')
  }
}

async function handleSendEmail(id: number) {
  try {
    await sendReceiptEmail(scopeType.value, scopeId.value, id)
    success(t('receipt.list.toast.emailSent'))
  } catch (err) {
    console.error('[receipts] メール送信に失敗しました', err)
    handleApiError(err, 'receipts.sendEmail')
  }
}

function openIssueDialog() {
  issueForm.value = { recipientName: '', totalAmount: '', description: '', notes: '' }
  showIssueDialog.value = true
}

async function submitIssue() {
  const amount = Number(issueForm.value.totalAmount)
  if (!issueForm.value.recipientName || !amount || !scopeReady.value) return
  issueSubmitting.value = true
  try {
    await issueReceipt(scopeType.value, scopeId.value, {
      recipientName: issueForm.value.recipientName,
      totalAmount: amount,
      description: issueForm.value.description,
      notes: issueForm.value.notes,
    })
    success(t('receipt.list.toast.issued'))
    showIssueDialog.value = false
    load()
  } catch (err) {
    console.error('[receipts] 発行に失敗しました', err)
    handleApiError(err, 'receipts.issue')
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
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('receipt.list.title')" />
      <div class="flex gap-2">
        <NuxtLink to="/admin/receipt-settings">
          <Button :label="t('receipt.list.settingsButton')" icon="pi pi-cog" severity="secondary" outlined />
        </NuxtLink>
        <Button
          :label="t('receipt.list.issueButton')"
          icon="pi pi-plus"
          :disabled="!scopeReady"
          @click="openIssueDialog"
        />
      </div>
    </div>

    <div
      v-if="isPersonalScope"
      class="rounded-lg border border-surface-200 bg-surface-50 p-4 text-sm text-surface-600 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-300"
    >
      <i class="pi pi-info-circle mr-1" />
      {{ t('receipt.list.notice.personalScopeUnsupported') }}
    </div>

    <div
      v-else-if="!scopeReady"
      class="rounded-lg border border-surface-200 bg-surface-50 p-4 text-sm text-surface-600 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-300"
    >
      <i class="pi pi-info-circle mr-1" />
      {{ t('receipt.list.notice.scopeNotReady') }}
    </div>

    <DataTable
      v-else
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
              @click="openVoidDialog(data.id)"
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

    <!-- 無効化ダイアログ（BE は reason 必須） -->
    <Dialog
      v-model:visible="showVoidDialog"
      :header="t('receipt.list.dialog.voidTitle')"
      :style="{ width: '440px' }"
      modal
      :draggable="false"
    >
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('receipt.list.dialog.voidReason') }} <span class="text-red-500">*</span>
        </label>
        <Textarea
          v-model="voidReason"
          class="w-full"
          rows="3"
          :maxlength="500"
          :placeholder="t('receipt.list.dialog.voidReasonPlaceholder')"
          :invalid="!!voidReasonError"
        />
        <p v-if="voidReasonError" class="mt-1 text-xs text-red-500">{{ voidReasonError }}</p>
      </div>
      <template #footer>
        <Button :label="t('receipt.list.dialog.cancel')" severity="secondary" text @click="showVoidDialog = false" />
        <Button
          :label="t('receipt.list.dialog.voidSubmit')"
          icon="pi pi-ban"
          severity="danger"
          :loading="voidSubmitting"
          :disabled="!voidReason.trim()"
          @click="submitVoid"
        />
      </template>
    </Dialog>
  </div>
</template>
