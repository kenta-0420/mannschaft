<script setup lang="ts">
/**
 * F11.1 PWA: 未送信件数バッジ + 同期進捗インジケーター + コンフリクトバッジ。
 *
 * ヘッダーのナビゲーション領域に配置し、オフラインキューに
 * PENDING / FAILED の項目がある場合にバッジを表示する。
 * 未解決コンフリクトがある場合は赤いバッジを表示する。
 */
const { t } = useI18n()
const syncStore = useSyncStore()

const hasPending = computed(() => syncStore.pendingCount > 0)
const hasConflicts = computed(() => syncStore.hasConflicts)
const showIndicator = computed(
  () => hasPending.value || syncStore.syncInProgress || hasConflicts.value,
)
</script>

<template>
  <div v-if="showIndicator" class="relative inline-flex items-center gap-2">
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

    <!-- コンフリクトバッジ -->
    <NuxtLink
      v-if="hasConflicts"
      to="/sync/conflicts"
      class="inline-flex items-center gap-1 rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-medium text-red-800 hover:bg-red-200 dark:bg-red-900 dark:text-red-200 dark:hover:bg-red-800"
    >
      <i class="pi pi-exclamation-triangle text-xs" />
      {{ t('sync.conflict_found', { count: syncStore.conflictCount }) }}
    </NuxtLink>
  </div>
</template>
