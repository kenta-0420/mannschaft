<script setup lang="ts">
import type { SharedFile, FileVisibilityRole } from '~/types/filesharing'

/**
 * F05.5 (B/C) ファイルのセキュリティ設定を編集するダイアログ。
 * 最低可視ロール・ダウンロード禁止・名称・説明を PATCH /api/v1/files/{id} で更新する。
 */
const props = defineProps<{ file: SharedFile }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ updated: [] }>()

const { updateFile } = useFileSharingApi()
const { showSuccess, showError } = useNotification()
const { t } = useI18n()

const name = ref('')
const description = ref('')
const minVisibleRole = ref<FileVisibilityRole | null>(null)
const downloadDisabled = ref(false)
const saving = ref(false)

// ダイアログを開くたびに対象ファイルの現在値でフォームを初期化する
watch(visible, (open) => {
  if (open) {
    name.value = props.file.fileName
    description.value = props.file.description ?? ''
    minVisibleRole.value = props.file.minVisibleRole ?? null
    downloadDisabled.value = props.file.downloadDisabled ?? false
  }
})

async function onSave() {
  if (!name.value.trim()) return
  saving.value = true
  try {
    await updateFile(props.file.id, {
      name: name.value.trim(),
      description: description.value.trim() || undefined,
      minVisibleRole: minVisibleRole.value ?? undefined,
      downloadDisabled: downloadDisabled.value,
    })
    showSuccess(t('file_sharing.settings.saved'))
    visible.value = false
    emit('updated')
  }
  catch {
    showError(t('file_sharing.settings.saveError'))
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('file_sharing.settings.title')"
    class="w-full max-w-md"
    data-testid="file-settings-dialog"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('file_sharing.settings.nameLabel') }}</label>
        <InputText v-model="name" class="w-full" data-testid="file-settings-name" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('file_sharing.settings.descriptionLabel') }}</label>
        <Textarea v-model="description" auto-resize rows="2" class="w-full" />
      </div>
      <FileSecurityFields
        v-model:min-visible-role="minVisibleRole"
        v-model:download-disabled="downloadDisabled"
      />
    </div>
    <template #footer>
      <Button :label="t('button.cancel')" text @click="visible = false" />
      <Button
        :label="t('button.save')"
        :loading="saving"
        :disabled="!name.trim()"
        data-testid="file-settings-save"
        @click="onSave"
      />
    </template>
  </Dialog>
</template>
