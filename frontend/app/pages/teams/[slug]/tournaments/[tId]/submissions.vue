<script setup lang="ts">
import type {
  SubmissionRequirementResponse,
} from '~/types/tournament'

definePageMeta({ layout: 'team', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const tId = Number(route.params.tId)
// orgId はナビゲーション時にクエリパラメータとして渡す
const orgId = String(route.query.orgId ?? '')

const notification = useNotification()

// orgId が取得できない場合は不正なアクセス
if (!orgId || isNaN(Number(orgId))) {
  throw createError({ statusCode: 400, statusMessage: 'orgId is required' })
}

const { listRequirementsForTeam, submitForTeam } = useTournamentSubmission(orgId, tId)

// ===== 状態 =====

const requirements = ref<SubmissionRequirementResponse[]>([])
const loadingReqs = ref(false)

// 提出ダイアログ制御
const submittingReqId = ref<number | null>(null)
const submitting = ref(false)

// ===== ヘルパー =====

function isDeadlinePassed(deadline: string | null): boolean {
  if (!deadline) return false
  return new Date(deadline) < new Date()
}

function canSubmit(req: SubmissionRequirementResponse): boolean {
  if (isDeadlinePassed(req.deadline)) return false
  return true
}

// ===== データ取得 =====

async function loadRequirements() {
  loadingReqs.value = true
  try {
    const res = await listRequirementsForTeam(teamSlug)
    requirements.value = res.data
  } catch {
    notification.error(t('tournament.submission.error_load'))
  } finally {
    loadingReqs.value = false
  }
}

// ===== 提出操作 =====

function openSubmitDialog(reqId: number) {
  submittingReqId.value = reqId
}

function cancelSubmit() {
  submittingReqId.value = null
}

async function doSubmit(reqId: number) {
  submitting.value = true
  try {
    await submitForTeam(reqId, teamSlug, {})
    notification.success(t('tournament.submission.submit_success'))
    submittingReqId.value = null
    await loadRequirements()
  } catch {
    notification.error(t('tournament.submission.submit_failed'))
  } finally {
    submitting.value = false
  }
}

// ===== 初期化 =====

onMounted(() => loadRequirements())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <div class="mb-4 flex items-center gap-3">
      <PageHeader :title="$t('tournament.submission.title')" :back-to="`/teams/${teamSlug}/tournaments`" />
    </div>

    <PageLoading v-if="loadingReqs" size="40px" />
    <DashboardEmptyState
      v-else-if="requirements.length === 0"
      icon="pi pi-file"
      :message="$t('tournament.submission.empty')"
    />
    <div v-else class="grid gap-4">
      <div
        v-for="req in requirements"
        :key="req.id"
        class="rounded-xl border border-surface-200 bg-surface-0 p-4"
      >
        <div class="flex items-start justify-between gap-3">
          <div class="flex-1">
            <p class="text-sm font-semibold">
              {{ req.formTemplateName ?? `Template #${req.formTemplateId}` }}
            </p>
            <div class="mt-1 flex flex-wrap gap-2 text-xs text-surface-500">
              <span v-if="req.deadline">
                {{ $t('tournament.submission.deadline') }}: {{ req.deadline }}
              </span>
            </div>

            <!-- 締切超過・支払い未完了のブロック表示 -->
            <div v-if="isDeadlinePassed(req.deadline)" class="mt-2">
              <span class="rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-600">
                {{ $t('tournament.submission.deadline_passed') }}
              </span>
            </div>
            <div v-else-if="req.requiresPayment" class="mt-2">
              <span class="rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700">
                {{ $t('tournament.submission.payment_required') }}
              </span>
            </div>
          </div>

          <div class="shrink-0">
            <Button
              v-if="canSubmit(req)"
              :label="$t('tournament.submission.submit')"
              icon="pi pi-send"
              size="small"
              :disabled="req.requiresPayment"
              @click="openSubmitDialog(req.id)"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 提出確認ダイアログ -->
    <Dialog
      v-if="submittingReqId !== null"
      :visible="submittingReqId !== null"
      :modal="true"
      :header="$t('tournament.submission.submit')"
      style="width: 24rem"
      @update:visible="cancelSubmit"
    >
      <p class="text-sm">{{ $t('tournament.submission.submit_confirm') }}</p>
      <template #footer>
        <Button :label="$t('common.cancel')" size="small" text @click="cancelSubmit" />
        <Button
          :label="$t('tournament.submission.submit')"
          size="small"
          :loading="submitting"
          @click="submittingReqId !== null && doSubmit(submittingReqId)"
        />
      </template>
    </Dialog>
  </div>
</template>
