<script setup lang="ts">
import type {
  SubmissionRequirementResponse,
  SubmissionStatusDashboardResponse,
  CreateSubmissionRequirementRequest,
  UpdateSubmissionRequirementRequest,
  SubmissionRequirementTarget,
} from '~/types/tournament'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const tId = Number(route.params.tId)

const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const { listRequirementsForOrganizer, createRequirement, updateRequirement, deleteRequirement, getStatusDashboard } =
  useTournamentSubmission(orgSlug, tId)
const notification = useNotification()

// ===== 状態 =====

const activeTab = ref<'list' | 'dashboard'>('list')
const requirements = ref<SubmissionRequirementResponse[]>([])
const loadingReqs = ref(false)

const selectedReqId = ref<number | null>(null)
const dashboard = ref<SubmissionStatusDashboardResponse | null>(null)
const loadingDashboard = ref(false)

// フォーム表示制御
const showCreateForm = ref(false)
const editingReq = ref<SubmissionRequirementResponse | null>(null)
const saving = ref(false)

// フォームモデル（formTemplateIdはInputText用にstring管理し、保存時にNumber変換）
const form = ref<{
  formTemplateId: string
  deadline: string
  target: SubmissionRequirementTarget
  targetTeamIds: string
  requiresPayment: boolean
}>({
  formTemplateId: '',
  deadline: '',
  target: 'ALL',
  targetTeamIds: '',
  requiresPayment: false,
})

// ===== ヘルパー =====

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    NOT_SUBMITTED: t('tournament.submission.not_submitted'),
    SUBMITTED: t('tournament.submission.submitted'),
    APPROVED: t('tournament.submission.approved'),
    REJECTED: t('tournament.submission.rejected'),
  }
  return map[status] ?? status
}

function statusClass(status: string): string {
  const map: Record<string, string> = {
    NOT_SUBMITTED: 'bg-surface-100 text-surface-600',
    SUBMITTED: 'bg-blue-100 text-blue-700',
    APPROVED: 'bg-green-100 text-green-700',
    REJECTED: 'bg-red-100 text-red-600',
  }
  return map[status] ?? 'bg-surface-100'
}

function isDeadlinePassed(deadline: string | null): boolean {
  if (!deadline) return false
  return new Date(deadline) < new Date()
}

// ===== データ取得 =====

async function loadRequirements() {
  loadingReqs.value = true
  try {
    const res = await listRequirementsForOrganizer()
    requirements.value = res.data
  } catch {
    notification.error(t('tournament.submission.error_load'))
  } finally {
    loadingReqs.value = false
  }
}

async function loadDashboard(reqId: number) {
  loadingDashboard.value = true
  dashboard.value = null
  try {
    const res = await getStatusDashboard(reqId)
    dashboard.value = res.data
  } catch {
    notification.error(t('tournament.submission.error_load'))
  } finally {
    loadingDashboard.value = false
  }
}

function onSelectDashboard(reqId: number) {
  selectedReqId.value = reqId
  loadDashboard(reqId)
}

// ===== フォーム操作 =====

function openCreate() {
  editingReq.value = null
  form.value = { formTemplateId: '', deadline: '', target: 'ALL', targetTeamIds: '', requiresPayment: false }
  showCreateForm.value = true
}

function openEdit(req: SubmissionRequirementResponse) {
  editingReq.value = req
  form.value = {
    formTemplateId: String(req.formTemplateId),
    deadline: req.deadline ?? '',
    target: req.target,
    targetTeamIds: req.targetTeamIds ? req.targetTeamIds.join(',') : '',
    requiresPayment: req.requiresPayment,
  }
  showCreateForm.value = true
}

function cancelForm() {
  showCreateForm.value = false
  editingReq.value = null
}

function parseTargetTeamIds(raw: string): number[] | null {
  if (!raw.trim()) return null
  return raw
    .split(',')
    .map(s => parseInt(s.trim(), 10))
    .filter(n => !isNaN(n))
}

