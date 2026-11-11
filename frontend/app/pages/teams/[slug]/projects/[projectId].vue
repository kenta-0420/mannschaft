<script setup lang="ts">
import { parseApiError } from '~/utils/apiError'
import { normalizeProjectDetail, toProgressPercent } from '~/utils/projectDetail'
import type {
  ProjectResponse,
  UpdateProjectRequest,
  MilestoneResponse,
  MilestoneCompletionMode,
  CreateMilestoneRequest,
  GatesSummaryResponse,
} from '~/types/project'
import type { TodoResponse } from '~/types/todo'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const projectId = Number(route.params.projectId)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)
const projectApi = useProjectApi()
const notification = useNotification()
const { success: showSuccess, error: showError, warn: showWarn } = notification
const { t } = useI18n()
const confirmDialog = useConfirm()

const project = ref<ProjectResponse | null>(null)
const milestones = ref<MilestoneResponse[]>([])
const todos = ref<TodoResponse[]>([])
const gatesSummary = ref<GatesSummaryResponse | null>(null)
const loading = ref(true)
const showEditDialog = ref(false)
const showMilestoneDialog = ref(false)
const milestoneFieldErrors = ref<Record<string, string>>({})
const editingMilestone = ref<MilestoneResponse | null>(null)
const showForceUnlockDialog = ref(false)
const forceUnlockTarget = ref<MilestoneResponse | null>(null)
const forceUnlockSubmitting = ref(false)
const initializeGateLoading = ref(false)

const editForm = reactive<UpdateProjectRequest>({
  title: '',
  description: '',
  emoji: '',
  color: '',
  dueDate: '',
})

const milestoneForm = reactive<CreateMilestoneRequest>({
  title: '',
  dueDate: '',
  sortOrder: 0,
})

async function load() {
  loading.value = true
  try {
    const [pRes, mRes, tRes, gRes] = await Promise.all([
      projectApi.getProject(teamSlug, projectId),
      projectApi.listMilestones(teamSlug, projectId),
      projectApi.getProjectTodos(teamSlug, projectId),
      projectApi.getGatesSummary(teamSlug, projectId).catch((e) => {
        // ゲート概要は補助情報。取得失敗は null フォールバック（未表示）だが沈黙させずログに残す。
        console.warn('[teams/projects] ゲート概要の取得に失敗（未表示にフォールバック）', e)
        return null
      }),
    ])
    project.value = normalizeProjectDetail(pRes.data)
    milestones.value = mRes.data
    todos.value = tRes.data ?? []
    gatesSummary.value = gRes?.data ?? null
  } catch {
    showError(t('project.error.fetch_failed'))
  } finally {
    loading.value = false
  }
}

function openEdit() {
  if (!project.value) return
  Object.assign(editForm, {
    title: project.value.title,
    emoji: project.value.emoji ?? '',
    color: project.value.color ?? '#3B82F6',
    dueDate: project.value.dueDate ?? '',
  })
  showEditDialog.value = true
}

async function saveProject(form: UpdateProjectRequest) {
  try {
    await projectApi.updateProject(teamSlug, projectId, form)
    showEditDialog.value = false
    await load()
  } catch {
    showError(t('project.error.update_failed'))
  }
}

async function toggleComplete() {
  if (!project.value) return
  try {
    if (project.value.status === 'COMPLETED') {
      await projectApi.reopenProject(teamSlug, projectId)
    } else {
      await projectApi.completeProject(teamSlug, projectId)
    }
    await load()
  } catch {
    showError(t('project.error.status_change_failed'))
  }
}

function openCreateMilestone() {
  editingMilestone.value = null
  milestoneFieldErrors.value = {}
  Object.assign(milestoneForm, { title: '', dueDate: '', sortOrder: milestones.value.length })
  showMilestoneDialog.value = true
}

function openEditMilestone(ms: MilestoneResponse) {
  editingMilestone.value = ms
  milestoneFieldErrors.value = {}
  Object.assign(milestoneForm, {
    title: ms.title,
    dueDate: ms.dueDate ?? '',
    sortOrder: ms.sortOrder,
  })
  showMilestoneDialog.value = true
}

