<script setup lang="ts">
/**
 * F08.9 件2/件3 見守り画面の使い方説明モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは GuardianWatchGuideContent コンポーネントを再利用する
 * （参考: components/project/ProjectGuideModal.vue）。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('proxy.guardianship.watch.guide.title')"
    class="w-full max-w-2xl"
    data-testid="guardian-watch-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <GuardianWatchGuideContent />
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
