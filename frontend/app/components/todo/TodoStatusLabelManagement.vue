<script setup lang="ts">
import {
  BUCKET_DEFAULT_COLOR,
  LABEL_LIMIT_PER_SCOPE,
  type CreateTodoStatusLabelRequest,
  type TodoStatusLabel,
  type TodoStatusLabelBucket,
  type UpdateTodoStatusLabelRequest,
} from '~/types/todoStatusLabel'

const props = defineProps<{
  scope: 'me' | 'team' | 'organization'
  scopeId?: string
  canEdit: boolean
}>()

const { t } = useI18n()
const labelApi = useTodoStatusLabelApi()
const notification = useNotification()
const errorHandler = useErrorHandler()

const labels = ref<TodoStatusLabel[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editTarget = ref<TodoStatusLabel | null>(null)
const saving = ref(false)
const deleteDialogVisible = ref(false)
const deleteTarget = ref<TodoStatusLabel | null>(null)
const deleting = ref(false)

const bucketOptions: Array<{ label: string; value: TodoStatusLabelBucket }> = [
  { label: t('todo.statusLabel.bucket.OPEN'), value: 'OPEN' },
  { label: t('todo.statusLabel.bucket.IN_PROGRESS'), value: 'IN_PROGRESS' },
  { label: t('todo.statusLabel.bucket.COMPLETED'), value: 'COMPLETED' },
]

interface FormState {
  name: string
  bucket: TodoStatusLabelBucket
  color: string
  sortOrder: number
}

const form = reactive<FormState>({
  name: '',
  bucket: 'OPEN',
  color: BUCKET_DEFAULT_COLOR.OPEN,
  sortOrder: 0,
})

const userSectionKey = computed(() => {
  if (props.scope === 'team') return 'todo.statusLabel.management.teamSection'
  if (props.scope === 'organization') return 'todo.statusLabel.management.organizationSection'
  return 'todo.statusLabel.management.userSection'
})

async function loadLabels() {
  loading.value = true
  try {
    const res = await labelApi.listLabels(props.scope, props.scopeId)
    labels.value = res.data
  } catch (e) {
    errorHandler.handleApiError(e, `${props.scope}-todo-status-labels:list`)
  } finally {
    loading.value = false
  }
}

const userLabels = computed(() => labels.value.filter((l) => !l.isSystemDefault))
const systemLabels = computed(() => labels.value.filter((l) => l.isSystemDefault))
const isLimitReached = computed(() => userLabels.value.length >= LABEL_LIMIT_PER_SCOPE)

function openCreateDialog() {
  editTarget.value = null
  form.name = ''
  form.bucket = 'OPEN'
  form.color = BUCKET_DEFAULT_COLOR.OPEN
  form.sortOrder = userLabels.value.length
  dialogVisible.value = true
}

function openEditDialog(label: TodoStatusLabel) {
  editTarget.value = label
  form.name = label.name
  form.bucket = label.bucket
  form.color = label.color ?? BUCKET_DEFAULT_COLOR[label.bucket]
  form.sortOrder = label.sortOrder
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
  editTarget.value = null
}

function colorOf(label: TodoStatusLabel): string {
  return label.color ?? BUCKET_DEFAULT_COLOR[label.bucket]
}

function bucketLabel(bucket: TodoStatusLabelBucket): string {
  return t(`todo.statusLabel.bucket.${bucket}`)
}

async function handleSave() {
  if (!form.name.trim()) return
  saving.value = true
  try {
    if (editTarget.value) {
      const body: UpdateTodoStatusLabelRequest = {
        name: form.name.trim(),
        bucket: form.bucket,
        color: form.color,
        sortOrder: form.sortOrder,
      }
      await labelApi.updateLabel(props.scope, props.scopeId, editTarget.value.id, body)
      notification.success(t('todo.statusLabel.management.updateSuccess'))
    } else {
      const body: CreateTodoStatusLabelRequest = {
        name: form.name.trim(),
        bucket: form.bucket,
        color: form.color,
        sortOrder: form.sortOrder,
      }
      await labelApi.createLabel(props.scope, props.scopeId, body)
      notification.success(t('todo.statusLabel.management.createSuccess'))
    }
    closeDialog()
    await loadLabels()
  } catch (e) {
    handleSaveError(e)
  } finally {
    saving.value = false
  }
}

function handleSaveError(error: unknown) {
  const code = (error as { data?: { error?: { code?: string } } })?.data?.error?.code
  if (code === 'LABEL_NAME_DUPLICATED' || code === 'LABEL_NAME_DUPLICATE') {
    notification.error(t('todo.statusLabel.error.duplicate'))
    return
  }
  if (code === 'LABEL_LIMIT_EXCEEDED') {
    notification.error(t('todo.statusLabel.error.limitExceeded'))
    return
  }
  if (code === 'SYSTEM_LABEL_IMMUTABLE') {
    notification.error(t('todo.statusLabel.error.systemImmutable'))
    return
  }
  errorHandler.handleApiError(error, `${props.scope}-todo-status-labels:save`)
}

function openDeleteDialog(label: TodoStatusLabel) {
  deleteTarget.value = label
  deleteDialogVisible.value = true
}

function closeDeleteDialog() {
  deleteDialogVisible.value = false
  deleteTarget.value = null
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await labelApi.deleteLabel(props.scope, props.scopeId, deleteTarget.value.id)
    notification.success(t('todo.statusLabel.management.deleteSuccess'))
    closeDeleteDialog()
    await loadLabels()
  } catch (e) {
    handleDeleteError(e)
  } finally {
    deleting.value = false
  }
}

