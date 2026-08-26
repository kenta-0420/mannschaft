<script setup lang="ts">
/** 利用規約をモーダルで表示。v-model:visible で開閉。本体は TermsContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
const { t } = useI18n()
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('landing.legal.terms.title')"
    class="w-full max-w-2xl"
    data-testid="terms-modal"
  >
    <div class="overflow-y-auto py-2" style="max-height: 70vh">
      <TermsContent />
    </div>
    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
