<script setup lang="ts">
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
const teamId = Number(route.params.id)
const projectId = Number(route.params.projectId)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)
const projectApi = useProjectApi()
const notification = useNotification()
const { success: showSuccess, error: showError, warn: showWarn } = notification
const { t } = useI18n()

const project = ref<ProjectResponse | null>(null)
const milestones = ref<MilestoneResponse[]>([])
const todos = ref<TodoResponse[]>([])
const gatesSummary = ref<GatesSummaryResponse | null>(null)
const loading = ref(true)
const showEditDialog = ref(false)
const showMilestoneDialog = ref(false)
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
      projectApi.getProject(teamId, projectId),
      projectApi.listMilestones(teamId, projectId),
      projectApi.getProjectTodos(teamId, projectId),
      projectApi.getGatesSummary(teamId, projectId).catch(() => null),
    ])
    project.value = pRes.data
    milestones.value = mRes.data
    todos.value = (tRes.data ?? []) as TodoResponse[]
    gatesSummary.value = gRes?.data ?? null
  } catch {
    showError('プロジェクト情報の取得に失敗しました')
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

async function saveProject() {
  try {
    await projectApi.updateProject(teamId, projectId, editForm)
    showEditDialog.value = false
    await load()
  } catch {
    showError('更新に失敗しました')
  }
}

async function toggleComplete() {
  if (!project.value) return
  try {
    if (project.value.status === 'COMPLETED') {
      await projectApi.reopenProject(teamId, projectId)
    } else {
      await projectApi.completeProject(teamId, projectId)
    }
    await load()
  } catch {
    showError('ステータスの変更に失敗しました')
  }
}

function openCreateMilestone() {
  editingMilestone.value = null
  Object.assign(milestoneForm, { title: '', dueDate: '', sortOrder: milestones.value.length })
  showMilestoneDialog.value = true
}

function openEditMilestone(ms: MilestoneResponse) {
  editingMilestone.value = ms
  Object.assign(milestoneForm, {
    title: ms.title,
    dueDate: ms.dueDate ?? '',
    sortOrder: ms.sortOrder,
  })
  showMilestoneDialog.value = true
}

async function saveMilestone() {
  try {
    if (editingMilestone.value) {
      await projectApi.updateMilestone(teamId, projectId, editingMilestone.value.id, milestoneForm)
    } else {
      await projectApi.createMilestone(teamId, projectId, milestoneForm)
    }
    showMilestoneDialog.value = false
    await load()
  } catch {
    showError('マイルストーンの保存に失敗しました')
  }
}

async function toggleMilestoneComplete(ms: MilestoneResponse) {
  try {
    await projectApi.completeMilestone(teamId, projectId, ms.id)
    await load()
  } catch {
    showError('更新に失敗しました')
  }
}

async function removeMilestone(ms: MilestoneResponse) {
  if (!confirm(`「${ms.title}」を削除しますか？`)) return
  try {
    await projectApi.deleteMilestone(teamId, projectId, ms.id)
    await load()
  } catch {
    showError('削除に失敗しました')
  }
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
    await projectApi.forceUnlockMilestone(teamId, projectId, forceUnlockTarget.value.id, reason)
    showSuccess(t('project.force_unlock_success'))
    showForceUnlockDialog.value = false
    forceUnlockTarget.value = null
    await load()
  } catch (err) {
    const apiErr = extractApiError(err)
    if (apiErr?.errorCode === 'MILESTONE_LOCKED' && apiErr.unlockCondition) {
      showWarn(apiErr.unlockCondition)
    } else {
      showError(apiErr?.message ?? '強制アンロックに失敗しました')
    }
  } finally {
    forceUnlockSubmitting.value = false
  }
}

async function handleChangeCompletionMode(milestoneId: number, mode: MilestoneCompletionMode) {
  try {
    await projectApi.changeCompletionMode(teamId, projectId, milestoneId, mode)
    showSuccess(t('project.completion_mode_changed'))
    await load()
  } catch (err) {
    const apiErr = extractApiError(err)
    showError(apiErr?.message ?? '完了モードの変更に失敗しました')
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
      await projectApi.initializeGate(teamId, projectId, ms.id)
    }
    showSuccess(t('project.initialize_gate_success'))
    await load()
  } catch (err) {
    const apiErr = extractApiError(err)
    showError(apiErr?.message ?? 'ゲートの初期化に失敗しました')
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
        <BackButton :to="`/teams/${teamId}/projects`" label="プロジェクト一覧" />
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span v-if="project.emoji" class="text-3xl">{{ project.emoji }}</span>
            <PageHeader :title="project.title" />
            <Tag
              :value="project.status === 'COMPLETED' ? '完了' : '進行中'"
              :severity="project.status === 'COMPLETED' ? 'success' : 'info'"
            />
          </div>
          <div v-if="isAdminOrDeputy" class="flex gap-2">
            <Button icon="pi pi-pencil" label="編集" text @click="openEdit" />
            <Button
              :icon="project.status === 'COMPLETED' ? 'pi pi-refresh' : 'pi pi-check'"
              :label="project.status === 'COMPLETED' ? '再開' : '完了にする'"
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
            <p class="text-xs text-surface-400">進捗</p>
            <p class="text-2xl font-bold">{{ Math.round(project.progressRate * 100) }}%</p>
          </div>
          <div>
            <p class="text-xs text-surface-400">タスク</p>
            <p class="text-2xl font-bold">{{ project.completedTodos }}/{{ project.totalTodos }}</p>
          </div>
          <div>
            <p class="text-xs text-surface-400">マイルストーン</p>
            <p class="text-2xl font-bold">
              {{ project.milestones.completed }}/{{ project.milestones.total }}
            </p>
          </div>
          <div>
            <p class="text-xs text-surface-400">期限</p>
            <p
              class="text-2xl font-bold"
              :class="(project.daysRemaining ?? 0) < 0 ? 'text-red-500' : ''"
            >
              {{ project.dueDate ?? '未設定' }}
            </p>
          </div>
        </div>
        <ProgressBar
          :value="Math.round(project.progressRate * 100)"
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
        :team-id="teamId"
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
        <h2 class="mb-2 text-lg font-semibold">関連タスク</h2>
        <div v-if="todos.length === 0" class="py-4 text-center text-surface-400">
          このプロジェクトに関連するタスクはありません
        </div>
        <div v-else class="flex flex-col gap-1">
          <div
            v-for="(todo, idx) in todos"
            :key="idx"
            class="rounded-lg border border-surface-100 p-3 dark:border-surface-600"
          >
            <pre class="text-sm">{{ JSON.stringify(todo, null, 2) }}</pre>
          </div>
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
