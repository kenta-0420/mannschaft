<script setup lang="ts">
import dayjs from 'dayjs'
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
const { userTimezone } = useDatetime()
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

/**
 * TODO詳細を取得する。
 * @param silent true の場合はスケルトン（loading）を出さずに裏で再取得する。
 *   更新後の再取得に使うと、画面全体の再描画（チラつき）を避け、
 *   Vue の差分更新で変更された箇所だけが書き換わる。
 */
async function loadTodo(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await todoApi.getPersonalTodo(todoId)
    todo.value = res.data as unknown as PersonalTodoDetail
    newLabelId.value = todo.value?.statusLabel?.id ?? null
  }
  catch (e) {
    errorHandler.handleApiError(e, 'personal-todo:load')
    if (!silent) todo.value = null
  }
  finally {
    if (!silent) loading.value = false
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
    await loadTodo(true)
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
  return dayjs(d).tz(userTimezone.value).format('YYYY-MM-DD')
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
    await loadTodo(true)
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

// ── インライン編集 ───────────────────────────────────────────────
type EditableField = 'status' | 'priority' | 'startDate' | 'dueDate' | 'progressRate'
const editingField = ref<EditableField | null>(null)
const fieldSaving = ref(false)

const inlinePriority = ref<string>('MEDIUM')
const inlineStartDate = ref<Date | null>(null)
const inlineDueDate = ref<Date | null>(null)
const inlineProgressRate = ref<number>(0)
const inlineStatusLabelId = ref<number | null>(null)

function startFieldEdit(field: EditableField) {
  if (editing.value) return // 一括編集中は個別編集不可
  editingField.value = field
  if (!todo.value) return
  if (field === 'priority') inlinePriority.value = todo.value.priority ?? 'MEDIUM'
  if (field === 'startDate') inlineStartDate.value = todo.value.content?.startDate ? new Date(todo.value.content.startDate) : null
  if (field === 'dueDate') inlineDueDate.value = todo.value.schedule?.dueDate ? new Date(todo.value.schedule.dueDate) : null
  if (field === 'progressRate') inlineProgressRate.value = todo.value.content?.progressRate ?? 0
  if (field === 'status') inlineStatusLabelId.value = todo.value.statusLabel?.id ?? null
}

function cancelFieldEdit() {
  editingField.value = null
}

async function saveFieldEdit() {
  if (!editingField.value) return
  fieldSaving.value = true
  try {
    if (editingField.value === 'status') {
      if (inlineStatusLabelId.value === null) return
      if (inlineStatusLabelId.value === todo.value?.statusLabel?.id) {
        editingField.value = null
        return
      }
      await todoApi.changeTodoStatusById('PERSONAL', null, todoId, {
        statusLabelId: inlineStatusLabelId.value,
      })
    }
    else {
      // PUT /todos/{id} は全項目置換（title が @NotBlank 必須）。
      // インライン編集は1項目のみ変更するため、現在値をベースに変更項目だけ上書きして送信する。
      const payload: Record<string, unknown> = {
        title: todo.value?.content?.title ?? '',
        description: todo.value?.content?.description ?? null,
        priority: todo.value?.priority ?? 'MEDIUM',
        projectId: todo.value?.scope?.projectId ?? null,
        milestoneId: todo.value?.scope?.milestoneId ?? null,
        startDate: todo.value?.content?.startDate ?? null,
        dueDate: todo.value?.schedule?.dueDate ?? null,
        // dueTime は updateTodo() で無条件上書きされるため、現在値を保持して消失を防ぐ
        dueTime: todo.value?.schedule?.dueTime ?? null,
        progressRate: todo.value?.content?.progressRate ?? null,
      }
      if (editingField.value === 'priority') payload.priority = inlinePriority.value
      if (editingField.value === 'startDate') payload.startDate = inlineStartDate.value ? toLocalDateStr(inlineStartDate.value) : null
      if (editingField.value === 'dueDate') payload.dueDate = inlineDueDate.value ? toLocalDateStr(inlineDueDate.value) : null
      if (editingField.value === 'progressRate') payload.progressRate = inlineProgressRate.value
      await todoApi.updatePersonalTodo(todoId, payload)
    }
    notification.success(t('todo.action.updated'))
    editingField.value = null
    await loadTodo(true)
  }
  catch {
    notification.error(t('todo.action.updateFailed'))
  }
  finally {
    fieldSaving.value = false
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') cancelFieldEdit()
}

onMounted(() => {
  loadTodo()
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
})
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
      <div class="flex items-center justify-between gap-3">
        <PageHeader :title="todo.content?.title ?? ''" back-to="/todos" :back-label="t('todo.backToList')" />
        <Button
          v-if="!editing && editingField === null"
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
      <div class="mb-6">
      <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
        <!-- ステータスカード -->
        <div
          class="rounded-lg border-2 p-3 transition-colors bg-surface-0 dark:bg-surface-800"
          :class="editingField === 'status'
            ? 'border-primary cursor-default'
            : 'border-surface-400 dark:border-surface-500 cursor-pointer hover:border-primary/70'"
          @click="editingField !== 'status' && startFieldEdit('status')"
        >
          <p class="text-xs text-surface-500">{{ t('todo.field.status') }}</p>
          <template v-if="editingField === 'status'">
            <div class="mt-1 flex flex-col gap-2" @click.stop>
              <TodoStatusLabelSelect
                v-if="userId"
                v-model="inlineStatusLabelId"
                scope-type="PERSONAL"
                :scope-id="userId !== null ? String(userId) : null"
              />
              <div class="flex justify-end gap-1">
                <Button icon="pi pi-times" text rounded size="small" severity="secondary" :disabled="fieldSaving" @click="cancelFieldEdit" />
                <Button icon="pi pi-check" text rounded size="small" :loading="fieldSaving" @click="saveFieldEdit" />
              </div>
            </div>
          </template>
          <div v-else class="mt-1">
            <TodoStatusLabelBadge :label="todo.statusLabel" :fallback-bucket="todo.status" />
          </div>
        </div>

        <!-- 優先度カード -->
        <div
          class="rounded-lg border-2 p-3 transition-colors bg-surface-0 dark:bg-surface-800"
          :class="editingField === 'priority'
            ? 'border-primary cursor-default'
            : 'border-surface-400 dark:border-surface-500 cursor-pointer hover:border-primary/70'"
          @click="editingField !== 'priority' && startFieldEdit('priority')"
        >
          <p class="text-xs text-surface-500">{{ t('todo.field.priority') }}</p>
          <template v-if="editingField === 'priority'">
            <div class="mt-1 space-y-2" @click.stop>
              <Select
                v-model="inlinePriority"
                :options="priorityOptions"
                option-label="label"
                option-value="value"
                class="w-full"
              />
              <div class="flex justify-end gap-1">
                <Button icon="pi pi-times" text rounded size="small" severity="secondary" :disabled="fieldSaving" @click="cancelFieldEdit" />
                <Button icon="pi pi-check" text rounded size="small" :loading="fieldSaving" @click="saveFieldEdit" />
              </div>
            </div>
          </template>
          <div v-else class="mt-1">
            <TodoPriorityBadge :priority="todo.priority ?? ''" />
          </div>
        </div>

        <!-- 開始日カード -->
        <div
          class="rounded-lg border-2 p-3 transition-colors bg-surface-0 dark:bg-surface-800"
          :class="editingField === 'startDate'
            ? 'border-primary cursor-default'
            : 'border-surface-400 dark:border-surface-500 cursor-pointer hover:border-primary/70'"
          @click="editingField !== 'startDate' && startFieldEdit('startDate')"
        >
          <p class="text-xs text-surface-500">{{ t('todo.field.startDate') }}</p>
          <template v-if="editingField === 'startDate'">
            <div class="mt-1 space-y-2" @click.stop>
              <DatePicker v-model="inlineStartDate" class="w-full" date-format="yy/mm/dd" show-icon />
              <div class="flex justify-end gap-1">
                <Button icon="pi pi-times" text rounded size="small" severity="secondary" :disabled="fieldSaving" @click="cancelFieldEdit" />
                <Button icon="pi pi-check" text rounded size="small" :loading="fieldSaving" @click="saveFieldEdit" />
              </div>
            </div>
          </template>
          <p v-else class="mt-1 text-sm font-medium">{{ formatDate(todo.content?.startDate ?? null) }}</p>
        </div>

        <!-- 期限カード -->
        <div
          class="rounded-lg border-2 p-3 transition-colors bg-surface-0 dark:bg-surface-800"
          :class="editingField === 'dueDate'
            ? 'border-primary cursor-default'
            : 'border-surface-400 dark:border-surface-500 cursor-pointer hover:border-primary/70'"
          @click="editingField !== 'dueDate' && startFieldEdit('dueDate')"
        >
          <p class="text-xs text-surface-500">{{ t('todo.field.dueDate') }}</p>
          <template v-if="editingField === 'dueDate'">
            <div class="mt-1 space-y-2" @click.stop>
              <DatePicker v-model="inlineDueDate" class="w-full" date-format="yy/mm/dd" show-icon />
              <div class="flex justify-end gap-1">
                <Button icon="pi pi-times" text rounded size="small" severity="secondary" :disabled="fieldSaving" @click="cancelFieldEdit" />
                <Button icon="pi pi-check" text rounded size="small" :loading="fieldSaving" @click="saveFieldEdit" />
              </div>
            </div>
          </template>
          <p
            v-else
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

        <!-- 進捗率カード -->
        <div
          class="rounded-lg border-2 p-3 transition-colors bg-surface-0 dark:bg-surface-800"
          :class="editingField === 'progressRate'
            ? 'border-primary cursor-default'
            : 'border-surface-400 dark:border-surface-500 cursor-pointer hover:border-primary/70'"
          @click="editingField !== 'progressRate' && startFieldEdit('progressRate')"
        >
          <p class="text-xs text-surface-500">{{ t('todo.field.progressRate') }}</p>
          <template v-if="editingField === 'progressRate'">
            <div class="mt-1 space-y-2" @click.stop>
              <div class="flex items-center gap-3">
                <Slider v-model="inlineProgressRate" :step="5" :min="0" :max="100" class="progress-slider flex-1" />
                <span class="w-12 shrink-0 text-right text-sm font-semibold">{{ inlineProgressRate }}%</span>
              </div>
              <div class="flex justify-end gap-1">
                <Button icon="pi pi-times" text rounded size="small" severity="secondary" :disabled="fieldSaving" @click="cancelFieldEdit" />
                <Button icon="pi pi-check" text rounded size="small" :loading="fieldSaving" @click="saveFieldEdit" />
              </div>
            </div>
          </template>
          <p v-else class="mt-1 text-sm font-medium">{{ todo.content?.progressRate ?? 0 }}%</p>
        </div>
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
              :scope-id="userId !== null ? String(userId) : null"
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
            <Slider v-model="editForm.progressRate" :step="5" :min="0" :max="100" class="progress-slider flex-1" />
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

<style scoped>
/* 進捗スライダー: 線上の任意の位置をクリックするとハンドルが移動するよう、
   トラックの高さ（＝クリック可能領域）を広げて押しやすくする。 */
.progress-slider {
  height: 0.625rem;
  cursor: pointer;
}
.progress-slider :deep(.p-slider-handle) {
  cursor: grab;
}
.progress-slider :deep(.p-slider-handle):active {
  cursor: grabbing;
}
</style>
