<script setup lang="ts">
const emit = defineEmits<{
  showDeletionPreview: []
}>()

const { t } = useI18n()
const confirm = useConfirm()

function confirmDelete() {
  confirm.require({
    message: t('settings.delete_account.confirm_message'),
    header: t('settings.delete_account.confirm_dialog_title'),
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: t('settings.delete_account.confirm_reject_label'),
    acceptLabel: t('settings.delete_account.confirm_accept_label'),
    acceptClass: 'p-button-danger',
    accept: () => emit('showDeletionPreview'),
  })
}
</script>

<template>
  <div
    class="rounded-xl border border-red-200 bg-surface-0 p-6 dark:border-red-900 dark:bg-surface-800"
  >
    <h2 class="mb-2 text-lg font-semibold text-red-600">{{ $t('settings.delete_account.section_title') }}</h2>
    <p class="mb-4 text-sm text-surface-500">
      {{ $t('settings.delete_account.description') }}
    </p>
    <Button
      translate="no"
      :label="$t('settings.delete_account.delete_button')"
      icon="pi pi-trash"
      severity="danger"
      outlined
      @click="confirmDelete"
    />
  </div>
</template>
