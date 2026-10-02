<script setup lang="ts">
/** プライバシーポリシーをモーダルで表示。v-model:visible で開閉。本体は PrivacyContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
const { t } = useI18n()
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('landing.legal.privacy.title')"
    class="w-full max-w-2xl"
    data-testid="privacy-modal"
  >
    <div class="overflow-y-auto py-2" style="max-height: 70vh">
      <PrivacyContent />
    </div>
    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
