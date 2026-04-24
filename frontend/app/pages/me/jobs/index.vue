<script setup lang="ts">
import type {
  ContractActionKind,
} from '~/components/jobs/ContractActionPanel.vue'
import type {
  JobApplicationResponse,
  JobContractResponse,
  JobPagedMeta,
} from '~/types/jobmatching'

/**
 * F13.1 Worker 向け「自分の求人」ページ。
 *
 * <p>タブで応募一覧 / 契約一覧を切替表示する。
 * 応募タブ: GET /api/v1/me/applications（取り下げボタン付き）
 * 契約タブ: GET /api/v1/me/contracts（Worker 視点で完了報告・キャンセル可能）</p>
 */

const { t, locale } = useI18n()
const authStore = useAuthStore()
const applicationApi = useJobApplicationApi()
const contractApi = useJobContractApi()
const { success, error } = useNotification()

type TabKey = 'applications' | 'contracts'
const activeTab = ref<TabKey>('applications')

const PAGE_SIZE = 20

// === 応募履歴 ===
const applications = ref<JobApplicationResponse[]>([])
const applicationsMeta = ref<JobPagedMeta>({
  total: 0,
  page: 0,
  size: PAGE_SIZE,
  totalPages: 0,
})
const applicationsLoading = ref(false)
const applicationsPage = ref(0)
const busyApplicationId = ref<number | null>(null)

// === 契約履歴 ===
const contracts = ref<JobContractResponse[]>([])
const contractsMeta = ref<JobPagedMeta>({
  total: 0,
  page: 0,
  size: PAGE_SIZE,
  totalPages: 0,
})
const contractsLoading = ref(false)
const contractsPage = ref(0)
const busyContractKey = ref<string | null>(null)

// Contract の差し戻しダイアログ
const rejectDialog = ref(false)
const rejectTargetContractId = ref<number | null>(null)
const rejectReason = ref('')

// Contract のキャンセルダイアログ
const cancelDialog = ref(false)
const cancelTargetContractId = ref<number | null>(null)
const cancelReason = ref('')

const tabs = computed(() => [
  { label: t('jobmatching.myJobs.tabs.applications'), value: 'applications' as TabKey },
  { label: t('jobmatching.myJobs.tabs.contracts'), value: 'contracts' as TabKey },
])

async function loadApplications(page = 0) {
  applicationsLoading.value = true
  applicationsPage.value = page
  try {
    const res = await applicationApi.listMyApplications({ page, size: PAGE_SIZE })
    applications.value = res.data
    applicationsMeta.value = res.meta
  }
  catch (e) {
    error(t('jobmatching.error.loadFailed'), String(e))
    applications.value = []
  }
  finally {
    applicationsLoading.value = false
  }
}

async function loadContracts(page = 0) {
  contractsLoading.value = true
  contractsPage.value = page
  try {
    const res = await contractApi.listMyContracts({ page, size: PAGE_SIZE })
    contracts.value = res.data
    contractsMeta.value = res.meta
  }
  catch (e) {
    error(t('jobmatching.error.loadFailed'), String(e))
    contracts.value = []
  }
  finally {
    contractsLoading.value = false
  }
}

async function withdrawApplication(applicationId: number) {
  busyApplicationId.value = applicationId
  try {
    await applicationApi.withdrawApplication(applicationId)
    success(t('jobmatching.myJobs.withdrawSucceeded'))
    await loadApplications(applicationsPage.value)
  }
  catch (e) {
    error(t('jobmatching.myJobs.withdrawFailed'), String(e))
  }
  finally {
    busyApplicationId.value = null
  }
}

// --- 契約アクション ---

function contractKey(id: number, kind: ContractActionKind): string {
  return `${id}:${kind}`
}

async function reportCompletion(contractId: number) {
  busyContractKey.value = contractKey(contractId, 'report-completion')
  try {
    await contractApi.reportCompletion(contractId, { message: null })
    success(t('jobmatching.contract.reportSucceeded'))
    await loadContracts(contractsPage.value)
  }
  catch (e) {
    error(t('jobmatching.contract.reportFailed'), String(e))
  }
  finally {
    busyContractKey.value = null
  }
}

