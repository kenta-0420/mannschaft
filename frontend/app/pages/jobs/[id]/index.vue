<script setup lang="ts">
import type { JobPostingResponse } from '~/types/jobmatching'

/**
 * F13.1 Worker 向け求人詳細ページ。
 *
 * <p>求人情報を表示し、OPEN ステータスのときのみ応募ボタンを表示する。
 * 応募ボタン押下で自己 PR 入力ダイアログを出し、送信後は /me/jobs?tab=applications に遷移する。</p>
 */

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()
const postingApi = useJobPostingApi()
const applicationApi = useJobApplicationApi()
const { success, error } = useNotification()

const jobId = computed(() => Number(route.params.id))

const job = ref<JobPostingResponse | null>(null)
const loading = ref(false)

// 応募ダイアログ
const applyDialog = ref(false)
const selfPr = ref('')
const applying = ref(false)

async function load() {
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

function openApplyDialog() {
  selfPr.value = ''
  applyDialog.value = true
}

async function submitApply() {
  if (!job.value) return
  applying.value = true
  try {
    await applicationApi.applyToJob(job.value.id, {
      selfPr: selfPr.value.trim() || null,
    })
    success(t('jobmatching.workerDetail.applySucceeded'))
    applyDialog.value = false
    router.push('/me/jobs')
  }
  catch (e) {
    error(t('jobmatching.workerDetail.applyFailed'), String(e))
  }
  finally {
    applying.value = false
  }
}

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

const canApply = computed(() => job.value?.status === 'OPEN')

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
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
          @click="router.push('/jobs')"
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

      <!-- サマリ（報酬強調） -->
      <section class="mb-5 rounded-xl bg-primary-50 p-4 dark:bg-primary-950">
        <div class="flex items-center justify-between gap-3">
          <span class="text-sm text-surface-600 dark:text-surface-300">
            {{ t(`jobmatching.rewardType.${job.rewardType}`) }}
          </span>
          <span class="text-2xl font-bold">
            {{ fmtJpy(job.baseRewardJpy) }}
          </span>
        </div>
      </section>

      <!-- 応募ボタン -->
      <div
        v-if="canApply"
        class="mb-5"
      >
        <Button
          :label="t('jobmatching.workerDetail.applyButton')"
          icon="pi pi-send"
          size="large"
          class="w-full sm:w-auto"
          @click="openApplyDialog"
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
        </dl>
      </section>
    </div>

    <!-- 応募ダイアログ -->
    <Dialog
      v-model:visible="applyDialog"
      :header="t('jobmatching.workerDetail.applyDialog.title')"
      modal
      :style="{ width: '480px' }"
    >
      <p class="mb-3 text-sm text-surface-600 dark:text-surface-300">
        {{ t('jobmatching.workerDetail.applyDialog.description') }}
      </p>
      <Textarea
        v-model="selfPr"
        :placeholder="t('jobmatching.workerDetail.applyDialog.selfPrPlaceholder')"
        rows="5"
        maxlength="500"
        class="w-full"
        auto-resize
      />
      <template #footer>
        <Button
          :label="t('jobmatching.workerDetail.applyDialog.cancel')"
          severity="secondary"
          outlined
          :disabled="applying"
          @click="applyDialog = false"
        />
        <Button
          :label="t('jobmatching.workerDetail.applyDialog.confirm')"
          icon="pi pi-send"
          :loading="applying"
          :disabled="applying"
          @click="submitApply"
        />
      </template>
    </Dialog>
  </div>
</template>