async function saveMilestone(form: CreateMilestoneRequest) {
  try {
    if (editingMilestone.value) {
      await projectApi.updateMilestone(teamSlug, projectId, editingMilestone.value.id, form)
    } else {
      await projectApi.createMilestone(teamSlug, projectId, form)
    }
    showMilestoneDialog.value = false
    await load()
  } catch (error) {
    // 400 の fieldErrors は該当欄に出す。理由が分からないときだけ汎用のトーストを出す。
    milestoneFieldErrors.value = parseApiError(error).fieldErrors
    if (Object.keys(milestoneFieldErrors.value).length === 0) {
      showError(t('project.error.milestone_save_failed'))
    }
  }
}

async function toggleMilestoneComplete(ms: MilestoneResponse) {
  try {
    await projectApi.completeMilestone(teamSlug, projectId, ms.id)
    await load()
  } catch {
    showError(t('project.error.update_failed'))
  }
}

function removeMilestone(ms: MilestoneResponse) {
  confirmDialog.require({
    message: t('project.delete_confirm', { title: ms.title }),
    header: t('project.delete_confirm_header'),
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    acceptLabel: t('button.delete'),
    rejectLabel: t('button.cancel'),
    accept: async () => {
      try {
        await projectApi.deleteMilestone(teamSlug, projectId, ms.id)
        await load()
      } catch {
        showError(t('project.error.delete_failed'))
      }
    },
  })
}

function openForceUnlock(ms: MilestoneResponse) {
  forceUnlockTarget.value = ms
  showForceUnlockDialog.value = true
}

interface ApiErrorData {
  errorCode?: string
  unlockCondition?: string
  message?: string
}
interface ApiError {
  data?: ApiErrorData
}

function extractApiError(err: unknown): ApiErrorData | null {
  if (typeof err === 'object' && err !== null && 'data' in err) {
    const typed = err as ApiError
    return typed.data ?? null
  }
  return null
}

async function handleForceUnlockConfirm(reason: string) {
  if (!forceUnlockTarget.value) return
  forceUnlockSubmitting.value = true
  try {
    await projectApi.forceUnlockMilestone(teamSlug, projectId, forceUnlockTarget.value.id, reason)
    showSuccess(t('project.force_unlock_success'))
    showForceUnlockDialog.value = false
    forceUnlockTarget.value = null
    await load()
  } catch (err) {
    const apiErr = extractApiError(err)
    if (apiErr?.errorCode === 'MILESTONE_LOCKED' && apiErr.unlockCondition) {
      showWarn(apiErr.unlockCondition)
    } else {
      showError(apiErr?.message ?? t('project.error.force_unlock_failed'))
    }
  } finally {
    forceUnlockSubmitting.value = false
  }
}

async function handleChangeCompletionMode(milestoneId: number, mode: MilestoneCompletionMode) {
  try {
    await projectApi.changeCompletionMode(teamSlug, projectId, milestoneId, mode)
    showSuccess(t('project.completion_mode_changed'))
    await load()
  } catch (err) {
    const apiErr = extractApiError(err)
    showError(apiErr?.message ?? t('project.error.completion_mode_failed'))
  }
}

async function handleInitializeGate() {
  if (milestones.value.length === 0) {
    showWarn(t('project.no_milestones'))
    return
  }
  initializeGateLoading.value = true
  try {
    // プロジェクト内の全マイルストーンを sort_order 昇順でゲート初期化
    const ordered = [...milestones.value].sort((a, b) => a.sortOrder - b.sortOrder)
    for (const ms of ordered) {
      await projectApi.initializeGate(teamSlug, projectId, ms.id)
    }
    showSuccess(t('project.initialize_gate_success'))
    await load()
  } catch (err) {
    const apiErr = extractApiError(err)
    showError(apiErr?.message ?? t('project.error.gate_init_failed'))
  } finally {
    initializeGateLoading.value = false
  }
}

onMounted(async () => {
  await loadPermissions()
  await load()
})
</script>