async function approveCompletion(contractId: number) {
  busyContractKey.value = contractKey(contractId, 'approve-completion')
  try {
    await contractApi.approveCompletion(contractId)
    success(t('jobmatching.contract.approveSucceeded'))
    await loadContracts(contractsPage.value)
  }
  catch (e) {
    error(t('jobmatching.contract.approveFailed'), String(e))
  }
  finally {
    busyContractKey.value = null
  }
}

function openRejectCompletionDialog(contractId: number) {
  rejectTargetContractId.value = contractId
  rejectReason.value = ''
  rejectDialog.value = true
}

async function confirmRejectCompletion() {
  if (rejectTargetContractId.value == null) return
  if (!rejectReason.value.trim()) {
    error(t('jobmatching.contract.rejectReasonRequired'))
    return
  }
  const id = rejectTargetContractId.value
  busyContractKey.value = contractKey(id, 'reject-completion')
  try {
    await contractApi.rejectCompletion(id, { reason: rejectReason.value.trim() })
    success(t('jobmatching.contract.rejectSucceeded'))
    rejectDialog.value = false
    rejectTargetContractId.value = null
    rejectReason.value = ''
    await loadContracts(contractsPage.value)
  }
  catch (e) {
    error(t('jobmatching.contract.rejectFailed'), String(e))
  }
  finally {
    busyContractKey.value = null
  }
}

function openCancelDialog(contractId: number) {
  cancelTargetContractId.value = contractId
  cancelReason.value = ''
  cancelDialog.value = true
}

async function confirmCancel() {
  if (cancelTargetContractId.value == null) return
  const id = cancelTargetContractId.value
  busyContractKey.value = contractKey(id, 'cancel')
  try {
    await contractApi.cancelContract(id, {
      reason: cancelReason.value.trim() || null,
    })
    success(t('jobmatching.contract.cancelSucceeded'))
    cancelDialog.value = false
    cancelTargetContractId.value = null
    cancelReason.value = ''
    await loadContracts(contractsPage.value)
  }
  catch (e) {
    error(t('jobmatching.contract.cancelFailed'), String(e))
  }
  finally {
    busyContractKey.value = null
  }
}

// --- ヘルパ ---

