<script setup lang="ts">
/**
 * F02.6 チームお知らせ一覧ページ。
 *
 * チームのお知らせを全件表示する。カーソルページング対応。
 * ADMIN の場合: ピン留め・削除・お知らせ追加ボタンを表示。
 *
 * 権限: チームメンバー以上（middleware: auth で保護）
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = Number(route.params.id)

const { isAdmin, loadPermissions } = useRoleAccess('team', teamId)

const {
  feed,
  meta,
  loading,
  error,
  fetchFeed,
  togglePin,
  deleteAnnouncement,
  markAsRead,
  markAllAsRead,
} = useAnnouncementFeed('TEAM', teamId)

const confirmDialog = useConfirm()

onMounted(async () => {
  await loadPermissions()
  await fetchFeed({ limit: 20 })
})

/** 次のページを読み込む */
async function loadMore() {
  if (!meta.value?.hasNext || !meta.value?.nextCursor) return
  await fetchFeed({
    cursor: meta.value.nextCursor,
    limit: 20,
  })
}

/** アイテムクリック: 既読マーク → 元コンテンツへ遷移 */
async function onItemClick(item: (typeof feed.value)[number]) {
  if (!item.isRead) {
    await markAsRead(item.id)
  }
  navigateTo(item.sourceUrl)
}

/** 全件既読 */
async function onMarkAllRead() {
  await markAllAsRead()
}

/** ピン留め切り替え */
async function onTogglePin(id: number) {
  await togglePin(id)
}

/** 削除確認ダイアログ表示 */
function onDeleteConfirm(id: number) {
  confirmDialog.require({
    message: t('announcement.remove_from_announcements'),
    header: t('dialog.confirm_title'),
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    accept: async () => {
      await deleteAnnouncement(id)
    },
  })
}
</script>

<template>
  <div class="mx-auto max-w-3xl p-4">
    <!-- ヘッダー -->
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="t('announcement.widget_title')">
        <span class="text-sm text-surface-400">{{ t('announcement.all_announcements') }}</span>
      </PageHeader>
    </div>

    <!-- アクションバー -->
    <div class="mb-4 flex items-center justify-between">
      <span v-if="meta" class="text-sm text-surface-500">
        {{ t('announcement.unread_count', { count: meta.unreadCount }) }}
      </span>
      <Button
        v-if="meta && meta.unreadCount > 0"
        :label="t('announcement.mark_all_read')"
        icon="pi pi-check-circle"
        size="small"
        outlined
        @click="onMarkAllRead"
      />
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading && feed.length === 0" />

    <!-- エラー -->
    <div v-else-if="error" class="py-8 text-center text-sm text-red-500">
      {{ error }}
    </div>

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="feed.length === 0"
      icon="pi pi-bell"
      :message="t('announcement.empty')"
    />

    <!-- 一覧 -->
    <div v-else>
      <SectionCard>
        <div role="list" class="divide-y divide-surface-100 dark:divide-surface-700">
          <AnnouncementAnnouncementItem
            v-for="item in feed"
            :key="item.id"
            :item="item"
            :show-pin-control="isAdmin"
            @click="onItemClick"
            @pin="onTogglePin"
            @delete="onDeleteConfirm"
          />
        </div>
      </SectionCard>

      <!-- もっと見るボタン -->
      <div v-if="meta?.hasNext" class="mt-4 flex justify-center">
        <Button
          :label="t('button.next')"
          icon="pi pi-chevron-down"
          outlined
          :loading="loading"
          @click="loadMore"
        />
      </div>
    </div>

    <ConfirmDialog />
  </div>
</template>
