<script setup lang="ts">
/**
 * マイコルクボード 使い方説明モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは CorkboardGuideContent コンポーネントを再利用。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('corkboard.guide.title')"
    class="w-full max-w-2xl"
    data-testid="corkboard-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <CorkboardGuideContent />
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
