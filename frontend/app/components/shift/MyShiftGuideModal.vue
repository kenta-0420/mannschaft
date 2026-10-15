<script setup lang="ts">
/**
 * マイシフト 使い方説明モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは MyShiftGuideContent コンポーネントを再利用。
 */

const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('my_shift_guide.title')"
    class="w-full max-w-2xl"
    data-testid="my-shift-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <MyShiftGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
