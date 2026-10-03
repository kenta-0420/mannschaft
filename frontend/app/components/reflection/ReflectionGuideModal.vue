<script setup lang="ts">
/**
 * アクティブリコール学習 使い方説明モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは ReflectionGuideContent コンポーネントを再利用。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('reflection.guide.page_title')"
    class="w-full max-w-2xl"
    data-testid="reflection-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <ReflectionGuideContent />
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
