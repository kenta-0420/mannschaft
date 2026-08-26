<script setup lang="ts">
/**
 * 新着募集フィード 使い方説明モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは RecruitmentFeedGuideContent コンポーネントを再利用。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('recruitment.guide.feed.title')"
    class="w-full max-w-2xl"
    data-testid="recruitment-feed-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <RecruitmentFeedGuideContent />
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
