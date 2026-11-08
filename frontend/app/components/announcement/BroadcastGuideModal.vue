<script setup lang="ts">
/** 告知ウィザードの使い方。全ステップから同じガイドを開く。 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('announcement.broadcast_guide.toggle')"
    class="w-[95vw] max-w-2xl"
    :pt="{ pcCloseButton: { root: { class: 'min-h-11 min-w-11' } } }"
    data-testid="broadcast-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <BroadcastGuideContent />
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        class="min-h-11 min-w-11"
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
