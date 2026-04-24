<script setup lang="ts">
import type {
  JobApplicationResponse,
  JobPagedMeta,
  JobPostingResponse,
} from '~/types/jobmatching'
// F13.1 Phase 13.1.2: ACCEPTED 応募について「QR を表示」ボタンを出すため、
// 現在ログイン中ユーザーの契約一覧を引いて応募 ID → 契約 ID のマップを作る。

/**
 * F13.1 チーム配下求人詳細（Requester 視点）。
 *
 * <p>求人の詳細表示 + 編集/公開/終了/キャンセル/削除ボタン + 応募者一覧を 1 ページに集約する。
 * ボタンの有効無効は求人ステータスによって決まる（DRAFT は編集/公開/キャンセル/削除、
 * OPEN は終了/キャンセル、CLOSED/CANCELLED は閲覧のみ）。</p>
 */

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()
const postingApi = useJobPostingApi()
const applicationApi = useJobApplicationApi()
const contractApi = useJobContractApi()
const { success, error, warn } = useNotification()

const teamId = computed(() => Number(route.params.id))
const jobId = computed(() => Number(route.params.jobId))

const job = ref<JobPostingResponse | null>(null)
const loading = ref(false)
const applications = ref<JobApplicationResponse[]>([])
const applicationsMeta = ref<JobPagedMeta>({ total: 0, page: 0, size: 20, totalPages: 0 })
const applicationsLoading = ref(false)
const busyAction = ref<string | null>(null)
const busyApplicationId = ref<number | null>(null)
/**
 * F13.1 Phase 13.1.2: 応募 ID → 契約 ID のマップ。
 * ACCEPTED 応募について「QR を表示」ボタンを出すために使う。
 */
const contractIdByApplicationId = ref<Record<number, number>>({})

// --- reject-completion ならぬ reject-application ダイアログ ---
const rejectDialog = ref(false)
const rejectTargetId = ref<number | null>(null)
const rejectReason = ref('')

async function loadJob() {
  loading.value = true
  try {
    const res = await postingApi.getJob(jobId.value)
    job.value = res.data
  }
  catch (e) {
    error(t('jobmatching.error.loadFailed'), String(e))
    job.value = null
  }
  finally {
    loading.value = false
  }
}

async function loadApplications() {
  applicationsLoading.value = true
  try {
    const res = await applicationApi.listApplicationsByJob(jobId.value, { page: 0, size: 20 })
    applications.value = res.data
    applicationsMeta.value = res.meta
    // ACCEPTED 応募に対応する契約 ID を引くため、自分の契約一覧から該当求人分を拾ってマップ化する。
    // 権限が無い／契約が存在しないケースは静かに空マップとする。
    await loadContractMap()
  }
  catch (e) {
    // 権限なし等で失敗するケースは静かに空表示
    applications.value = []
    applicationsMeta.value = { total: 0, page: 0, size: 20, totalPages: 0 }
    contractIdByApplicationId.value = {}
    warn(t('jobmatching.error.applicationsLoadFailed'), String(e))
  }
  finally {
    applicationsLoading.value = false
  }
}

/**
 * 自分（Requester）の契約一覧から、現在の求人 ID に紐づく契約を拾って
 * 応募 ID → 契約 ID のマップを作る。
 */
async function loadContractMap() {
  try {
    // 件数上限は一旦 100 件に。MVP なので十分。
    const res = await contractApi.listMyContracts({ page: 0, size: 100 })
    const map: Record<number, number> = {}
    for (const c of res.data) {
      if (c.jobPostingId === jobId.value) {
        map[c.jobApplicationId] = c.id
      }
    }
    contractIdByApplicationId.value = map
  }
  catch {
    // 契約一覧が取れなくても QR ボタンが出ないだけなので静かに握る。
    contractIdByApplicationId.value = {}
  }
}

function showQrForContract(contractId: number) {
  router.push(`/contracts/${contractId}/qr?type=IN`)
}

async function publish() {
  if (!job.value) return
  busyAction.value = 'publish'
  try {
    const res = await postingApi.publishJob(job.value.id)
    job.value = res.data
    success(t('jobmatching.detail.publishSucceeded'))
  }
  catch (e) {
    error(t('jobmatching.detail.publishFailed'), String(e))
  }
  finally {
    busyAction.value = null
  }
}

