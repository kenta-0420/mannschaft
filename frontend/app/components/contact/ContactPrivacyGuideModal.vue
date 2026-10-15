<script setup lang="ts">
/**
 * 連絡先プライバシー設定の使い方モーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは ContactPrivacyGuideContent コンポーネントを再利用。
 */

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('contact_privacy.guide.page_title')"
    class="w-full max-w-2xl"
    data-testid="contact-privacy-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <ContactPrivacyGuideContent />
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
