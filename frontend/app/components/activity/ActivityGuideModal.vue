<script setup lang="ts">
/**
 * 活動記録ページの使い方を案内するモーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは ActivityGuideContent コンポーネントに分離。
 */
const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('activity.guide.title')"
    class="w-full max-w-2xl"
    data-testid="activity-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <ActivityGuideContent />
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
