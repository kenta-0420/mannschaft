<script setup lang="ts">
import { parseApiError } from '~/utils/apiError'
import { normalizeProjectDetail, toProgressPercent } from '~/utils/projectDetail'
import type {
  ProjectResponse,
  UpdateProjectRequest,
  MilestoneResponse,
  CreateMilestoneRequest,
  GatesSummaryResponse,
  MilestoneCompletionMode,
} from '~/types/project'
import type { TodoResponse } from '~/types/todo'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const projectId = Number(route.params.projectId)
const projectApi = useProjectApi()
const { showError, showSuccess } = useNotification()
const authStore = useAuthStore()
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

// 強制アンロックダイアログ制御
const showForceUnlockDialog = ref(false)
const forceUnlockTarget = ref<MilestoneResponse | null>(null)
const forceUnlockSubmitting = ref(false)

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

// 作成者 = 自分かどうか（個人プロジェクトでは常に true のはずだが、
// 権限チェックのエッジケース（共有閲覧等）に備えて厳密に判定する）
const isCreator = computed(() => {
  const uid = authStore.currentUser?.id
  if (!uid || !project.value) return false
  return project.value.createdBy.id === uid
})

async function load() {
  loading.value = true
  try {
    // 個人スコープ (teamId = null)
    const [pRes, mRes, tRes, gRes] = await Promise.all([
      projectApi.getProject(null, projectId),
      projectApi.listMilestones(null, projectId),
      projectApi.getProjectTodos(null, projectId),
      projectApi.getGatesSummary(null, projectId).catch((e) => {
        // ゲート概要は補助情報。取得失敗は null フォールバック（未表示）だが沈黙させずログに残す。
        console.warn('[my/projects] ゲート概要の取得に失敗（未表示にフォールバック）', e)
        return null
      }),
    ])
    project.value = normalizeProjectDetail(pRes.data)
    milestones.value = mRes.data
    todos.value = tRes.data
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
    await projectApi.updateProject(null, projectId, form)
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
      await projectApi.reopenProject(null, projectId)
    } else {
      await projectApi.completeProject(null, projectId)
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
      await projectApi.updateMilestone(null, projectId, editingMilestone.value.id, form)
    } else {
      await projectApi.createMilestone(null, projectId, form)
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
    await projectApi.completeMilestone(null, projectId, ms.id)
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
        await projectApi.deleteMilestone(null, projectId, ms.id)
        await load()
      } catch {
        showError(t('project.error.delete_failed'))
      }
    },
  })
}

// === F02.7 ゲート機能（個人スコープでは作成者のみ操作可）===

function openForceUnlock(ms: MilestoneResponse) {
  forceUnlockTarget.value = ms
  showForceUnlockDialog.value = true
}

async function submitForceUnlock(reason: string) {
  if (!forceUnlockTarget.value) return
  forceUnlockSubmitting.value = true
  try {
    await projectApi.forceUnlockMilestone(null, projectId, forceUnlockTarget.value.id, reason)
    showForceUnlockDialog.value = false
    forceUnlockTarget.value = null
    showSuccess(t('project.force_unlock_success'))
    await load()
  } catch {
    showError(t('project.error.force_unlock_failed'))
  } finally {
    forceUnlockSubmitting.value = false
  }
}

async function handleChangeCompletionMode(milestoneId: number, mode: MilestoneCompletionMode) {
  try {
    await projectApi.changeCompletionMode(null, projectId, milestoneId, mode)
    showSuccess(t('project.completion_mode_changed'))
    await load()
  } catch {
    showError(t('project.error.completion_mode_failed'))
  }
}

async function handleInitializeGate() {
  if (milestones.value.length === 0) return
  try {
    // 全マイルストーンに対して初期化 API を発行（バックエンドは冪等）
    await Promise.all(
      milestones.value.map((ms) => projectApi.initializeGate(null, projectId, ms.id)),
    )
    showSuccess(t('project.initialize_gate_success'))
    await load()
  } catch {
    showError(t('project.error.gate_init_failed'))
  }
}

onMounted(async () => {
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
            <PageHeader :title="project.title" back-to="/my/projects" :back-label="$t('project.my_projects')" />
            <Tag
              :value="project.status === 'COMPLETED' ? $t('project.status.completed') : $t('project.status.active')"
              :severity="project.status === 'COMPLETED' ? 'success' : 'info'"
            />
          </div>
          <div v-if="isCreator" class="flex gap-2">
            <Button icon="pi pi-pencil" :label="$t('project.action.edit')" text @click="openEdit" />
            <Button
              :icon="project.status === 'COMPLETED' ? 'pi pi-refresh' : 'pi pi-check'"
              :label="project.status === 'COMPLETED' ? $t('project.action.reopen') : $t('project.action.complete')"
              :severity="project.status === 'COMPLETED' ? 'info' : 'success'"
              text
              @click="toggleComplete"
            />
            <InitializeGateButton
              v-if="milestones.length > 0"
              @confirm="handleInitializeGate"
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

      <!--
        canEdit / canForceUnlock はどちらも作成者（自分）のみ許可。
        個人プロジェクトでは作成者 = PERSONAL スコープ所有者のため、
        他人が閲覧している状況（共有URL等のエッジケース）では一律非表示になる。
      -->
      <ProjectMilestoneList
        :milestones="milestones"
        :can-edit="isCreator"
        :can-force-unlock="isCreator"
        @create="openCreateMilestone"
        @edit="openEditMilestone"
        @toggle-complete="toggleMilestoneComplete"
        @remove="removeMilestone"
        @force-unlock="openForceUnlock"
        @change-completion-mode="handleChangeCompletionMode"
      />

      <div>
        <h2 class="mb-2 text-lg font-semibold">{{ $t('project.related_tasks') }}</h2>
        <div v-if="todos.length === 0" class="py-4 text-center text-surface-400">
          {{ $t('project.no_related_tasks') }}
        </div>
        <div v-else class="flex flex-col gap-1">
          <ProjectRelatedTodoRow v-for="todo in todos" :key="todo.id" :todo="todo" />
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
      :visible="showForceUnlockDialog"
      :milestone="forceUnlockTarget"
      :submitting="forceUnlockSubmitting"
      @update:visible="showForceUnlockDialog = $event"
      @confirm="submitForceUnlock"
      @cancel="forceUnlockTarget = null"
    />
  </div>
</template>
