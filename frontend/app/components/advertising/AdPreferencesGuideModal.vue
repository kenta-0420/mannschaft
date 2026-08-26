<script setup lang="ts">
/**
 * 広告の受信設定 使い方ガイド モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは AdPreferencesGuideContent コンポーネントを再利用。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('advertising.settings_ad_preferences_guide.page_title')"
    class="w-full max-w-2xl"
    data-testid="ad-preferences-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <AdPreferencesGuideContent />
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
