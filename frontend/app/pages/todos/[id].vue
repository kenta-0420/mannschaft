<script setup lang="ts">
import type { TodoStatusLabelInfo } from '~/types/todoStatusLabel'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const todoId = Number(route.params.id)
const todoApi = useTodoApi()
const notification = useNotification()
const errorHandler = useErrorHandler()
const authStore = useAuthStore()
const { formatDate, formatDateTime } = useDatetime()

/** Wave 1 DTO刷新: ネスト構造 */
interface PersonalTodoDetail {
  id: number
  scope?: {
    scopeType?: string
    scopeId?: number
    projectId?: number | null
    milestoneId?: number | null
  }
  content?: {
    title?: string
    description?: string | null
    startDate?: string | null
    progressRate?: number | null
    progressManual?: boolean
    sortOrder?: number
  }
  schedule?: {
    dueDate?: string | null
    dueTime?: string | null
    daysRemaining?: number | null
    linkedScheduleId?: number | null
  }
  audit?: {
    createdAt?: string
    updatedAt?: string
    createdBy?: { id: number; displayName: string }
    completedBy?: { id: number; displayName: string } | null
  }
  /** @deprecated 旧フラットフィールド互換 — ステータスバケット */
  status?: string
  /** @deprecated 旧フラットフィールド互換 */
  priority?: string
  /** @deprecated 旧フラットフィールド互換 */
  statusLabel?: TodoStatusLabelInfo | null
  /** @deprecated 旧フラットフィールド互換 */
  daysRemaining?: number | null
  /** @deprecated 旧フラットフィールド互換 */
  completedAt?: string | null
  /** @deprecated 旧フラットフィールド互換 */
  completedBy?: { id: number; displayName: string } | null
  assignees: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
}

const todo = ref<PersonalTodoDetail | null>(null)
const loading = ref(true)
const newLabelId = ref<number | null>(null)
const changing = ref(false)

// 編集モード（main 由来 — 個人 TODO の項目編集）
const editing = ref(false)
const saving = ref(false)
const editForm = ref({
  title: '',
  description: '',
  priority: 'MEDIUM',
  progressRate: 0,
  projectId: null as number | null,
  milestoneId: null as number | null,
  startDate: null as Date | null,
  dueDate: null as Date | null,
})

const userId = computed<number | null>(() => authStore.user?.id ?? null)

async function loadTodo() {
  loading.value = true
  try {
    const res = await todoApi.getPersonalTodo(todoId)
    todo.value = res.data as unknown as PersonalTodoDetail
    newLabelId.value = todo.value?.statusLabel?.id ?? null
  }
  catch (e) {
    errorHandler.handleApiError(e, 'personal-todo:load')
    todo.value = null
  }
  finally {
    loading.value = false
  }
}

async function applyStatusChange() {
  if (!todo.value) return
  if (newLabelId.value === null) return
  if (newLabelId.value === todo.value.statusLabel?.id) return
  changing.value = true
  try {
    await todoApi.changeTodoStatusById('PERSONAL', null, todoId, {
      statusLabelId: newLabelId.value,
    })
    await loadTodo()
    const labelName = todo.value?.statusLabel?.name ?? ''
    notification.success(t('todo.statusChange.success', { name: labelName }))
  }
  catch (e) {
    errorHandler.handleApiError(e, 'personal-todo:status')
  }
  finally {
    changing.value = false
  }
}

