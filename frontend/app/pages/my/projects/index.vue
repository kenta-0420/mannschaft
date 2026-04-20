<script setup lang="ts">
import type { ProjectResponse, CreateProjectRequest } from '~/types/project'

definePageMeta({ middleware: 'auth' })

const router = useRouter()
const projectApi = useProjectApi()
const { showError } = useNotification()

const projects = ref<ProjectResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)

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
    // teamId = null で個人スコープ (`/api/v1/users/me/projects`) を取得
    const res = await projectApi.listProjects(null)
    projects.value = res.data
  } catch {
    showError('プロジェクトの取得に失敗しました')
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
    await projectApi.createProject(null, form)
    showDialog.value = false
    await load()
  } catch {
    showError('プロジェクトの作成に失敗しました')
  }
}

function openProject(project: ProjectResponse) {
  router.push(`/my/projects/${project.id}`)
}

async function remove(project: ProjectResponse) {
  if (!confirm(`「${project.title}」を削除しますか？`)) return
  try {
    await projectApi.deleteProject(null, project.id)
    await load()
  } catch {
    showError('削除に失敗しました')
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
      return '進行中'
    case 'COMPLETED':
      return '完了'
    case 'ARCHIVED':
      return 'アーカイブ'
    default:
      return status
  }
}

onMounted(async () => {
  await load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <div>
        <PageHeader :title="$t('project.my_projects')" />
        <p class="text-sm text-surface-500">{{ $t('project.my_projects_description') }}</p>
      </div>
      <Button label="プロジェクト作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <SectionCard
        v-for="project in projects"
        :key="project.id"
        class="cursor-pointer transition-shadow hover:shadow-md"
        :data-testid="`my-project-card-${project.id}`"
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
            <span>{{ project.completedTodos }}/{{ project.totalTodos }} タスク</span>
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
              (あと{{ project.daysRemaining }}日)
            </span>
            <span
              v-else-if="project.daysRemaining !== null && project.daysRemaining < 0"
              class="text-xs text-red-500"
            >
              ({{ Math.abs(project.daysRemaining) }}日超過)
            </span>
          </div>
          <Button
            icon="pi pi-trash"
            text
            rounded
            size="small"
            severity="danger"
            :data-testid="`my-project-remove-${project.id}`"
            @click.stop="remove(project)"
          />
        </div>
      </SectionCard>

      <DashboardEmptyState
        v-if="projects.length === 0"
        icon="pi pi-briefcase"
        :message="$t('project.no_my_projects')"
        class="col-span-full"
      />
    </div>

    <!-- 作成ダイアログ -->
    <Dialog v-model:visible="showDialog" header="プロジェクト作成" modal class="w-full max-w-lg">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">タイトル</label>
          <InputText
            v-model="form.title"
            class="w-full"
            placeholder="プロジェクト名"
            data-testid="my-project-create-title"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="form.description" class="w-full" rows="3" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">絵文字</label>
            <InputText v-model="form.emoji" class="w-full" placeholder="例: 📚" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">カラー</label>
            <InputText v-model="form.color" type="color" class="w-full" />
          </div>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">期限</label>
          <InputText v-model="form.dueDate" type="date" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showDialog = false" />
        <Button
          label="作成"
          data-testid="my-project-create-submit"
          @click="createProject"
        />
      </template>
    </Dialog>
  </div>
</template>
