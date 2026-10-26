<script setup lang="ts">
/**
 * 村参加申請ページの使い方を案内するモーダル。v-model:visible で開閉。
 * 本体は VillageJoinRequestGuideContent に分離（VillageGuideModal と同じ金型）。
 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('village.joinRequestGuide.page_title')"
    class="w-full max-w-2xl"
    data-testid="village-join-request-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <VillageJoinRequestGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
