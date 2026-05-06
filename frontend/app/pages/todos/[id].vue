<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const todoId = Number(route.params.id)
const todoApi = useTodoApi()
const notification = useNotification()

interface TodoDetail {
  id: number
  title: string
  description: string | null
  status: string
  priority: string
  dueDate: string | null
  startDate: string | null
  daysRemaining: number | null
  completedAt: string | null
  completedBy: { id: number; displayName: string } | null
  createdBy: { id: number; displayName: string }
  assignees: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
  createdAt: string
  updatedAt: string
}

const todo = ref<TodoDetail | null>(null)
const loading = ref(true)
const editing = ref(false)
const saving = ref(false)

const editForm = ref({
  title: '',
  description: '',
  priority: 'MEDIUM',
  startDate: null as Date | null,
  dueDate: null as Date | null,
})

async function loadTodo() {
  loading.value = true
  try {
    const res = await todoApi.getPersonalTodo(todoId)
    todo.value = res.data as unknown as TodoDetail
  }
  catch {
    notification.error('TODOの取得に失敗しました')
    router.replace('/todos')
  }
  finally {
    loading.value = false
  }
}

function startEdit() {
  if (!todo.value) return
  editForm.value = {
    title: todo.value.title,
    description: todo.value.description ?? '',
    priority: todo.value.priority,
    startDate: todo.value.startDate ? new Date(todo.value.startDate) : null,
    dueDate: todo.value.dueDate ? new Date(todo.value.dueDate) : null,
  }
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

async function saveEdit() {
  if (!editForm.value.title.trim()) return
  saving.value = true
  try {
    await todoApi.updatePersonalTodo(todoId, {
      title: editForm.value.title.trim(),
      description: editForm.value.description.trim() || null,
      priority: editForm.value.priority,
      startDate: editForm.value.startDate ? editForm.value.startDate.toISOString().slice(0, 10) : null,
      dueDate: editForm.value.dueDate ? editForm.value.dueDate.toISOString().slice(0, 10) : null,
    })
    notification.success('更新しました')
    editing.value = false
    await loadTodo()
  }
  catch {
    notification.error('更新に失敗しました')
  }
  finally {
    saving.value = false
  }
}

const statusConfig: Record<string, { label: string; severity: string }> = {
  OPEN: { label: '未着手', severity: 'secondary' },
  IN_PROGRESS: { label: '進行中', severity: 'info' },
  COMPLETED: { label: '完了', severity: 'success' },
  CANCELLED: { label: 'キャンセル', severity: 'danger' },
}

const priorityOptions = [
  { label: '高', value: 'HIGH' },
  { label: '中', value: 'MEDIUM' },
  { label: '低', value: 'LOW' },
]

const priorityConfig: Record<string, { label: string; severity: string }> = {
  HIGH: { label: '高', severity: 'danger' },
  MEDIUM: { label: '中', severity: 'warn' },
  LOW: { label: '低', severity: 'success' },
}

function formatDate(d: string | null) {
  if (!d) return null
  return new Date(d).toLocaleDateString('ja-JP', { year: 'numeric', month: 'long', day: 'numeric' })
}

const isOverdue = computed(() =>
  todo.value?.dueDate && new Date(todo.value.dueDate) < new Date() && todo.value.status !== 'COMPLETED',
)

onMounted(loadTodo)
</script>

<template>
  <div>
    <div class="mb-5 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.push('/todos')" />
      <h1 class="text-2xl font-bold">TODO詳細</h1>
    </div>

    <PageLoading v-if="loading" />

    <SectionCard v-else-if="todo" class="max-w-2xl">
      <!-- 閲覧モード -->
      <template v-if="!editing">
        <div class="space-y-4">
          <div class="flex flex-wrap items-start justify-between gap-3">
            <h2 class="text-xl font-bold">{{ todo.title }}</h2>
            <div class="flex gap-2">
              <Tag
                :value="priorityConfig[todo.priority]?.label ?? todo.priority"
                :severity="priorityConfig[todo.priority]?.severity ?? 'secondary'"
                rounded
              />
              <Tag
                :value="statusConfig[todo.status]?.label ?? todo.status"
                :severity="statusConfig[todo.status]?.severity ?? 'secondary'"
                rounded
              />
              <Button icon="pi pi-pencil" text rounded size="small" @click="startEdit" />
            </div>
          </div>

          <div class="space-y-1.5 text-sm">
            <div v-if="todo.startDate" class="flex items-center gap-2">
              <i class="pi pi-calendar-plus text-surface-400" />
              <span>開始: {{ formatDate(todo.startDate) }}</span>
            </div>
            <div v-if="todo.dueDate" class="flex items-center gap-2">
              <i class="pi pi-calendar text-surface-400" />
              <span :class="isOverdue ? 'font-semibold text-red-600' : ''">
                期限: {{ formatDate(todo.dueDate) }}
                <span v-if="isOverdue" class="text-red-500">（期限切れ）</span>
                <span v-else-if="todo.daysRemaining !== null" class="text-surface-400">（あと{{ todo.daysRemaining }}日）</span>
              </span>
            </div>
            <div v-if="todo.assignees?.length" class="flex items-center gap-2">
              <i class="pi pi-users text-surface-400" />
              <span>{{ todo.assignees.map((a) => a.displayName).join(', ') }}</span>
            </div>
            <div v-if="todo.createdBy" class="flex items-center gap-2">
              <i class="pi pi-user text-surface-400" />
              <span>作成: {{ todo.createdBy.displayName }}</span>
            </div>
            <div v-if="todo.completedAt" class="flex items-center gap-2">
              <i class="pi pi-check-circle text-green-500" />
              <span>{{ formatDate(todo.completedAt) }} に完了
                <span v-if="todo.completedBy">（{{ todo.completedBy.displayName }}）</span>
              </span>
            </div>
          </div>

          <div v-if="todo.description" class="rounded-lg bg-surface-50 p-3 dark:bg-surface-700/50">
            <p class="whitespace-pre-wrap text-sm">{{ todo.description }}</p>
          </div>
        </div>
      </template>

      <!-- 編集モード -->
      <template v-else>
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">タイトル <span class="text-red-500">*</span></label>
            <InputText v-model="editForm.title" class="w-full" autofocus />
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">説明（任意）</label>
            <Textarea v-model="editForm.description" class="w-full" rows="3" auto-resize />
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">優先度</label>
            <Select
              v-model="editForm.priority"
              :options="priorityOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>

          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="mb-1 block text-sm font-medium">開始日（任意）</label>
              <DatePicker
                v-model="editForm.startDate"
                class="w-full"
                date-format="yy/mm/dd"
                show-icon
              />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">期限（任意）</label>
              <DatePicker
                v-model="editForm.dueDate"
                class="w-full"
                date-format="yy/mm/dd"
                show-icon
              />
            </div>
          </div>

          <div class="flex justify-end gap-2">
            <Button label="キャンセル" text severity="secondary" @click="cancelEdit" />
            <Button
              label="保存"
              icon="pi pi-check"
              :loading="saving"
              :disabled="!editForm.title.trim()"
              @click="saveEdit"
            />
          </div>
        </div>
      </template>
    </SectionCard>
  </div>
</template>
