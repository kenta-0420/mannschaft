<script setup lang="ts">
/**
 * F04.11 統合通知インボックス — ページ。
 *
 * 設計書: docs/features/F04.11_notification_inbox/04_security_operations.md §1
 * 手本: pages/notifications/index.vue
 * 本体は InboxList コンポーネントに委譲する。
 */
definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const showGuide = ref(false)
</script>

<template>
  <div>
    <div class="mx-auto max-w-3xl p-6">
      <PageHeader :title="t('inbox.title')" help @help="showGuide = true" />
      <InboxList />
    </div>

    <InboxGuideModal v-model:visible="showGuide" />
  </div>
</template>