async function close() {
  if (!job.value) return
  busyAction.value = 'close'
  try {
    const res = await postingApi.closeJob(job.value.id)
    job.value = res.data
    success(t('jobmatching.detail.closeSucceeded'))
  }
  catch (e) {
    error(t('jobmatching.detail.closeFailed'), String(e))
  }
  finally {
    busyAction.value = null
  }
}

async function cancel() {
  if (!job.value) return
  busyAction.value = 'cancel'
  try {
    const res = await postingApi.cancelJob(job.value.id)
    job.value = res.data
    success(t('jobmatching.detail.cancelSucceeded'))
  }
  catch (e) {
    error(t('jobmatching.detail.cancelFailed'), String(e))
  }
  finally {
    busyAction.value = null
  }
}

async function remove() {
  if (!job.value) return
  busyAction.value = 'delete'
  try {
    await postingApi.deleteJob(job.value.id)
    success(t('jobmatching.detail.deleteSucceeded'))
    router.push(`/teams/${teamId.value}/jobs`)
  }
  catch (e) {
    error(t('jobmatching.detail.deleteFailed'), String(e))
  }
  finally {
    busyAction.value = null
  }
}

function goToEdit() {
  router.push(`/teams/${teamId.value}/jobs/${jobId.value}/edit`)
}

// --- 応募者の採用/不採用 ---

async function acceptApplication(applicationId: number) {
  busyApplicationId.value = applicationId
  try {
    await applicationApi.acceptApplication(applicationId)
    success(t('jobmatching.application.acceptSucceeded'))
    await Promise.all([loadJob(), loadApplications()])
  }
  catch (e) {
    error(t('jobmatching.application.acceptFailed'), String(e))
  }
  finally {
    busyApplicationId.value = null
  }
}

function openRejectDialog(applicationId: number) {
  rejectTargetId.value = applicationId
  rejectReason.value = ''
  rejectDialog.value = true
}

async function confirmReject() {
  if (rejectTargetId.value == null) return
  const id = rejectTargetId.value
  busyApplicationId.value = id
  try {
    await applicationApi.rejectApplication(id, {
      reason: rejectReason.value.trim() || null,
    })
    success(t('jobmatching.application.rejectSucceeded'))
    rejectDialog.value = false
    rejectTargetId.value = null
    rejectReason.value = ''
    await loadApplications()
  }
  catch (e) {
    error(t('jobmatching.application.rejectFailed'), String(e))
  }
  finally {
    busyApplicationId.value = null
  }
}

// --- 表示ヘルパ ---

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

const canEdit = computed(() => job.value?.status === 'DRAFT')
const canPublish = computed(() => job.value?.status === 'DRAFT')
const canClose = computed(() => job.value?.status === 'OPEN')
const canCancel = computed(() => job.value?.status === 'DRAFT' || job.value?.status === 'OPEN')
const canDelete = computed(() => job.value?.status === 'DRAFT')