async function saveRequirement() {
  const formTemplateId = parseInt(form.value.formTemplateId, 10)
  if (!form.value.formTemplateId || isNaN(formTemplateId)) {
    notification.error(t('tournament.submission.error_template_required'))
    return
  }
  saving.value = true
  try {
    if (editingReq.value) {
      const body: UpdateSubmissionRequirementRequest = {
        formTemplateId,
        deadline: form.value.deadline || null,
        target: form.value.target,
        targetTeamIds: form.value.target === 'SPECIFIC' ? parseTargetTeamIds(form.value.targetTeamIds) : null,
        requiresPayment: form.value.requiresPayment,
      }
      await updateRequirement(editingReq.value.id, body)
    } else {
      const body: CreateSubmissionRequirementRequest = {
        formTemplateId,
        deadline: form.value.deadline || null,
        target: form.value.target,
        targetTeamIds: form.value.target === 'SPECIFIC' ? parseTargetTeamIds(form.value.targetTeamIds) : null,
        requiresPayment: form.value.requiresPayment,
      }
      await createRequirement(body)
    }
    notification.success(t('common.saved'))
    showCreateForm.value = false
    editingReq.value = null
    await loadRequirements()
  } catch {
    notification.error(t('common.save_failed'))
  } finally {
    saving.value = false
  }
}

async function confirmDelete(reqId: number) {
  try {
    await deleteRequirement(reqId)
    notification.success(t('common.deleted'))
    requirements.value = requirements.value.filter(r => r.id !== reqId)
    if (selectedReqId.value === reqId) {
      selectedReqId.value = null
      dashboard.value = null
    }
  } catch {
    notification.error(t('common.delete_failed'))
  }
}

// ===== 初期化 =====

onMounted(async () => {
  await loadPermissions()
  await loadRequirements()
})
</script>

