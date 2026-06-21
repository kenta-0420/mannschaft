<script setup lang="ts">
import type {
  ProjectResponse,
  UpdateProjectRequest,
  MilestoneResponse,
  CreateMilestoneRequest,
} from '~/types/project'
import type { TodoResponse } from '~/types/todo'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
const projectId = Number(route.params.projectId)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const projectApi = useProjectApi()
const notification = useNotification()
const { success: showSuccess, error: showError } = notification
const { t } = useI18n()

const project = ref<ProjectResponse | null>(null)
const milestones = ref<MilestoneResponse[]>([])
const todos = ref<TodoResponse[]>([])
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
      projectApi.getOrgProject(orgSlug, projectId),
      projectApi.listOrgMilestones(orgSlug, projectId),
      projectApi.getOrgProjectTodos(orgSlug, projectId),
    ])
    project.value = pRes.data
    milestones.value = mRes.data
    todos.value = (tRes.data ?? []) as TodoResponse[]
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

async function saveProject() {
  try {
    await projectApi.updateOrgProject(orgSlug, projectId, editForm)
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
      await projectApi.reopenOrgProject(orgSlug, projectId)
    } else {
      await projectApi.completeOrgProject(orgSlug, projectId)
    }
    await load()
  } catch {
    showError(t('project.error.status_change_failed'))
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
      await projectApi.updateOrgMilestone(orgSlug, projectId, editingMilestone.value.id, milestoneForm)
    } else {
      await projectApi.createOrgMilestone(orgSlug, projectId, milestoneForm)
    }
    showMilestoneDialog.value = false
    await load()
  } catch {
    showError(t('project.error.milestone_save_failed'))
  }
}

async function toggleMilestoneComplete(ms: MilestoneResponse) {
  try {
    await projectApi.completeOrgMilestone(orgSlug, projectId, ms.id)
    await load()
  } catch {
    showError(t('project.error.update_failed'))
  }
}

async function removeMilestone(ms: MilestoneResponse) {
  if (!confirm(`「${ms.title}」を削除しますか？`)) return
  try {
    await projectApi.deleteOrgMilestone(orgSlug, projectId, ms.id)
    await load()
  } catch {
    showError(t('project.error.delete_failed'))
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
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span v-if="project.emoji" class="text-3xl">{{ project.emoji }}</span>
            <PageHeader
              :title="project.title"
              :back-to="`/organizations/${orgSlug}/projects`"
              :back-label="$t('project.org_page.back_label')"
            />
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

      <SectionCard class="mb-6">
        <div class="mb-2 grid gap-4 sm:grid-cols-4">
          <div>
            <p class="text-xs text-surface-400">{{ $t('project.stats.progress') }}</p>
            <p class="text-2xl font-bold">{{ Math.round(project.progressRate * 100) }}%</p>
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
          :value="Math.round(project.progressRate * 100)"
          :show-value="false"
          style="height: 8px"
        />
      </SectionCard>

      <ProjectMilestoneList
        :milestones="milestones"
        :todos="todos"
        :team-id="orgSlug"
        :project-id="projectId"
        :can-edit="isAdminOrDeputy"
        :can-force-unlock="false"
        @create="openCreateMilestone"
        @edit="openEditMilestone"
        @toggle-complete="toggleMilestoneComplete"
        @remove="removeMilestone"
        @todos-reordered="load"
      />

      <div>
        <h2 class="mb-2 text-lg font-semibold">{{ $t('project.related_tasks') }}</h2>
        <div v-if="todos.length === 0" class="py-4 text-center text-surface-400">
          {{ $t('project.no_related_tasks') }}
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