onMounted(async () => {
  await loadJob()
  if (job.value) {
    loadApplications()
  }
})
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <div
      v-if="loading"
      class="flex justify-center p-8"
    >
      <ProgressSpinner />
    </div>

    <div
      v-else-if="!job"
      class="rounded border border-dashed border-surface-300 p-8 text-center text-surface-500 dark:border-surface-600"
    >
      {{ t('jobmatching.detail.notFound') }}
    </div>

    <div v-else>
      <!-- 戻る -->
      <div class="mb-3">
        <Button
          :label="t('jobmatching.detail.back')"
          icon="pi pi-arrow-left"
          severity="secondary"
          text
          @click="router.push(`/teams/${teamId}/jobs`)"
        />
      </div>

      <!-- ヘッダ -->
      <header class="mb-4">
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0 flex-1">
            <h1 class="text-2xl font-bold">
              {{ job.title }}
            </h1>
            <p
              v-if="job.category"
              class="mt-1 text-sm text-surface-500"
            >
              {{ job.category }}
            </p>
          </div>
          <JobStatusBadge
            kind="posting"
            :status="job.status"
          />
        </div>
      </header>

      <!-- アクションバー -->
      <div
        v-if="canEdit || canPublish || canClose || canCancel || canDelete"
        class="mb-5 flex flex-wrap gap-2 rounded-lg bg-surface-100 p-3 dark:bg-surface-800"
      >
        <Button
          v-if="canEdit"
          :label="t('jobmatching.detail.edit')"
          icon="pi pi-pencil"
          severity="secondary"
          @click="goToEdit"
        />
        <Button
          v-if="canPublish"
          :label="t('jobmatching.detail.publish')"
          icon="pi pi-send"
          :loading="busyAction === 'publish'"
          :disabled="busyAction !== null"
          @click="publish"
        />
        <Button
          v-if="canClose"
          :label="t('jobmatching.detail.close')"
          icon="pi pi-lock"
          severity="warn"
          outlined
          :loading="busyAction === 'close'"
          :disabled="busyAction !== null"
          @click="close"
        />
        <Button
          v-if="canCancel"
          :label="t('jobmatching.detail.cancel')"
          icon="pi pi-times-circle"
          severity="danger"
          outlined
          :loading="busyAction === 'cancel'"
          :disabled="busyAction !== null"
          @click="cancel"
        />
        <Button
          v-if="canDelete"
          :label="t('jobmatching.detail.delete')"
          icon="pi pi-trash"
          severity="danger"
          text
          :loading="busyAction === 'delete'"
          :disabled="busyAction !== null"
          @click="remove"
        />
      </div>

      <!-- 求人詳細 -->
      <section class="mb-6 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
        <h2 class="mb-2 text-base font-semibold">
          {{ t('jobmatching.detail.sections.overview') }}
        </h2>
        <p class="mb-4 whitespace-pre-wrap text-sm text-surface-800 dark:text-surface-100">
          {{ job.description }}
        </p>

        <dl class="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.workLocationType') }}
            </dt>
            <dd>{{ t(`jobmatching.workLocationType.${job.workLocationType}`) }}</dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.workAddress') }}
            </dt>
            <dd>{{ job.workAddress || '-' }}</dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.workStartAt') }}
            </dt>
            <dd>{{ formatDateTime(job.workStartAt) }}</dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.workEndAt') }}
            </dt>
            <dd>{{ formatDateTime(job.workEndAt) }}</dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.rewardType') }}
            </dt>
            <dd>{{ t(`jobmatching.rewardType.${job.rewardType}`) }}</dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.baseRewardJpy') }}
            </dt>
            <dd class="font-semibold">
              {{ fmtJpy(job.baseRewardJpy) }}
            </dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.capacity') }}
            </dt>
            <dd>{{ t('jobmatching.card.capacityValue', { count: job.capacity }) }}</dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.applicationDeadlineAt') }}
            </dt>
            <dd>{{ formatDateTime(job.applicationDeadlineAt) }}</dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.visibilityScope') }}
            </dt>
            <dd>{{ t(`jobmatching.visibilityScope.${job.visibilityScope}`) }}</dd>
          </div>
          <div>
            <dt class="text-surface-500">
              {{ t('jobmatching.detail.fields.publishAt') }}
            </dt>
            <dd>{{ formatDateTime(job.publishAt) }}</dd>
          </div>
        </dl>
      </section>

      <!-- 応募者一覧 -->
      <section>
        <h2 class="mb-3 text-base font-semibold">
          {{ t('jobmatching.detail.sections.applications', { count: applicationsMeta.total }) }}
        </h2>
        <ApplicationList
          :applications="applications"
          :loading="applicationsLoading"
          :busy-application-id="busyApplicationId"
          :contract-id-by-application-id="contractIdByApplicationId"
          @accept="acceptApplication"
          @reject="openRejectDialog"
          @show-qr="showQrForContract"
        />
      </section>
    </div>

    <!-- 不採用理由ダイアログ -->
    <Dialog
      v-model:visible="rejectDialog"
      :header="t('jobmatching.application.rejectDialog.title')"
      modal
      :style="{ width: '480px' }"
    >
      <p class="mb-3 text-sm text-surface-600 dark:text-surface-300">
        {{ t('jobmatching.application.rejectDialog.description') }}
      </p>
      <Textarea
        v-model="rejectReason"
        :placeholder="t('jobmatching.application.rejectDialog.reasonPlaceholder')"
        rows="4"
        maxlength="500"
        class="w-full"
        auto-resize
      />
      <template #footer>
        <Button
          :label="t('jobmatching.application.rejectDialog.cancel')"
          severity="secondary"
          outlined
          @click="rejectDialog = false"
        />
        <Button
          :label="t('jobmatching.application.rejectDialog.confirm')"
          severity="danger"
          :loading="busyApplicationId !== null"
          :disabled="busyApplicationId !== null"
          @click="confirmReject"
        />
      </template>
    </Dialog>
  </div>
</template>
