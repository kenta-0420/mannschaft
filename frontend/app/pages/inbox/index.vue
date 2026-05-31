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
const router = useRouter()
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <Button
        icon="pi pi-arrow-left"
        text
        rounded
        :aria-label="t('common.back')"
        @click="router.back()"
      />
      <h1 class="text-2xl font-bold">{{ t('inbox.title') }}</h1>
    </div>

    <div class="mx-auto max-w-3xl">
      <InboxList />
    </div>
  </div>
</template>
