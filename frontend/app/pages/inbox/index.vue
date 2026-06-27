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
    <PageHeader :title="t('inbox.title')" help @help="showGuide = true" />

    <div class="mx-auto max-w-3xl">
      <InboxList />
    </div>

    <InboxGuideModal v-model:visible="showGuide" />
  </div>
</template>
