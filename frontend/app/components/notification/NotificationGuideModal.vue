<script setup lang="ts">
/**
 * 通知の使い方説明モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは NotificationGuideContent コンポーネントを再利用。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('notification.guide.title')"
    class="w-full max-w-2xl"
    data-testid="notification-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <NotificationGuideContent />
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
