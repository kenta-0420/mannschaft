<script setup lang="ts">
/** 村一覧ページの使い方モーダル。v-model:visible で開閉。本体は VillageListGuideContent。 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('village.listGuide.page_title')"
    class="w-full max-w-2xl"
    data-testid="village-list-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <VillageListGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
