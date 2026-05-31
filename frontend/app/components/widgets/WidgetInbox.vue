<script setup lang="ts">
/**
 * F04.11 統合通知インボックス — ダッシュボードウィジェット。
 *
 * 設計書: docs/features/F04.11_notification_inbox/04_security_operations.md §4 UX
 * 手本: WidgetNotices.vue / WidgetFavorites.vue / DashboardWidgetCard
 *
 * 件数サマリ（受信箱 / スヌーズ中）を表示し /inbox へ遷移する。
 */
const { t } = useI18n()
const inboxStore = useInboxStore()
const { captureQuiet } = useErrorReport()

async function load() {
  try {
    await inboxStore.fetchSummary()
  } catch (error) {
    captureQuiet(error, { context: 'WidgetInbox: サマリ取得' })
  }
}

onMounted(() => {
  load()
})
</script>

<template>
  <DashboardWidgetCard
    :title="t('inbox.title')"
    icon="pi pi-inbox"
    to="/inbox"
    :loading="inboxStore.summaryLoading"
    refreshable
    @refresh="load"
  >
    <div v-if="!inboxStore.summaryLoading">
      <!-- 受信箱が空かつスヌーズ中もゼロの場合の空状態 -->
      <DashboardEmptyState
        v-if="inboxStore.inboxCount === 0 && inboxStore.snoozedCount === 0"
        icon="pi pi-inbox"
        :message="t('inbox.empty')"
      />

      <!-- 件数サマリ表示 -->
      <template v-else>
        <!-- 受信箱 件数 -->
        <div class="flex items-center justify-between py-2">
          <div class="flex items-center gap-2 text-sm">
            <i class="pi pi-bell text-primary" />
            <span>{{ t('inbox.summary.inbox') }}</span>
          </div>
          <Badge
            v-if="inboxStore.inboxCount > 0"
            :value="inboxStore.inboxCount"
            severity="danger"
          />
          <span v-else class="text-xs text-surface-400">0</span>
        </div>

        <!-- スヌーズ中 件数 -->
        <div class="flex items-center justify-between border-t border-surface-100 py-2 dark:border-surface-700">
          <div class="flex items-center gap-2 text-sm">
            <i class="pi pi-clock text-amber-500" />
            <span>{{ t('inbox.summary.snoozed') }}</span>
          </div>
          <span class="text-xs text-surface-500">{{ inboxStore.snoozedCount }}</span>
        </div>

        <!-- /inbox へのリンク -->
        <NuxtLink
          to="/inbox"
          class="mt-2 block text-center text-xs text-primary hover:underline"
        >
          {{ t('inbox.title') }} →
        </NuxtLink>
      </template>
    </div>
  </DashboardWidgetCard>
</template>
