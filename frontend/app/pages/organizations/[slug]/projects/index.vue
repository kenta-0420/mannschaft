<script setup lang="ts">
import type { ProjectResponse, CreateProjectRequest } from '~/types/project'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const orgSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const projectApi = useProjectApi()
const { showError } = useNotification()
const { t } = useI18n()

const projects = ref<ProjectResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const showGuide = ref(false)

const form = reactive<CreateProjectRequest>({
  title: '',
  description: '',
  emoji: '',
  color: '#3B82F6',
  dueDate: '',
})

async function load() {
  loading.value = true
  try {
    const res = await projectApi.listOrgProjects(orgSlug)
    projects.value = res.data
  } catch {
    showError(t('project.error.fetch_failed'))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { title: '', description: '', emoji: '', color: '#3B82F6', dueDate: '' })
  showDialog.value = true
}

async function createProject() {
  try {
    await projectApi.createOrgProject(orgSlug, form)
    showDialog.value = false
    await load()
  } catch {
    showError(t('project.error.create_failed'))
  }
}

function openProject(project: ProjectResponse) {
  router.push(`/organizations/${orgSlug}/projects/${project.id}`)
}

async function remove(project: ProjectResponse) {
  if (!confirm(`「${project.title}」を削除しますか？`)) return
  try {
    await projectApi.deleteOrgProject(orgSlug, project.id)
    await load()
  } catch {
    showError(t('project.error.delete_failed'))
  }
}

function statusSeverity(status: string) {
  switch (status) {
    case 'ACTIVE':
      return 'info'
    case 'COMPLETED':
      return 'success'
    case 'ARCHIVED':
      return 'secondary'
    default:
      return 'info'
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'ACTIVE':
      return $t('project.status.active')
    case 'COMPLETED':
      return $t('project.status.completed')
    case 'ARCHIVED':
      return $t('project.status.archived')
    default:
      return status
  }
}

const { t: $t } = useI18n()

onMounted(async () => {
  await loadPermissions()
  await load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="$t('project.org_page.title')" />
      <div class="flex items-center gap-2">
        <Button
          icon="pi pi-question-circle"
          :label="$t('project.guide.button')"
          text
          data-testid="project-help-link"
          @click="showGuide = true"
        />
        <Button
          v-if="isAdminOrDeputy"
          :label="$t('project.create_button')"
          icon="pi pi-plus"
          @click="openCreate"
        />
      </div>
    </div>

    <PageLoading v-if="loading" size="40px" />

    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <SectionCard
        v-for="project in projects"
        :key="project.id"
        class="cursor-pointer transition-shadow hover:shadow-md"
        @click="openProject(project)"
      >
        <div class="mb-2 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span v-if="project.emoji" class="text-xl">{{ project.emoji }}</span>
            <h3 class="font-semibold">{{ project.title }}</h3>
          </div>
          <Tag :value="statusLabel(project.status)" :severity="statusSeverity(project.status)" />
        </div>

        <!-- 進捗バー -->
        <div class="mb-2">
          <div class="mb-1 flex justify-between text-xs text-surface-500">
            <span>{{ project.completedTodos }}/{{ project.totalTodos }} {{ $t('project.task_unit') }}</span>
            <span>{{ Math.round(project.progressRate * 100) }}%</span>
          </div>
          <ProgressBar
            :value="Math.round(project.progressRate * 100)"
            :show-value="false"
            style="height: 6px"
          />
        </div>

        <div class="flex items-center justify-between text-sm text-surface-500">
          <div class="flex items-center gap-2">
            <span v-if="project.dueDate">
              <i class="pi pi-calendar mr-1" />{{ project.dueDate }}
            </span>
            <span
              v-if="project.daysRemaining !== null && project.daysRemaining >= 0"
              class="text-xs"
            >
              ({{ $t('project.days_remaining', { n: project.daysRemaining }) }})
            </span>
            <span
              v-else-if="project.daysRemaining !== null && project.daysRemaining < 0"
              class="text-xs text-red-500"
            >
              ({{ $t('project.days_overdue', { n: Math.abs(project.daysRemaining) }) }})
            </span>
          </div>
          <Button
            v-if="isAdminOrDeputy"
            icon="pi pi-trash"
            text
            rounded
            size="small"
            severity="danger"
            @click.stop="remove(project)"
          />
        </div>
      </SectionCard>

      <DashboardEmptyState
        v-if="projects.length === 0"
        icon="pi pi-briefcase"
        :message="$t('project.org_page.empty')"
        class="col-span-full"
      />
    </div>

    <!-- 作成ダイアログ -->
    <Dialog v-model:visible="showDialog" :header="$t('project.dialog.create_title')" modal class="w-full max-w-lg">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('project.dialog.title_label') }}</label>
          <InputText v-model="form.title" class="w-full" :placeholder="$t('project.dialog.title_placeholder')" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('project.dialog.description_label') }}</label>
          <Textarea v-model="form.description" class="w-full" rows="3" />
        </div>
        <ProjectAppearanceFields
          v-model:emoji="form.emoji"
          v-model:color="form.color"
        />
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('project.dialog.due_date_label') }}</label>
          <InputText v-model="form.dueDate" type="date" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button :label="$t('project.dialog.cancel')" text @click="showDialog = false" />
        <Button :label="$t('project.dialog.create')" @click="createProject" />
      </template>
    </Dialog>

    <!-- 使い方説明モーダル -->
    <ProjectGuideModal v-model:visible="showGuide" />
  </div>
</template>