function startEdit() {
  if (!todo.value) return
  editForm.value = {
    title: todo.value.content?.title ?? '',
    description: todo.value.content?.description ?? '',
    priority: todo.value.priority ?? 'MEDIUM',
    progressRate: todo.value.content?.progressRate ?? 0,
    projectId: todo.value.scope?.projectId ?? null,
    milestoneId: todo.value.scope?.milestoneId ?? null,
    startDate: todo.value.content?.startDate ? new Date(todo.value.content.startDate) : null,
    dueDate: todo.value.schedule?.dueDate ? new Date(todo.value.schedule.dueDate) : null,
  }
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

function toLocalDateStr(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function saveEdit() {
  if (!editForm.value.title.trim()) return
  saving.value = true
  try {
    await todoApi.updatePersonalTodo(todoId, {
      title: editForm.value.title.trim(),
      description: editForm.value.description.trim() || null,
      priority: editForm.value.priority,
      progressRate: editForm.value.progressRate,
      projectId: editForm.value.projectId,
      milestoneId: editForm.value.milestoneId,
      startDate: editForm.value.startDate ? toLocalDateStr(editForm.value.startDate) : null,
      dueDate: editForm.value.dueDate ? toLocalDateStr(editForm.value.dueDate) : null,
    })
    notification.success(t('todo.action.updated'))
    // 楽観的更新: API再取得を待たずに即時反映
    if (todo.value) {
      todo.value = {
        ...todo.value,
        content: { ...todo.value.content, progressRate: editForm.value.progressRate },
      }
    }
    editing.value = false
    await loadTodo()
  }
  catch {
    notification.error(t('todo.action.updateFailed'))
  }
  finally {
    saving.value = false
  }
}

const priorityOptions = computed(() => [
  { label: t('todo.priorityValue.HIGH'), value: 'HIGH' },
  { label: t('todo.priorityValue.MEDIUM'), value: 'MEDIUM' },
  { label: t('todo.priorityValue.LOW'), value: 'LOW' },
])


onMounted(loadTodo)
</script>

<template>
  <div v-if="loading" class="space-y-4">
    <Skeleton height="2rem" width="60%" />
    <Skeleton height="8rem" />
    <Skeleton height="4rem" />
  </div>

  <div v-else-if="todo" class="mx-auto max-w-3xl">
    <!-- ヘッダー -->
    <div class="mb-6">
      <BackButton to="/todos" :label="t('todo.backToList')" />
      <div class="flex items-center justify-between gap-3">
        <PageHeader :title="todo.content?.title ?? ''" />
        <Button
          v-if="!editing"
          icon="pi pi-pencil"
          text
          rounded
          size="small"
          @click="startEdit"
        />
      </div>
    </div>

    <!-- 閲覧モード -->
    <template v-if="!editing">
      <!-- メタ情報 -->
      <div class="mb-6 grid grid-cols-2 gap-4 md:grid-cols-4">
        <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
          <p class="text-xs text-surface-500">{{ t('todo.field.status') }}</p>
          <div class="mt-1">
            <TodoStatusLabelBadge :label="todo.statusLabel" :fallback-bucket="todo.status" />
          </div>
        </div>
        <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
          <p class="text-xs text-surface-500">{{ t('todo.field.priority') }}</p>
          <div class="mt-1">
            <TodoPriorityBadge :priority="todo.priority ?? ''" />
          </div>
        </div>
        <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
          <p class="text-xs text-surface-500">{{ t('todo.field.startDate') }}</p>
          <p class="mt-1 text-sm font-medium">
            {{ formatDate(todo.content?.startDate ?? null) }}
          </p>
        </div>
        <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
          <p class="text-xs text-surface-500">{{ t('todo.field.dueDate') }}</p>
          <p
            class="mt-1 text-sm font-medium"
            :class="{
              'text-red-500':
                todo.schedule?.daysRemaining !== null &&
                (todo.schedule?.daysRemaining ?? 0) < 0 &&
                todo.status !== 'COMPLETED',
            }"
          >
            {{ formatDate(todo.schedule?.dueDate ?? null) }}
          </p>
        </div>
        <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
          <p class="text-xs text-surface-500">{{ t('todo.field.progressRate') }}</p>
          <p class="mt-1 text-sm font-medium">{{ todo.content?.progressRate ?? 0 }}%</p>
        </div>
      </div>

      <!-- ステータス変更 UI -->
      <SectionCard :title="t('todo.statusChange.section')" class="mb-6">
        <div class="flex items-center gap-3">
          <div class="max-w-xs flex-1">
            <TodoStatusLabelSelect
              v-if="userId"
              v-model="newLabelId"
              scope-type="PERSONAL"
              :scope-id="userId"
            />
          </div>
          <Button
            :label="t('todo.statusChange.applyButton')"
            :loading="changing"
            :disabled="newLabelId === null || newLabelId === todo.statusLabel?.id"
            @click="applyStatusChange"
          />
        </div>
      </SectionCard>

      <!-- 説明 -->
      <SectionCard v-if="todo.content?.description" :title="t('todo.field.description')" class="mb-6">
        <p class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-300">
          {{ todo.content?.description }}
        </p>
      </SectionCard>

      <!-- 完了情報 -->
      <div
        v-if="todo.completedAt"
        class="mb-6 rounded-lg border border-green-200 bg-green-50 p-4 dark:border-green-800 dark:bg-green-900/20"
      >
        <p class="text-sm">
          <i class="pi pi-check-circle mr-1 text-green-600" />
          {{ todo.completedBy?.displayName ?? '-' }} / {{ formatDateTime(todo.completedAt) }}
        </p>
      </div>
    </template>

    <!-- 編集モード -->
    <SectionCard v-else class="max-w-2xl">
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('todo.field.title') }} <span class="text-red-500">*</span></label>
          <InputText v-model="editForm.title" class="w-full" autofocus />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('todo.field.descriptionOptional') }}</label>
          <Textarea v-model="editForm.description" class="w-full" rows="3" auto-resize />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('todo.field.priority') }}</label>
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
            <label class="mb-1 block text-sm font-medium">{{ t('todo.field.startDateOptional') }}</label>
            <DatePicker
              v-model="editForm.startDate"
              class="w-full"
              date-format="yy/mm/dd"
              show-icon
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('todo.field.dueDateOptional') }}</label>
            <DatePicker
              v-model="editForm.dueDate"
              class="w-full"
              date-format="yy/mm/dd"
              show-icon
            />
          </div>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('todo.field.progressRate') }}</label>
          <div class="mt-3 flex items-center gap-4">
            <Slider v-model="editForm.progressRate" :step="5" :min="0" :max="100" class="flex-1" />
            <span class="w-12 shrink-0 text-right text-sm font-semibold">{{ editForm.progressRate }}%</span>
          </div>
        </div>

        <div class="flex justify-end gap-2">
          <Button :label="t('todo.action.cancel')" text severity="secondary" @click="cancelEdit" />
          <Button
            :label="t('todo.action.save')"
            icon="pi pi-check"
            :loading="saving"
            :disabled="!editForm.title.trim()"
            @click="saveEdit"
          />
        </div>
      </div>
    </SectionCard>
  </div>

  <div v-else class="mx-auto max-w-3xl">
    <Button icon="pi pi-arrow-left" text rounded @click="router.push('/todos')" />
  </div>
</template>
