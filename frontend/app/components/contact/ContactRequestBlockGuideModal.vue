<script setup lang="ts">
/** 申請事前拒否リストの使い方を案内するモーダル。v-model:visible で開閉。本体は ContactRequestBlockGuideContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('contact_request_blocks_help.title')"
    class="w-full max-w-2xl"
    data-testid="contact-request-blocks-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <ContactRequestBlockGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
