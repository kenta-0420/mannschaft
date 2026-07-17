<script setup lang="ts">
/**
 * ニュースレター設定画面の使い方を案内するモーダル。v-model:visible で開閉。
 * 本体は VillageNewsletterGuideContent に分離（VillageRecruitCategoryGuideModal と同じ金型）。
 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('village.newsletterGuide.page_title')"
    class="w-full max-w-2xl"
    data-testid="village-newsletter-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <VillageNewsletterGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
