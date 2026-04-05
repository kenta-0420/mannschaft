<script setup lang="ts">
import type {
  ProjectResponse,
  UpdateProjectRequest,
  MilestoneResponse,
  CreateMilestoneRequest,
} from '~/types/project'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const teamId = Number(route.params.id)
const projectId = Number(route.params.projectId)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)
const projectApi = useProjectApi()
const { showError } = useNotification()

const project = ref<ProjectResponse | null>(null)
const milestones = ref<MilestoneResponse[]>([])
const todos = ref<unknown[]>([])
const loading = ref(true)
const showEditDialog = ref(false)
const showMilestoneDialog = ref(false)
const editingMilestone = ref<MilestoneResponse | null>(null)

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
    const [pRes, mRes, tRes] = await Promise.all([
      projectApi.getProject(teamId, projectId),
      projectApi.listMilestones(teamId, projectId),
      projectApi.getProjectTodos(teamId, projectId),
    ])
    project.value = pRes.data
    milestones.value = mRes.data
    todos.value = tRes.data
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
        <Button
          icon="pi pi-arrow-left"
          text
          label="プロジェクト一覧"
          class="mb-2"
          @click="router.push(`/teams/${teamId}/projects`)"
        />
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span v-if="project.emoji" class="text-3xl">{{ project.emoji }}</span>
            <h1 class="text-2xl font-bold">{{ project.title }}</h1>
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

      <div
        class="mb-6 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
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
      </div>

      <ProjectMilestoneList
        :milestones="milestones"
        :can-edit="isAdminOrDeputy"
        @create="openCreateMilestone"
        @edit="openEditMilestone"
        @toggle-complete="toggleMilestoneComplete"
        @remove="removeMilestone"
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
  </div>
</template>
