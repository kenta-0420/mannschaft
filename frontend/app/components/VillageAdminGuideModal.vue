<script setup lang="ts">
/**
 * 村長コンソール（`/villages/[id]/admin`）の使い方を案内するモーダル。v-model:visible で開閉。
 * 本体は VillageAdminGuideContent に分離（VillageJoinRequestGuideModal と同じ金型）。
 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('village.adminGuide.page_title')"
    class="w-full max-w-2xl"
    data-testid="village-admin-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <VillageAdminGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
