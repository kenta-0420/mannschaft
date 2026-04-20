<script setup lang="ts">
import type {
  ProjectResponse,
  UpdateProjectRequest,
  MilestoneResponse,
  CreateMilestoneRequest,
  GatesSummaryResponse,
  MilestoneCompletionMode,
} from '~/types/project'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const projectId = Number(route.params.projectId)
const projectApi = useProjectApi()
const { showError, showSuccess } = useNotification()
const authStore = useAuthStore()
const { t } = useI18n()

const project = ref<ProjectResponse | null>(null)
const milestones = ref<MilestoneResponse[]>([])
const todos = ref<unknown[]>([])
const gatesSummary = ref<GatesSummaryResponse | null>(null)
const loading = ref(true)
const showEditDialog = ref(false)
const showMilestoneDialog = ref(false)
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
      projectApi.getGatesSummary(null, projectId).catch(() => null),
    ])
    project.value = pRes.data
    milestones.value = mRes.data
    todos.value = tRes.data
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
    await projectApi.updateProject(null, projectId, editForm)
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
      await projectApi.reopenProject(null, projectId)
    } else {
      await projectApi.completeProject(null, projectId)
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
      await projectApi.updateMilestone(null, projectId, editingMilestone.value.id, milestoneForm)
    } else {
      await projectApi.createMilestone(null, projectId, milestoneForm)
    }
    showMilestoneDialog.value = false
    await load()
  } catch {
    showError('マイルストーンの保存に失敗しました')
  }
}

async function toggleMilestoneComplete(ms: MilestoneResponse) {
  try {
    await projectApi.completeMilestone(null, projectId, ms.id)
    await load()
  } catch {
    showError('更新に失敗しました')
  }
}

async function removeMilestone(ms: MilestoneResponse) {
  if (!confirm(`「${ms.title}」を削除しますか？`)) return
  try {
    await projectApi.deleteMilestone(null, projectId, ms.id)
    await load()
  } catch {
    showError('削除に失敗しました')
  }
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
    showError(t('project.force_unlock_title') + ' に失敗しました')
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
    showError(t('project.completion_mode_changed') + ' に失敗しました')
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
    showError(t('project.initialize_gate_button') + ' に失敗しました')
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
        <BackButton to="/my/projects" :label="$t('project.my_projects')" />
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span v-if="project.emoji" class="text-3xl">{{ project.emoji }}</span>
            <PageHeader :title="project.title" />
            <Tag
              :value="project.status === 'COMPLETED' ? '完了' : '進行中'"
              :severity="project.status === 'COMPLETED' ? 'success' : 'info'"
            />
          </div>
          <div v-if="isCreator" class="flex gap-2">
            <Button icon="pi pi-pencil" label="編集" text @click="openEdit" />
            <Button
              :icon="project.status === 'COMPLETED' ? 'pi pi-refresh' : 'pi pi-check'"
              :label="project.status === 'COMPLETED' ? '再開' : '完了にする'"
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
      :visible="showForceUnlockDialog"
      :milestone="forceUnlockTarget"
      :submitting="forceUnlockSubmitting"
      @update:visible="showForceUnlockDialog = $event"
      @confirm="submitForceUnlock"
      @cancel="forceUnlockTarget = null"
    />
  </div>
</template>
