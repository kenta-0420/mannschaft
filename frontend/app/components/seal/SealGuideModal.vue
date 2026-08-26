<script setup lang="ts">
/** 電子印鑑の使い方を案内するモーダル。v-model:visible で開閉。本体は SealGuideContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('settings.seal_guide.title')"
    class="w-full max-w-2xl"
    data-testid="seal-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <SealGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
