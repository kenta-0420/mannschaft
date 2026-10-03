<script setup lang="ts">
/**
 * アクセス解析 使い方説明モーダル（F10.8）。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは AnalyticsGuideContent コンポーネントを再利用。
 * 参考: components/project/ProjectGuideModal.vue
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('analytics.guide.page_title')"
    class="w-full max-w-2xl"
    data-testid="analytics-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <AnalyticsGuideContent />
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
