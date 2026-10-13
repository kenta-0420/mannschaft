<script setup lang="ts">
/**
 * バックアップコード表示ダイアログ（2FA 有効化・再生成の直後に一度だけ見せる）。
 *
 * コードは再取得できないため、「保存した」にチェックするまで閉じられない（AC-3）。
 */
const props = defineProps<{
  visible: boolean
  codes: string[]
}>()

const emit = defineEmits<{
  close: []
}>()

const { t } = useI18n()
const notification = useNotification()

const saved = ref(false)

// 開き直すたびにチェックを外す（前回の確認を引き継がない）
watch(
  () => props.visible,
  (v) => {
    if (v) saved.value = false
  },
)

async function copyAll() {
  try {
    await navigator.clipboard.writeText(props.codes.join('\n'))
    notification.success(t('settings.security.two_fa.backup.copied'))
  } catch {
    notification.error(t('settings.security.two_fa.backup.copy_failed'))
  }
}

function download() {
  const blob = new Blob([props.codes.join('\n') + '\n'], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'mannschaft-backup-codes.txt'
  a.click()
  URL.revokeObjectURL(url)
}

function close() {
  if (!saved.value) return
  emit('close')
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="$t('settings.security.backup_codes_dialog_title')"
    :modal="true"
    :closable="false"
    :close-on-escape="false"
    class="w-full max-w-md"
    data-testid="two-fa-backup-codes-dialog"
  >
    <p class="mb-4 text-sm text-surface-500">
      {{ $t('settings.security.backup_codes_description') }}
    </p>
    <div class="grid grid-cols-2 gap-2">
      <code
        v-for="code in codes"
        :key="code"
        class="rounded bg-surface-100 px-3 py-2 text-center text-sm dark:bg-surface-700"
        data-testid="two-fa-backup-code"
      >{{ code }}</code>
    </div>
    <div class="mt-4 flex flex-wrap gap-2">
      <Button
        translate="no"
        :label="$t('settings.security.two_fa.backup.copy_button')"
        icon="pi pi-copy"
        severity="secondary"
        size="small"
        data-testid="two-fa-backup-copy"
        @click="copyAll"
      />
      <Button
        translate="no"
        :label="$t('settings.security.two_fa.backup.download_button')"
        icon="pi pi-download"
        severity="secondary"
        size="small"
        data-testid="two-fa-backup-download"
        @click="download"
      />
    </div>
    <div class="mt-4 flex items-center gap-2">
      <Checkbox v-model="saved" input-id="twoFaBackupSaved" binary data-testid="two-fa-backup-saved" />
      <label for="twoFaBackupSaved" class="text-sm">{{ $t('settings.security.two_fa.backup.saved_check') }}</label>
    </div>
    <div class="mt-4 flex justify-end">
      <Button
        translate="no"
        :label="$t('button.close')"
        :disabled="!saved"
        data-testid="two-fa-backup-close"
        @click="close"
      />
    </div>
  </Dialog>
</template>
