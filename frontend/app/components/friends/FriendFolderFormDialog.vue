<script setup lang="ts">
/**
 * F01.5 フレンドフォルダ 作成 / 編集 兼用ダイアログ。
 *
 * 使い分け:
 * - {@code folder} prop が {@code null} / {@code undefined} → 作成モード（createFolder を呼ぶ）
 * - {@code folder} prop が指定あり → 編集モード（updateFolder を呼ぶ）
 *
 * UX:
 * - 名前: 必須、最大 50 文字
 * - 説明: 任意、最大 300 文字
 * - 色: プリセットパレット 10 色から選択（デフォルト #6B7280 gray）
 *
 * エラーハンドリング:
 * - 409 （フォルダ上限超過 / 名前衝突）は専用メッセージをトースト表示
 * - その他のエラーは {@link useErrorHandler.handleApiError} で処理
 */
import type {
  CreateFolderRequest,
  TeamFriendFolderView,
  UpdateFolderRequest,
} from '~/types/friendFolders'

const props = defineProps<{
  modelValue: boolean
  teamId: number
  /** 指定ありで編集モード、null/undefined で作成モード */
  folder?: TeamFriendFolderView | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'saved': [folder: TeamFriendFolderView]
}>()

const { t } = useI18n()
const { createFolder, updateFolder } = useFriendFoldersApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

/** フォルダ色プリセット（設計書 §7 参照・デフォルトは gray） */
const COLOR_PRESETS = [
  '#6B7280', // gray（デフォルト）
  '#EF4444', // red
  '#F97316', // orange
  '#EAB308', // yellow
  '#10B981', // green
  '#14B8A6', // teal
  '#3B82F6', // blue
  '#6366F1', // indigo
  '#8B5CF6', // purple
  '#EC4899', // pink
] as const

const DEFAULT_COLOR = '#6B7280'
const NAME_MAX = 50
const DESCRIPTION_MAX = 300

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const isEdit = computed(() => props.folder != null)

// ----- フォーム状態 -----
const form = reactive({
  name: '',
  description: '',
  color: DEFAULT_COLOR,
})

const submitting = ref(false)

// ----- バリデーション -----
const nameError = computed(() => {
  const trimmed = form.name.trim()
  if (!trimmed) return t('folders.messages.name_required')
  if (trimmed.length > NAME_MAX) return t('folders.messages.name_too_long')
  return ''
})

const descriptionError = computed(() => {
  if (form.description.length > DESCRIPTION_MAX) {
    return t('folders.messages.description_too_long')
  }
  return ''
})

const canSubmit = computed(
  () => !nameError.value && !descriptionError.value && !submitting.value,
)

// ----- ダイアログ開閉時のリセット -----
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      if (props.folder) {
        form.name = props.folder.name
        form.description = props.folder.description ?? ''
        form.color = props.folder.color || DEFAULT_COLOR
      }
      else {
        form.name = ''
        form.description = ''
        form.color = DEFAULT_COLOR
      }
    }
  },
)

// ----- 409 エラー判定 -----
function is409(error: unknown): boolean {
  const apiError = error as { statusCode?: number; status?: number }
  return apiError?.statusCode === 409 || apiError?.status === 409
}

// ----- 送信 -----
async function handleSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    let saved: TeamFriendFolderView
    if (isEdit.value && props.folder) {
      const req: UpdateFolderRequest = {
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        color: form.color,
      }
      saved = await updateFolder(props.teamId, props.folder.id, req)
      notification.success(t('folders.messages.updated'))
    }
    else {
      const req: CreateFolderRequest = {
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        color: form.color,
      }
      saved = await createFolder(props.teamId, req)
      notification.success(t('folders.messages.created'))
    }
    emit('saved', saved)
    visible.value = false
  }
  catch (error) {
    if (is409(error)) {
      // 作成時は上限超過 or 名前衝突、編集時は名前衝突
      if (!isEdit.value) {
        notification.error(t('dialog.error'), t('folders.messages.max_exceeded'))
      }
      else {
        notification.error(t('dialog.error'), t('folders.messages.name_conflict'))
      }
    }
    else {
      handleApiError(error)
    }
  }
  finally {
    submitting.value = false
  }
}

function handleCancel() {
  visible.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="isEdit ? t('folders.actions.edit') : t('folders.create')"
    :modal="true"
    :closable="!submitting"
    :style="{ width: '460px' }"
  >
    <div class="flex flex-col gap-4">
      <!-- フォルダ名 -->
      <label class="flex flex-col gap-1">
        <span class="text-sm font-medium">
          {{ t('folders.fields.name') }}
          <span class="text-red-500">*</span>
        </span>
        <InputText
          v-model="form.name"
          class="w-full"
          :placeholder="t('folders.placeholders.name')"
          :maxlength="NAME_MAX"
          :invalid="!!nameError && form.name.length > 0"
        />
        <small v-if="nameError && form.name.length > 0" class="text-red-500">
          {{ nameError }}
        </small>
        <small class="text-xs text-surface-400">
          {{ form.name.length }} / {{ NAME_MAX }}
        </small>
      </label>

      <!-- 説明 -->
      <label class="flex flex-col gap-1">
        <span class="text-sm font-medium">{{ t('folders.fields.description') }}</span>
        <Textarea
          v-model="form.description"
          class="w-full"
          :rows="3"
          :placeholder="t('folders.placeholders.description')"
          :maxlength="DESCRIPTION_MAX"
          :invalid="!!descriptionError"
        />
        <small v-if="descriptionError" class="text-red-500">
          {{ descriptionError }}
        </small>
        <small class="text-xs text-surface-400">
          {{ form.description.length }} / {{ DESCRIPTION_MAX }}
        </small>
      </label>

      <!-- 色（プリセット選択） -->
      <div class="flex flex-col gap-2">
        <span class="text-sm font-medium">{{ t('folders.fields.color') }}</span>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="preset in COLOR_PRESETS"
            :key="preset"
            type="button"
            class="h-8 w-8 rounded-full border-2 transition hover:scale-110 focus:outline-none focus:ring-2 focus:ring-primary-400 focus:ring-offset-2"
            :class="form.color === preset
              ? 'border-surface-900 dark:border-surface-100'
              : 'border-surface-200 dark:border-surface-700'"
            :style="{ backgroundColor: preset }"
            :aria-label="preset"
            :aria-pressed="form.color === preset"
            @click="form.color = preset"
          >
            <i
              v-if="form.color === preset"
              class="pi pi-check text-xs text-white"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('button.cancel')"
        text
        :disabled="submitting"
        @click="handleCancel"
      />
      <Button
        :label="isEdit ? t('button.save') : t('button.create')"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="handleSubmit"
      />
    </template>
  </Dialog>
</template>
