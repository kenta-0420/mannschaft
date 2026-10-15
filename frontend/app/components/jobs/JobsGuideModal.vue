<script setup lang="ts">
/**
 * 求人（Worker向け）使い方説明モーダル。
 * v-model:visible で開閉を制御する。コンテンツは JobsGuideContent コンポーネントに分離。
 */
const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('jobmatching.guide.title')"
    class="w-full max-w-2xl"
    data-testid="jobs-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <JobsGuideContent />
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