<template>
  <div class="mx-auto max-w-5xl">
    <div class="mb-4 flex items-center gap-3">
      <BackButton :to="`/organizations/${orgSlug}/tournaments/${tId}`" :label="$t('tournament.submission.back_to_tournament')" />
      <PageHeader :title="$t('tournament.submission.title')" />
    </div>

    <!-- タブ切替 -->
    <div class="mb-4 flex gap-1 border-b border-surface-200">
      <button
        class="px-4 py-2 text-sm font-medium transition-colors"
        :class="activeTab === 'list' ? 'border-b-2 border-primary text-primary' : 'text-surface-500 hover:text-surface-700'"
        @click="activeTab = 'list'"
      >
        {{ $t('tournament.submission.requirement') }}
      </button>
      <button
        class="px-4 py-2 text-sm font-medium transition-colors"
        :class="activeTab === 'dashboard' ? 'border-b-2 border-primary text-primary' : 'text-surface-500 hover:text-surface-700'"
        @click="activeTab = 'dashboard'"
      >
        {{ $t('tournament.submission.status_dashboard') }}
      </button>
    </div>

    <!-- ========== 提出枠一覧タブ ========== -->
    <template v-if="activeTab === 'list'">
      <div v-if="isAdminOrDeputy" class="mb-4 flex justify-end">
        <Button
          :label="$t('tournament.submission.create_requirement')"
          icon="pi pi-plus"
          size="small"
          @click="openCreate"
        />
      </div>

      <!-- 作成/編集フォーム -->
      <div v-if="showCreateForm" class="mb-6 rounded-xl border border-surface-200 bg-surface-0 p-4">
        <h3 class="mb-4 text-sm font-semibold">
          {{ editingReq ? $t('tournament.submission.edit_requirement') : $t('tournament.submission.create_requirement') }}
        </h3>
        <div class="grid gap-4 sm:grid-cols-2">
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-500">
              {{ $t('tournament.submission.form_template_id') }}
            </label>
            <InputText
              v-model="form.formTemplateId"
              type="number"
              class="w-full"
              :placeholder="$t('tournament.submission.form_template_id_placeholder')"
            />
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-500">
              {{ $t('tournament.submission.deadline') }}
            </label>
            <InputText
              v-model="form.deadline"
              type="datetime-local"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-500">
              {{ $t('tournament.submission.target') }}
            </label>
            <Select
              v-model="form.target"
              :options="[
                { label: $t('tournament.submission.target_all'), value: 'ALL' },
                { label: $t('tournament.submission.target_specific'), value: 'SPECIFIC' },
              ]"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div v-if="form.target === 'SPECIFIC'">
            <label class="mb-1 block text-xs font-medium text-surface-500">
              {{ $t('tournament.submission.target_team_ids') }}
            </label>
            <InputText
              v-model="form.targetTeamIds"
              class="w-full"
              :placeholder="$t('tournament.submission.target_team_ids_placeholder')"
            />
          </div>
          <div class="flex items-center gap-2">
            <Checkbox v-model="form.requiresPayment" :binary="true" input-id="requiresPayment" />
            <label for="requiresPayment" class="text-sm">
              {{ $t('tournament.submission.requires_payment') }}
            </label>
          </div>
        </div>
        <div class="mt-4 flex justify-end gap-2">
          <Button :label="$t('common.cancel')" size="small" text @click="cancelForm" />
          <Button
            :label="$t('common.save')"
            size="small"
            :loading="saving"
            @click="saveRequirement"
          />
        </div>
      </div>

      <PageLoading v-if="loadingReqs" size="32px" />
      <DashboardEmptyState
        v-else-if="requirements.length === 0"
        icon="pi pi-file"
        :message="$t('tournament.submission.empty')"
      />
      <div v-else class="grid gap-3">
        <div
          v-for="req in requirements"
          :key="req.id"
          class="rounded-xl border border-surface-200 bg-surface-0 p-4"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="flex-1">
              <p class="text-sm font-semibold">
                {{ req.formTemplateName ?? `Template #${req.formTemplateId}` }}
              </p>
              <div class="mt-1 flex flex-wrap gap-2 text-xs text-surface-500">
                <span>
                  {{ $t('tournament.submission.target') }}: {{ req.target === 'ALL' ? $t('tournament.submission.target_all') : $t('tournament.submission.target_specific') }}
                </span>
                <span v-if="req.deadline">
                  {{ $t('tournament.submission.deadline') }}: {{ req.deadline }}
                  <span v-if="isDeadlinePassed(req.deadline)" class="ml-1 text-red-500">
                    ({{ $t('tournament.submission.deadline_passed') }})
                  </span>
                </span>
                <span v-if="req.requiresPayment" class="text-amber-600">
                  {{ $t('tournament.submission.payment_required') }}
                </span>
              </div>
            </div>
            <div v-if="isAdminOrDeputy" class="flex shrink-0 gap-1">
              <Button icon="pi pi-pencil" size="small" text rounded @click="openEdit(req)" />
              <Button
                icon="pi pi-trash"
                size="small"
                text
                rounded
                severity="danger"
                @click="confirmDelete(req.id)"
              />
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- ========== 状況ダッシュボードタブ ========== -->
    <template v-if="activeTab === 'dashboard'">
      <div class="mb-4">
        <label class="mb-1 block text-xs font-medium text-surface-500">
          {{ $t('tournament.submission.select_requirement') }}
        </label>
        <Select
          :model-value="selectedReqId"
          :options="requirements"
          option-label="formTemplateName"
          option-value="id"
          :placeholder="$t('tournament.submission.select_requirement_placeholder')"
          class="w-full sm:w-80"
          @update:model-value="(v: number) => { selectedReqId = v; onSelectDashboard(v) }"
        />
      </div>

      <PageLoading v-if="loadingDashboard" size="32px" />
      <template v-else-if="dashboard">
        <SectionCard>
          <template #title>
            {{ dashboard.formTemplateName }}
            <span v-if="dashboard.deadline" class="ml-2 text-xs font-normal text-surface-400">
              ({{ $t('tournament.submission.deadline') }}: {{ dashboard.deadline }})
            </span>
          </template>
          <DataTable :value="dashboard.teamStatuses" class="text-sm" striped-rows>
            <Column field="teamName" :header="$t('tournament.submission.team_name')" />
            <Column :header="$t('tournament.submission.status')">
              <template #body="{ data }">
                <span
                  class="rounded px-2 py-0.5 text-xs font-medium"
                  :class="statusClass(data.status)"
                >
                  {{ statusLabel(data.status) }}
                </span>
              </template>
            </Column>
            <Column :header="$t('tournament.submission.submitted_at')">
              <template #body="{ data }">
                {{ data.submittedAt ?? '-' }}
              </template>
            </Column>
          </DataTable>
          <div
            v-if="dashboard.teamStatuses.length === 0"
            class="py-8 text-center text-sm text-surface-400"
          >
            {{ $t('tournament.submission.no_teams') }}
          </div>
        </SectionCard>
      </template>
      <DashboardEmptyState
        v-else-if="!selectedReqId"
        icon="pi pi-chart-bar"
        :message="$t('tournament.submission.select_requirement_hint')"
      />
    </template>
  </div>
</template>
