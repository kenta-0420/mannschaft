<script setup lang="ts">
/**
 * シフト管理 使い方説明モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは ShiftGuideContent コンポーネントを再利用。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('shift_guide.title')"
    class="w-full max-w-2xl"
    data-testid="shift-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <ShiftGuideContent />
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
