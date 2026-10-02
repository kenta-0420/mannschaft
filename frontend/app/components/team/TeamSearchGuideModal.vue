<script setup lang="ts">
/** チーム検索画面の使い方を案内するモーダル。 */
const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('teamHub.guide.title')"
    class="w-full max-w-2xl"
    data-testid="team-search-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <TeamSearchGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
