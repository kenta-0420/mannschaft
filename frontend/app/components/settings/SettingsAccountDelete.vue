<script setup lang="ts">
const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()
const { t } = useI18n()

const showDeleteDialog = ref(false)

async function deleteAccount() {
  try {
    await api('/api/v1/users/me', { method: 'DELETE' })
    authStore.logout()
    navigateTo('/login')
  } catch {
    notification.error(t('settings.delete_account.delete_error'))
  }
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
      @click="showDeleteDialog = true"
    />
  </div>

  <Dialog
    v-model:visible="showDeleteDialog"
    :header="$t('settings.delete_account.confirm_dialog_title')"
    :modal="true"
    class="w-full max-w-md"
  >
    <p class="mb-4">
      {{ $t('settings.delete_account.confirm_message') }}
    </p>
    <div class="flex justify-end gap-2">
      <Button translate="no" :label="$t('settings.delete_account.confirm_reject_label')" severity="secondary" @click="showDeleteDialog = false" />
      <Button translate="no" :label="$t('deletion_preview.delete_button')" severity="danger" @click="deleteAccount" />
    </div>
  </Dialog>
</template>
