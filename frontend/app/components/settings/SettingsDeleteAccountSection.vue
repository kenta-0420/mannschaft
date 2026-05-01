<script setup lang="ts">
const emit = defineEmits<{
  showDeletionPreview: []
}>()

const confirm = useConfirm()

function confirmDelete() {
  confirm.require({
    message: 'アカウントを削除すると、全てのデータが完全に削除されます。本当に続けますか？',
    header: 'アカウント削除の確認',
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: 'キャンセル',
    acceptLabel: '削除の手続きへ進む',
    acceptClass: 'p-button-danger',
    accept: () => emit('showDeletionPreview'),
  })
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
      @click="confirmDelete"
    />
  </div>
</template>