function handleDeleteError(error: unknown) {
  const apiErr = error as {
    data?: { error?: { code?: string; details?: { in_use_count?: number; inUseCount?: number } } }
  }
  const code = apiErr?.data?.error?.code
  if (code === 'LABEL_IN_USE') {
    const count =
      apiErr?.data?.error?.details?.inUseCount ?? apiErr?.data?.error?.details?.in_use_count ?? 0
    notification.error(t('todo.statusLabel.error.inUseWithCount', { n: count }))
    return
  }
  if (code === 'SYSTEM_LABEL_IMMUTABLE') {
    notification.error(t('todo.statusLabel.error.systemImmutable'))
    return
  }
  errorHandler.handleApiError(error, `${props.scope}-todo-status-labels:delete`)
}

onMounted(loadLabels)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <BackButton />
        <h1 class="text-xl font-bold">{{ t('todo.statusLabel.management.title') }}</h1>
      </div>
      <Button
        v-if="canEdit && !isLimitReached"
        :label="t('todo.statusLabel.management.create')"
        icon="pi pi-plus"
        size="small"
        @click="openCreateDialog"
      />
    </div>

    <!-- 閲覧専用バナー -->
    <div
      v-if="!canEdit && scope !== 'me'"
      class="mb-4 rounded-lg border border-surface-300 bg-surface-50 px-4 py-3 text-sm text-surface-600 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-300"
    >
      <i class="pi pi-info-circle mr-2" />
      {{ t('todo.statusLabel.management.readOnlyForNonAdmin') }}
    </div>

    <!-- 上限警告 -->
    <div
      v-if="canEdit && isLimitReached"
      class="mb-4 rounded-lg border border-orange-200 bg-orange-50 px-4 py-3 text-sm text-orange-700 dark:border-orange-800 dark:bg-orange-900/20 dark:text-orange-300"
    >
      <i class="pi pi-exclamation-triangle mr-2" />
      {{ t('todo.statusLabel.error.limitExceeded') }}
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <i class="pi pi-spin pi-spinner text-3xl text-surface-400" />
    </div>

    <template v-else>
      <!-- システム既定 -->
      <section class="mb-8">
        <h2 class="mb-3 text-sm font-semibold uppercase tracking-wide text-surface-500">
          {{ t('todo.statusLabel.management.systemSection') }}
        </h2>
        <div class="space-y-2">
          <div
            v-for="label in systemLabels"
            :key="label.id"
            class="flex items-center gap-3 rounded-lg border border-surface-300 bg-surface-50 px-4 py-3 dark:border-surface-600 dark:bg-surface-800"
          >
            <span
              class="inline-block h-3 w-3 rounded-full"
              :style="{ backgroundColor: colorOf(label) }"
            />
            <div class="flex-1">
              <p class="text-sm font-medium">
                {{ label.name }}
                <span class="ml-2 text-xs font-normal text-surface-400">
                  {{ t('todo.statusLabel.systemDefault') }}
                </span>
              </p>
              <p class="text-xs text-surface-500">{{ bucketLabel(label.bucket) }}</p>
            </div>
            <i class="pi pi-lock text-surface-400" />
          </div>
        </div>
      </section>

      <!-- ユーザー独自 -->
      <section>
        <h2 class="mb-3 text-sm font-semibold uppercase tracking-wide text-surface-500">
          {{ t(userSectionKey) }}
          <span class="ml-2 font-normal normal-case text-surface-400">
            {{ userLabels.length }} / {{ LABEL_LIMIT_PER_SCOPE }}
          </span>
        </h2>

        <div
          v-if="userLabels.length === 0"
          class="rounded-xl border-2 border-dashed border-surface-300 px-4 py-10 text-center dark:border-surface-600"
        >
          <i class="pi pi-tag mb-3 text-3xl text-surface-300" />
          <p class="text-sm text-surface-400">{{ t('todo.statusLabel.management.empty') }}</p>
        </div>

        <div v-else class="space-y-2">
          <div
            v-for="label in userLabels"
            :key="label.id"
            class="flex items-center gap-3 rounded-lg border border-surface-300 bg-surface-0 px-4 py-3 dark:border-surface-600 dark:bg-surface-900"
          >
            <span
              class="inline-block h-3 w-3 rounded-full"
              :style="{ backgroundColor: colorOf(label) }"
            />
            <div class="flex-1">
              <p class="text-sm font-medium">{{ label.name }}</p>
              <p class="text-xs text-surface-500">{{ bucketLabel(label.bucket) }}</p>
            </div>
            <Button
              v-if="canEdit"
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              :aria-label="t('todo.statusLabel.management.edit')"
              @click="openEditDialog(label)"
            />
            <Button
              v-if="canEdit"
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              :aria-label="t('todo.statusLabel.management.delete')"
              @click="openDeleteDialog(label)"
            />
          </div>
        </div>
      </section>
    </template>

    <!-- 作成・編集ダイアログ -->
    <Dialog
      v-model:visible="dialogVisible"
      modal
      :header="
        editTarget ? t('todo.statusLabel.management.edit') : t('todo.statusLabel.management.create')
      "
      class="w-full max-w-md"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('todo.statusLabel.field.name') }}</label>
          <InputText v-model="form.name" class="w-full" maxlength="50" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('todo.statusLabel.field.bucket') }}</label>
          <Select
            v-model="form.bucket"
            :options="bucketOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('todo.statusLabel.field.color') }}</label>
          <div class="flex items-center gap-3">
            <ColorPicker v-model="form.color" />
            <InputText v-model="form.color" class="flex-1" />
          </div>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('todo.statusLabel.field.sortOrder') }}</label>
          <InputNumber v-model="form.sortOrder" :min="0" class="w-full" show-buttons />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-3">
          <Button :label="t('button.cancel')" severity="secondary" text @click="closeDialog" />
          <Button :label="t('button.save')" :loading="saving" @click="handleSave" />
        </div>
      </template>
    </Dialog>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="deleteDialogVisible"
      modal
      :header="t('dialog.confirm_title')"
      class="w-full max-w-md"
    >
      <p class="text-sm text-surface-700 dark:text-surface-300">
        {{ t('todo.statusLabel.confirmDelete') }}
      </p>
      <template #footer>
        <div class="flex justify-end gap-3">
          <Button :label="t('button.cancel')" severity="secondary" text @click="closeDeleteDialog" />
          <Button
            :label="t('button.delete')"
            severity="danger"
            :loading="deleting"
            @click="confirmDelete"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