function formatDateTime(iso: string | null): string {
  if (!iso) return '-'
  try {
    return new Date(iso).toLocaleString(locale.value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  }
  catch {
    return iso
  }
}

function fmtJpy(v: number): string {
  return `¥${v.toLocaleString()}`
}

const currentUserId = computed<number>(() => authStore.user?.id ?? 0)

// --- QR スキャン導線（Worker のみ、未完了ステータスで表示） ---

/**
 * Worker かつ MATCHED/CHECKED_IN/IN_PROGRESS の契約にのみ QR スキャン導線を出す。
 * CHECKED_OUT 以降は時刻確定フェーズ（QR は使わない）。
 */
function canShowScanLink(contract: JobContractResponse): boolean {
  if (contract.workerUserId !== currentUserId.value) return false
  return (
    contract.status === 'MATCHED'
    || contract.status === 'CHECKED_IN'
    || contract.status === 'IN_PROGRESS'
  )
}

/** 契約 status から次に取るべき IN/OUT を推定する。 */
function scanTypeFor(contract: JobContractResponse): 'IN' | 'OUT' {
  if (contract.status === 'CHECKED_IN' || contract.status === 'IN_PROGRESS') return 'OUT'
  return 'IN'
}

// ContractActionPanel の busyAction を各契約ごとに算出
function busyActionFor(contractId: number): ContractActionKind | null {
  const key = busyContractKey.value
  if (!key) return null
  const [idStr, kind] = key.split(':') as [string, ContractActionKind]
  if (Number(idStr) !== contractId) return null
  return kind
}

// タブ切替時に未読込ならロード
watch(activeTab, async (newTab) => {
  if (newTab === 'applications' && applications.value.length === 0) {
    await loadApplications(0)
  }
  if (newTab === 'contracts' && contracts.value.length === 0) {
    await loadContracts(0)
  }
})

onMounted(async () => {
  await loadApplications(0)
})
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <div class="mb-4">
      <h1 class="text-2xl font-bold">
        {{ t('jobmatching.myJobs.title') }}
      </h1>
      <p class="mt-1 text-sm text-surface-500">
        {{ t('jobmatching.myJobs.description') }}
      </p>
    </div>

    <!-- タブ -->
    <div class="mb-5">
      <SelectButton
        v-model="activeTab"
        :options="tabs"
        option-label="label"
        option-value="value"
        :allow-empty="false"
      />
    </div>

    <!-- 応募タブ -->
    <section v-if="activeTab === 'applications'">
      <div
        v-if="applicationsLoading"
        class="flex justify-center p-8"
      >
        <ProgressSpinner />
      </div>

      <div
        v-else-if="applications.length === 0"
        class="rounded border border-dashed border-surface-300 p-8 text-center text-surface-500 dark:border-surface-600"
      >
        {{ t('jobmatching.myJobs.applicationsEmpty') }}
      </div>

      <ul
        v-else
        class="flex flex-col gap-3"
      >
        <li
          v-for="app in applications"
          :key="app.id"
          class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0 flex-1">
              <NuxtLink
                :to="`/jobs/${app.jobPostingId}`"
                class="text-sm font-semibold hover:underline"
              >
                {{ t('jobmatching.myJobs.jobLink', { id: app.jobPostingId }) }}
              </NuxtLink>
              <p class="mt-1 text-xs text-surface-500">
                {{ t('jobmatching.application.appliedAt', { at: formatDateTime(app.appliedAt) }) }}
              </p>
              <p
                v-if="app.selfPr"
                class="mt-2 whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-200"
              >
                {{ app.selfPr }}
              </p>
            </div>

            <div class="flex shrink-0 flex-col items-end gap-2">
              <JobStatusBadge
                kind="application"
                :status="app.status"
              />
              <Button
                v-if="app.status === 'APPLIED'"
                :label="t('jobmatching.myJobs.withdrawButton')"
                icon="pi pi-times"
                severity="secondary"
                size="small"
                outlined
                :loading="busyApplicationId === app.id"
                :disabled="busyApplicationId === app.id"
                @click="withdrawApplication(app.id)"
              />
            </div>
          </div>
        </li>
      </ul>

      <div
        v-if="applicationsMeta.totalPages > 1"
        class="mt-6 flex justify-center"
      >
        <Paginator
          :rows="PAGE_SIZE"
          :total-records="applicationsMeta.total"
          :first="applicationsPage * PAGE_SIZE"
          @page="(e: { page: number }) => loadApplications(e.page)"
        />
      </div>
    </section>

    <!-- 契約タブ -->
    <section v-else-if="activeTab === 'contracts'">
      <div
        v-if="contractsLoading"
        class="flex justify-center p-8"
      >
        <ProgressSpinner />
      </div>

      <div
        v-else-if="contracts.length === 0"
        class="rounded border border-dashed border-surface-300 p-8 text-center text-surface-500 dark:border-surface-600"
      >
        {{ t('jobmatching.myJobs.contractsEmpty') }}
      </div>

      <ul
        v-else
        class="flex flex-col gap-3"
      >
        <li
          v-for="contract in contracts"
          :key="contract.id"
          class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="mb-3 flex items-start justify-between gap-3">
            <div class="min-w-0 flex-1">
              <NuxtLink
                :to="`/jobs/${contract.jobPostingId}`"
                class="text-sm font-semibold hover:underline"
              >
                {{ t('jobmatching.myJobs.contractLink', { id: contract.jobPostingId }) }}
              </NuxtLink>
              <p class="mt-1 text-xs text-surface-500">
                {{ t('jobmatching.contract.matchedAt', { at: formatDateTime(contract.matchedAt) }) }}
              </p>
              <p class="mt-0.5 text-xs text-surface-500">
                {{ formatDateTime(contract.workStartAt) }} - {{ formatDateTime(contract.workEndAt) }}
              </p>
            </div>

            <div class="flex shrink-0 flex-col items-end gap-1">
              <JobStatusBadge
                kind="contract"
                :status="contract.status"
              />
              <span class="text-sm font-semibold">
                {{ fmtJpy(contract.baseRewardJpy) }}
              </span>
            </div>
          </div>

          <div
            v-if="canShowScanLink(contract)"
            class="mb-2 flex flex-wrap gap-2"
          >
            <NuxtLink :to="`/contracts/${contract.id}/scan?type=${scanTypeFor(contract)}`">
              <Button
                :label="t(`jobmatching.qr.scanner.linkFromContract.${scanTypeFor(contract)}`)"
                icon="pi pi-qrcode"
                severity="info"
                size="small"
                data-testid="qr-scan-link"
              />
            </NuxtLink>
          </div>

          <ContractActionPanel
            :contract="contract"
            :current-user-id="currentUserId"
            :busy-action="busyActionFor(contract.id)"
            @report-completion="reportCompletion(contract.id)"
            @approve-completion="approveCompletion(contract.id)"
            @reject-completion="openRejectCompletionDialog(contract.id)"
            @cancel="openCancelDialog(contract.id)"
          />
        </li>
      </ul>

      <div
        v-if="contractsMeta.totalPages > 1"
        class="mt-6 flex justify-center"
      >
        <Paginator
          :rows="PAGE_SIZE"
          :total-records="contractsMeta.total"
          :first="contractsPage * PAGE_SIZE"
          @page="(e: { page: number }) => loadContracts(e.page)"
        />
      </div>
    </section>

    <!-- 完了差し戻しダイアログ -->
    <Dialog
      v-model:visible="rejectDialog"
      :header="t('jobmatching.contract.rejectDialog.title')"
      modal
      :style="{ width: '480px' }"
    >
      <p class="mb-3 text-sm text-surface-600 dark:text-surface-300">
        {{ t('jobmatching.contract.rejectDialog.description') }}
      </p>
      <Textarea
        v-model="rejectReason"
        :placeholder="t('jobmatching.contract.rejectDialog.reasonPlaceholder')"
        rows="4"
        maxlength="1000"
        class="w-full"
        auto-resize
      />
      <template #footer>
        <Button
          :label="t('jobmatching.contract.rejectDialog.cancel')"
          severity="secondary"
          outlined
          @click="rejectDialog = false"
        />
        <Button
          :label="t('jobmatching.contract.rejectDialog.confirm')"
          severity="warn"
          :loading="busyContractKey !== null"
          :disabled="!rejectReason.trim() || busyContractKey !== null"
          @click="confirmRejectCompletion"
        />
      </template>
    </Dialog>

    <!-- キャンセルダイアログ -->
    <Dialog
      v-model:visible="cancelDialog"
      :header="t('jobmatching.contract.cancelDialog.title')"
      modal
      :style="{ width: '480px' }"
    >
      <p class="mb-3 text-sm text-surface-600 dark:text-surface-300">
        {{ t('jobmatching.contract.cancelDialog.description') }}
      </p>
      <Textarea
        v-model="cancelReason"
        :placeholder="t('jobmatching.contract.cancelDialog.reasonPlaceholder')"
        rows="3"
        maxlength="500"
        class="w-full"
        auto-resize
      />
      <template #footer>
        <Button
          :label="t('jobmatching.contract.cancelDialog.cancel')"
          severity="secondary"
          outlined
          @click="cancelDialog = false"
        />
        <Button
          :label="t('jobmatching.contract.cancelDialog.confirm')"
          severity="danger"
          :loading="busyContractKey !== null"
          :disabled="busyContractKey !== null"
          @click="confirmCancel"
        />
      </template>
    </Dialog>
  </div>
</template>
