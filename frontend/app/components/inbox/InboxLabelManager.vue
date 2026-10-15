<script setup lang="ts">
import type { CreateLabelPayload, InboxLabel, UpdateLabelPayload } from '~/types/inbox'

/**
 * F04.11 Phase 2 — ラベル管理 CRUD コンポーネント。
 *
 * - ラベルの一覧表示（色ドット + 名前）
 * - 作成（name + color ピッカー + icon）
 * - 編集（名前・色・アイコンを変更）
 * - 削除（確認ダイアログ）
 * - 上限20件警告 / 同名重複(409)エラートースト
 *
 * 手本: TodoStatusLabelManagement.vue
 * 設計書: docs/features/F04.11_notification_inbox/02_api_design.md §3.4
 */

const LABEL_LIMIT = 20

const { t } = useI18n()
const inboxStore = useInboxStore()
const notification = useNotification()
const { captureQuiet } = useErrorReport()

const dialogVisible = ref(false)
const editTarget = ref<InboxLabel | null>(null)
const saving = ref(false)
const deleteDialogVisible = ref(false)
const deleteTarget = ref<InboxLabel | null>(null)
const deleting = ref(false)

interface LabelForm {
  name: string
  color: string
  icon: string
}

const form = reactive<LabelForm>({
  name: '',
  color: '#6366f1',
  icon: '',
})

const isLimitReached = computed(() => inboxStore.labels.length >= LABEL_LIMIT)

function openCreateDialog() {
  editTarget.value = null
  form.name = ''
  form.color = '#6366f1'
  form.icon = ''
  dialogVisible.value = true
}

function openEditDialog(label: InboxLabel) {
  editTarget.value = label
  form.name = label.name
  form.color = label.color ?? '#6366f1'
  form.icon = label.icon ?? ''
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
  editTarget.value = null
}

async function handleSave() {
  if (!form.name.trim()) return
  saving.value = true
  try {
    if (editTarget.value) {
      const payload: UpdateLabelPayload = {
        name: form.name.trim(),
        color: form.color || undefined,
        icon: form.icon || undefined,
      }
      await inboxStore.updateLabel(editTarget.value.id, payload)
      notification.success(t('inbox.label.updated'))
      closeDialog()
    } else {
      const payload: CreateLabelPayload = {
        name: form.name.trim(),
        color: form.color || undefined,
        icon: form.icon || undefined,
      }
      await inboxStore.createLabel(payload)
      notification.success(t('inbox.label.created'))
      closeDialog()
    }
  } catch (error) {
    handleSaveError(error)
  } finally {
    saving.value = false
  }
}

function handleSaveError(error: unknown) {
  const apiErr = error as { status?: number; data?: { error?: { code?: string } } }
  const status = apiErr?.status
  const code = apiErr?.data?.error?.code

  if (status === 409 || code === 'LABEL_NAME_DUPLICATE' || code === 'LABEL_NAME_DUPLICATED') {
    notification.error(t('inbox.label.duplicate'))
    return
  }
  if (status === 422 || code === 'LABEL_LIMIT_EXCEEDED') {
    notification.error(t('inbox.label.limitReached'))
    return
  }
  captureQuiet(error, { context: 'InboxLabelManager: ラベル保存' })
  notification.error(t('common.error.unknown'))
}

function openDeleteDialog(label: InboxLabel) {
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
    await inboxStore.deleteLabel(deleteTarget.value.id)
    notification.success(t('inbox.label.deleted'))
    closeDeleteDialog()
  } catch (error) {
    captureQuiet(error, { context: 'InboxLabelManager: ラベル削除' })
    notification.error(t('common.error.unknown'))
  } finally {
    deleting.value = false
  }
}

onMounted(async () => {
  if (inboxStore.labels.length === 0) {
    await inboxStore.fetchLabels()
  }
})
</script>

