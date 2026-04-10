<script setup lang="ts">
/**
 * F11.1 PWA: 未送信件数バッジ + 同期進捗インジケーター。
 *
 * ヘッダーのナビゲーション領域に配置し、オフラインキューに
 * PENDING / FAILED の項目がある場合にバッジを表示する。
 */
const { t } = useI18n()
const syncStore = useSyncStore()

const hasPending = computed(() => syncStore.pendingCount > 0)
</script>

<template>
  <div v-if="hasPending || syncStore.syncInProgress" class="relative inline-flex items-center">
    <!-- 同期中のスピナー -->
    <template v-if="syncStore.syncInProgress">
      <span class="flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400">
        <i class="pi pi-spin pi-spinner" />
        {{ t('sync.in_progress') }}
      </span>
    </template>

    <!-- 未送信バッジ -->
    <template v-else-if="hasPending">
      <span
        class="inline-flex items-center gap-1 rounded-full bg-orange-100 px-2.5 py-0.5 text-xs font-medium text-orange-800 dark:bg-orange-900 dark:text-orange-200"
      >
        <i class="pi pi-cloud-upload text-xs" />
        {{ t('sync.pending_badge', { count: syncStore.pendingCount }) }}
      </span>
    </template>
  </div>
</template>
