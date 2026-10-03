<script setup lang="ts">
/** F05.5 ファイル共有セキュリティの使い方モーダル。v-model:visible で開閉。本体は FileSharingGuideContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
const { t } = useI18n()
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('file_sharing.guide.title')"
    class="w-full max-w-2xl"
    data-testid="file-sharing-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <FileSharingGuideContent />
    </div>
    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