<template>
  <div data-testid="inbox-label-manager">
    <!-- ヘッダー -->
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('inbox.label.manage') }}
        <span class="ml-1 font-normal text-surface-400">
          {{ inboxStore.labels.length }} / {{ LABEL_LIMIT }}
        </span>
      </h3>
      <Button
        v-if="!isLimitReached"
        :label="t('inbox.label.create')"
        icon="pi pi-plus"
        size="small"
        @click="openCreateDialog"
      />
    </div>

    <!-- 上限警告 -->
    <div
      v-if="isLimitReached"
      class="mb-3 rounded-lg border border-orange-200 bg-orange-50 px-3 py-2 text-xs text-orange-700 dark:border-orange-800 dark:bg-orange-900/20 dark:text-orange-300"
    >
      <i class="pi pi-exclamation-triangle mr-1.5" />
      {{ t('inbox.label.limitReached') }}
    </div>

    <!-- ローディング -->
    <div v-if="inboxStore.labelsLoading" class="flex justify-center py-6">
      <i class="pi pi-spin pi-spinner text-xl text-primary" />
    </div>

    <!-- 空状態 -->
    <div
      v-else-if="inboxStore.labels.length === 0"
      class="rounded-xl border-2 border-dashed border-surface-200 px-4 py-8 text-center dark:border-surface-700"
      data-testid="inbox-label-manager-empty"
    >
      <i class="pi pi-tag mb-2 text-2xl text-surface-300" />
      <p class="text-xs text-surface-400">{{ t('inbox.label.empty') }}</p>
    </div>

    <!-- ラベル一覧 -->
    <div v-else class="space-y-1.5">
      <div
        v-for="label in inboxStore.labels"
        :key="label.id"
        class="flex items-center gap-2 rounded-lg border border-surface-200 bg-surface-0 px-3 py-2 dark:border-surface-700 dark:bg-surface-900"
        :data-testid="`inbox-label-manager-row-${label.id}`"
      >
        <!-- カラードット -->
        <span
          class="inline-block h-3 w-3 shrink-0 rounded-full"
          :style="{ backgroundColor: label.color ?? '#94a3b8' }"
        />
        <!-- アイコン（任意） -->
        <i v-if="label.icon" :class="label.icon" class="shrink-0 text-xs text-surface-500" />
        <!-- 名前 -->
        <span class="flex-1 truncate text-sm">{{ label.name }}</span>
        <!-- 編集 -->
        <Button
          icon="pi pi-pencil"
          text
          rounded
          size="small"
          :aria-label="t('inbox.label.edit')"
          :data-testid="`inbox-label-manager-edit-${label.id}`"
          @click="openEditDialog(label)"
        />
        <!-- 削除 -->
        <Button
          icon="pi pi-trash"
          text
          rounded
          size="small"
          severity="danger"
          :aria-label="t('inbox.label.delete')"
          :data-testid="`inbox-label-manager-delete-${label.id}`"
          @click="openDeleteDialog(label)"
        />
      </div>
    </div>

    <!-- 作成・編集ダイアログ -->
    <Dialog
      v-model:visible="dialogVisible"
      modal
      :header="editTarget ? t('inbox.label.edit') : t('inbox.label.create')"
      class="w-full max-w-sm"
    >
      <div class="flex flex-col gap-4">
        <!-- 名前 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('inbox.label.nameLabel') }}
          </label>
          <InputText
            v-model="form.name"
            class="w-full"
            maxlength="50"
            :placeholder="t('inbox.label.namePlaceholder')"
            data-testid="inbox-label-form-name"
          />
        </div>
        <!-- 色 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('inbox.label.colorLabel') }}
          </label>
          <div class="flex items-center gap-3">
            <input
              v-model="form.color"
              type="color"
              class="h-8 w-8 cursor-pointer rounded border border-surface-200"
              data-testid="inbox-label-form-color"
            >
            <InputText
              v-model="form.color"
              class="flex-1 font-mono text-sm"
              maxlength="7"
              placeholder="#6366f1"
            />
          </div>
        </div>
        <!-- アイコン（PrimeIcons クラス名、任意） -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('inbox.label.iconLabel') }}
          </label>
          <InputText
            v-model="form.icon"
            class="w-full"
            placeholder="pi pi-tag"
            data-testid="inbox-label-form-icon"
          />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('button.cancel')"
            severity="secondary"
            text
            @click="closeDialog"
          />
          <Button
            :label="t('button.save')"
            :loading="saving"
            :disabled="!form.name.trim()"
            @click="handleSave"
          />
        </div>
      </template>
    </Dialog>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="deleteDialogVisible"
      modal
      :header="t('dialog.confirm_title')"
      class="w-full max-w-sm"
    >
      <p class="text-sm text-surface-700 dark:text-surface-300">
        {{ t('inbox.label.delete') }}: <strong>{{ deleteTarget?.name }}</strong>
      </p>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('button.cancel')"
            severity="secondary"
            text
            @click="closeDeleteDialog"
          />
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