<template>
  <div>
    <PageLoading v-if="loading" size="40px" />

    <div v-else-if="project">
      <div class="mb-6">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-3">
            <span v-if="project.emoji" class="text-3xl">{{ project.emoji }}</span>
            <PageHeader :title="project.title" :back-to="`/teams/${teamSlug}/projects`" :back-label="$t('project.back_label')" />
            <Tag
              :value="project.status === 'COMPLETED' ? $t('project.status.completed') : $t('project.status.active')"
              :severity="project.status === 'COMPLETED' ? 'success' : 'info'"
            />
          </div>
          <div v-if="isAdminOrDeputy" class="flex gap-2">
            <Button icon="pi pi-pencil" :label="$t('project.action.edit')" text @click="openEdit" />
            <Button
              :icon="project.status === 'COMPLETED' ? 'pi pi-refresh' : 'pi pi-check'"
              :label="project.status === 'COMPLETED' ? $t('project.action.reopen') : $t('project.action.complete')"
              :severity="project.status === 'COMPLETED' ? 'info' : 'success'"
              text
              @click="toggleComplete"
            />
          </div>
        </div>
      </div>

      <GateProgressGauge v-if="gatesSummary" :summary="gatesSummary" class="mb-6" />

      <SectionCard class="mb-6">
        <div class="mb-2 grid gap-4 sm:grid-cols-4">
          <div>
            <p class="text-xs text-surface-400">{{ $t('project.stats.progress') }}</p>
            <p class="text-2xl font-bold">{{ toProgressPercent(project.progressRate) }}%</p>
          </div>
          <div>
            <p class="text-xs text-surface-400">{{ $t('project.stats.tasks') }}</p>
            <p class="text-2xl font-bold">{{ project.completedTodos }}/{{ project.totalTodos }}</p>
          </div>
          <div>
            <p class="text-xs text-surface-400">{{ $t('project.stats.milestones') }}</p>
            <p class="text-2xl font-bold">
              {{ project.milestones.completed }}/{{ project.milestones.total }}
            </p>
          </div>
          <div>
            <p class="text-xs text-surface-400">{{ $t('project.stats.due_date') }}</p>
            <p
              class="text-2xl font-bold"
              :class="(project.daysRemaining ?? 0) < 0 ? 'text-red-500' : ''"
            >
              {{ project.dueDate ?? $t('project.stats.no_due_date') }}
            </p>
          </div>
        </div>
        <ProgressBar
          :value="toProgressPercent(project.progressRate)"
          :show-value="false"
          style="height: 8px"
        />
      </SectionCard>

      <div v-if="isAdmin && milestones.length > 0" class="mb-3 flex justify-end">
        <InitializeGateButton
          :loading="initializeGateLoading"
          @confirm="handleInitializeGate"
        />
      </div>

      <ProjectMilestoneList
        :milestones="milestones"
        :todos="todos"
        :team-id="teamSlug"
        :project-id="projectId"
        :can-edit="isAdminOrDeputy"
        :can-force-unlock="isAdmin"
        @create="openCreateMilestone"
        @edit="openEditMilestone"
        @toggle-complete="toggleMilestoneComplete"
        @remove="removeMilestone"
        @force-unlock="openForceUnlock"
        @change-completion-mode="handleChangeCompletionMode"
        @todos-reordered="load"
      />

      <div>
        <h2 class="mb-2 text-lg font-semibold">{{ $t('project.related_tasks') }}</h2>
        <div v-if="todos.length === 0" class="py-4 text-center text-surface-400">
          {{ $t('project.no_related_tasks') }}
        </div>
        <div v-else class="flex flex-col gap-1">
          <ProjectRelatedTodoRow
            v-for="todo in todos"
            :key="todo.id"
            :todo="todo"
            :todo-href="`/teams/${teamSlug}/todos/${todo.id}`"
          />
        </div>
      </div>
    </div>

    <ProjectEditDialog
      v-model:visible="showEditDialog"
      :form="editForm"
      @save="saveProject"
    />

    <ProjectMilestoneDialog
      v-model:visible="showMilestoneDialog"
      :form="milestoneForm"
      :field-errors="milestoneFieldErrors"
      :editing="editingMilestone"
      @save="saveMilestone"
    />

    <ForceUnlockDialog
      v-model:visible="showForceUnlockDialog"
      :milestone="forceUnlockTarget"
      :submitting="forceUnlockSubmitting"
      @confirm="handleForceUnlockConfirm"
      @cancel="forceUnlockTarget = null"
    />
  </div>
</template>
