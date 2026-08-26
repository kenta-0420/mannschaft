<script setup lang="ts">
/**
 * 募集カテゴリ管理画面の使い方を案内するモーダル。v-model:visible で開閉。
 * 本体は VillageRecruitCategoryGuideContent に分離（VillageAdminGuideModal と同じ金型）。
 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('village.recruitCategoryGuide.page_title')"
    class="w-full max-w-2xl"
    data-testid="village-recruit-category-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <VillageRecruitCategoryGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
