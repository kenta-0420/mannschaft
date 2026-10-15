<script setup lang="ts">
/**
 * F09.19.4b 運用型キャンペーン（バナー広告）使い方モーダル。
 *
 * v-model:visible で開閉を制御する。コンテンツは OperationalCampaignGuideContent を再利用。
 * 参考: components/analytics/AnalyticsGuideModal.vue
 */
const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('advertising.operational_campaigns.guide.title')"
    class="w-full max-w-2xl"
    data-testid="operational-campaign-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <OperationalCampaignGuideContent />
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
