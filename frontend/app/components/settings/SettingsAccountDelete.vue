<script setup lang="ts">
const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()

const showDeleteDialog = ref(false)

async function deleteAccount() {
  try {
    await api('/api/v1/users/me', { method: 'DELETE' })
    authStore.logout()
    navigateTo('/login')
  } catch {
    notification.error('アカウントの削除に失敗しました')
  }
}
</script>

<template>
  <div
    class="rounded-xl border border-red-200 bg-surface-0 p-6 dark:border-red-900 dark:bg-surface-800"
  >
    <h2 class="mb-2 text-lg font-semibold text-red-600">アカウント削除</h2>
    <p class="mb-4 text-sm text-surface-500">
      アカウントを削除すると、全てのデータが完全に削除されます。この操作は取り消せません。
    </p>
    <Button
      label="アカウントを削除"
      icon="pi pi-trash"
      severity="danger"
      outlined
      @click="showDeleteDialog = true"
    />
  </div>

  <Dialog
    v-model:visible="showDeleteDialog"
    header="アカウント削除の確認"
    :modal="true"
    class="w-full max-w-md"
  >
    <p class="mb-4">
      本当にアカウントを削除しますか？全てのデータが完全に削除され、復元できません。
    </p>
    <div class="flex justify-end gap-2">
      <Button label="キャンセル" severity="secondary" @click="showDeleteDialog = false" />
      <Button label="削除する" severity="danger" @click="deleteAccount" />
    </div>
  </Dialog>
</template>
