<script setup lang="ts">
/** チーム加盟の設定画面の使い方を案内するモーダル。v-model:visible で開閉。本体は TeamAffiliationGuideContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('teamAffiliationGuide.title')"
    class="w-full max-w-2xl"
    data-testid="team-affiliation-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <TeamAffiliationGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
