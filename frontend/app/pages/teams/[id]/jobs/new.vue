<script setup lang="ts">
import type {
  CreateJobPostingRequest,
  RewardType,
  VisibilityScope,
  WorkLocationType,
} from '~/types/jobmatching'
import type { JobPostingFormState } from '~/components/jobs/JobPostingForm.vue'

/**
 * F13.1 求人新規投稿ページ（Requester 視点）。
 *
 * <p>DRAFT 作成 → 詳細画面で「公開」ボタンを押すフローを採用。
 * 即時公開は UX の安全策として行わない（下書き状態で見直す余地を残す）。</p>
 *
 * <p>「下書き保存して確認」: POST /api/v1/jobs（DRAFT 作成）→ 詳細画面へ遷移</p>
 */

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const api = useJobPostingApi()
const { success, error } = useNotification()

const teamId = computed(() => Number(route.params.id))

// デフォルト値（未来の日時を入れて Future バリデータを通しやすくする）
function createDefaultForm(): JobPostingFormState {
  const now = new Date()
  const oneWeekLater = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000)
  const oneWeekLaterEnd = new Date(oneWeekLater.getTime() + 3 * 60 * 60 * 1000)
  const threeDaysLater = new Date(now.getTime() + 3 * 24 * 60 * 60 * 1000)

  return {
    title: '',
    description: '',
    category: '',
    workLocationType: 'ONSITE' satisfies WorkLocationType,
    workAddress: '',
    workStartAt: oneWeekLater,
    workEndAt: oneWeekLaterEnd,
    rewardType: 'LUMP_SUM' satisfies RewardType,
    baseRewardJpy: null,
    capacity: 1,
    applicationDeadlineAt: threeDaysLater,
    visibilityScope: 'TEAM_MEMBERS' satisfies VisibilityScope,
  }
}

const form = ref<JobPostingFormState>(createDefaultForm())
const submitting = ref(false)

function toIsoOrThrow(d: Date | null, field: string): string {
  if (!d) throw new Error(`${field} is required`)
  return d.toISOString()
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

async function submitDraft() {
  const err = validate()
  if (err) {
    error(t('jobmatching.validation.failed'), err)
    return
  }
  submitting.value = true
  try {
    const f = form.value
    const body: CreateJobPostingRequest = {
      teamId: teamId.value,
      title: f.title.trim(),
      description: f.description.trim(),
      category: f.category.trim() || null,
      workLocationType: f.workLocationType,
      workAddress: f.workAddress.trim() || null,
      workStartAt: toIsoOrThrow(f.workStartAt, 'workStartAt'),
      workEndAt: toIsoOrThrow(f.workEndAt, 'workEndAt'),
      rewardType: f.rewardType,
      baseRewardJpy: f.baseRewardJpy!,
      capacity: f.capacity,
      applicationDeadlineAt: toIsoOrThrow(f.applicationDeadlineAt, 'applicationDeadlineAt'),
      visibilityScope: f.visibilityScope,
    }
    const res = await api.createJob(body)
    success(t('jobmatching.create.draftSaved'))
    router.push(`/teams/${teamId.value}/jobs/${res.data.id}`)
  }
  catch (e) {
    error(t('jobmatching.create.failed'), String(e))
  }
  finally {
    submitting.value = false
  }
}

function cancel() {
  router.push(`/teams/${teamId.value}/jobs`)
}
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <div class="mb-4">
      <h1 class="text-2xl font-bold">
        {{ t('jobmatching.create.title') }}
      </h1>
      <p class="mt-1 text-sm text-surface-500">
        {{ t('jobmatching.create.description') }}
      </p>
    </div>

    <JobPostingForm
      v-model="form"
      :submitting="submitting"
    >
      <template #submit="{ submitting: busy }">
        <Button
          :label="t('jobmatching.create.cancel')"
          severity="secondary"
          outlined
          :disabled="busy"
          @click="cancel"
        />
        <Button
          :label="t('jobmatching.create.saveDraft')"
          icon="pi pi-save"
          :loading="busy"
          :disabled="busy"
          @click="submitDraft"
        />
      </template>
    </JobPostingForm>
  </div>
</template>
