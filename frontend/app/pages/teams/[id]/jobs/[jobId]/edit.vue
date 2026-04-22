<script setup lang="ts">
import type {
  JobPostingResponse,
  UpdateJobPostingRequest,
  VisibilityScope,
  WorkLocationType,
  RewardType,
} from '~/types/jobmatching'
import type { JobPostingFormState } from '~/components/jobs/JobPostingForm.vue'

/**
 * F13.1 求人編集ページ（Requester 視点）。
 *
 * <p>既存求人を読み込み、PATCH で部分更新する。
 * 応募者が 1 件以上ある場合の報酬・業務日時・締切・定員・公開範囲の変更は
 * BE Service で拒否される（応募者保護）。FE ではエラーをそのまま表示するだけとし、
 * 「変更不可」の事前判定は MVP では実装しない（通知で気付ける）。</p>
 */

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const api = useJobPostingApi()
const { success, error } = useNotification()

const teamId = computed(() => Number(route.params.id))
const jobId = computed(() => Number(route.params.jobId))

const job = ref<JobPostingResponse | null>(null)
const loading = ref(true)
const submitting = ref(false)

function emptyForm(): JobPostingFormState {
  return {
    title: '',
    description: '',
    category: '',
    workLocationType: 'ONSITE' satisfies WorkLocationType,
    workAddress: '',
    workStartAt: null,
    workEndAt: null,
    rewardType: 'LUMP_SUM' satisfies RewardType,
    baseRewardJpy: null,
    capacity: 1,
    applicationDeadlineAt: null,
    visibilityScope: 'TEAM_MEMBERS' satisfies VisibilityScope,
  }
}

const form = ref<JobPostingFormState>(emptyForm())

function parseIsoToDate(iso: string | null): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

function applyJobToForm(j: JobPostingResponse) {
  form.value = {
    title: j.title,
    description: j.description,
    category: j.category ?? '',
    workLocationType: j.workLocationType,
    workAddress: j.workAddress ?? '',
    workStartAt: parseIsoToDate(j.workStartAt),
    workEndAt: parseIsoToDate(j.workEndAt),
    rewardType: j.rewardType,
    baseRewardJpy: j.baseRewardJpy,
    capacity: j.capacity,
    applicationDeadlineAt: parseIsoToDate(j.applicationDeadlineAt),
    visibilityScope: j.visibilityScope,
  }
}

async function load() {
  loading.value = true
  try {
    const res = await api.getJob(jobId.value)
    job.value = res.data
    applyJobToForm(res.data)
  }
  catch (e) {
    error(t('jobmatching.error.loadFailed'), String(e))
    job.value = null
  }
  finally {
    loading.value = false
  }
}

function validate(): string | null {
  const f = form.value
  if (!f.title.trim()) return t('jobmatching.validation.titleRequired')
  if (!f.description.trim()) return t('jobmatching.validation.descriptionRequired')
  if (!f.workStartAt || !f.workEndAt) return t('jobmatching.validation.workDateRequired')
  if (f.workStartAt >= f.workEndAt) return t('jobmatching.validation.workEndAfterStart')
  if (!f.applicationDeadlineAt) return t('jobmatching.validation.deadlineRequired')
  if (f.applicationDeadlineAt >= f.workStartAt) {
    return t('jobmatching.validation.deadlineBeforeStart')
  }
  if (f.baseRewardJpy == null) return t('jobmatching.validation.rewardRequired')
  if (f.baseRewardJpy < 500 || f.baseRewardJpy > 1_000_000) {
    return t('jobmatching.validation.rewardRange')
  }
  if (f.capacity < 1) return t('jobmatching.validation.capacityMin')
  if ((f.workLocationType === 'ONSITE' || f.workLocationType === 'HYBRID') && !f.workAddress.trim()) {
    return t('jobmatching.validation.workAddressRequired')
  }
  return null
}

async function save() {
  const err = validate()
  if (err) {
    error(t('jobmatching.validation.failed'), err)
    return
  }
  submitting.value = true
  try {
    const f = form.value
    const body: UpdateJobPostingRequest = {
      title: f.title.trim(),
      description: f.description.trim(),
      category: f.category.trim() || null,
      workLocationType: f.workLocationType,
      workAddress: f.workAddress.trim() || null,
      workStartAt: f.workStartAt ? f.workStartAt.toISOString() : null,
      workEndAt: f.workEndAt ? f.workEndAt.toISOString() : null,
      rewardType: f.rewardType,
      baseRewardJpy: f.baseRewardJpy,
      capacity: f.capacity,
      applicationDeadlineAt: f.applicationDeadlineAt
        ? f.applicationDeadlineAt.toISOString()
        : null,
      visibilityScope: f.visibilityScope,
    }
    await api.updateJob(jobId.value, body)
    success(t('jobmatching.edit.saved'))
    router.push(`/teams/${teamId.value}/jobs/${jobId.value}`)
  }
  catch (e) {
    error(t('jobmatching.edit.failed'), String(e))
  }
  finally {
    submitting.value = false
  }
}

function cancel() {
  router.push(`/teams/${teamId.value}/jobs/${jobId.value}`)
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <div class="mb-4">
      <h1 class="text-2xl font-bold">
        {{ t('jobmatching.edit.title') }}
      </h1>
      <p class="mt-1 text-sm text-surface-500">
        {{ t('jobmatching.edit.description') }}
      </p>
    </div>

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

    <JobPostingForm
      v-else
      v-model="form"
      :existing="job"
      editing
      :submitting="submitting"
    >
      <template #submit="{ submitting: busy }">
        <Button
          :label="t('jobmatching.edit.cancel')"
          severity="secondary"
          outlined
          :disabled="busy"
          @click="cancel"
        />
        <Button
          :label="t('jobmatching.edit.save')"
          icon="pi pi-check"
          :loading="busy"
          :disabled="busy"
          @click="save"
        />
      </template>
    </JobPostingForm>
  </div>
</template>
