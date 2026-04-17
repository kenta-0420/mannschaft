<script setup lang="ts">
import type { AnnouncementScopeType } from '~/types/announcement'

const props = defineProps<{
  scopeType: AnnouncementScopeType
  scopeId: number
  /** 表示件数（デフォルト 5） */
  limit?: number
}>()

const { t } = useI18n()
const router = useRouter()

const { feed, meta, loading, error, fetchFeed, togglePin, deleteAnnouncement, markAsRead, markAllAsRead } =
  useAnnouncementFeed(props.scopeType, props.scopeId)

const { isAdmin } = useRoleAccess(
  props.scopeType === 'TEAM' ? 'team' : 'organization',
  props.scopeId,
)

const displayLimit = computed(() => props.limit ?? 5)

/** ピン留めアイテムを先頭 + 残りを最大 displayLimit 件表示 */
const pinnedItems = computed(() => feed.value.filter(item => item.isPinned).slice(0, 3))
const normalItems = computed(() =>
  feed.value.filter(item => !item.isPinned).slice(0, displayLimit.value),
)
const displayItems = computed(() => [...pinnedItems.value, ...normalItems.value])

const unreadCount = computed(() => meta.value?.unreadCount ?? 0)

/** 全件ページへの遷移パス */
const allAnnouncementsPath = computed(() => {
  if (props.scopeType === 'TEAM') return `/teams/${props.scopeId}/announcements`
  return `/organizations/${props.scopeId}/announcements`
})

onMounted(() => {
  fetchFeed({ limit: displayLimit.value + 3 })
})

/** アイテムクリック: 既読マーク → 元コンテンツへ遷移 */
async function onItemClick(item: (typeof feed.value)[number]) {
  if (!item.isRead) {
    await markAsRead(item.id)
  }
  router.push(item.sourceUrl)
}

async function onTogglePin(id: number) {
  await togglePin(id)
}

async function onDelete(id: number) {
  await deleteAnnouncement(id)
}

async function onMarkAllRead() {
  await markAllAsRead()
}
</script>

<template>
  <DashboardWidgetCard>
    <!-- ヘッダー -->
    <template #header>
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2">
          <span class="font-semibold text-surface-700 dark:text-surface-200">
            {{ t('announcement.widget_title') }}
          </span>
          <span
            v-if="unreadCount > 0"
            class="rounded-full bg-primary px-2 py-0.5 text-xs font-bold text-white"
          >
            {{ t('announcement.unread_count', { count: unreadCount }) }}
          </span>
        </div>
        <div class="flex items-center gap-1">
          <Button
            v-if="unreadCount > 0"
            :label="t('announcement.mark_all_read')"
            size="small"
            text
            class="text-xs"
            @click="onMarkAllRead"
          />
          <Button
            icon="pi pi-refresh"
            text
            rounded
            size="small"
            class="text-surface-400"
            :title="t('button.loading')"
            @click="fetchFeed({ limit: displayLimit + 3 })"
          />
          <NuxtLink :to="allAnnouncementsPath">
            <Button
              :label="t('announcement.all_announcements')"
              icon="pi pi-arrow-right"
              icon-pos="right"
              size="small"
              text
              class="text-xs"
            />
          </NuxtLink>
        </div>
      </div>
    </template>

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- エラー -->
    <div v-else-if="error" class="py-4 text-center text-sm text-red-500">
      {{ error }}
    </div>

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="displayItems.length === 0"
      icon="pi pi-bell"
      :message="t('announcement.empty')"
    />

    <!-- お知らせ一覧 -->
    <div v-else role="list" class="divide-y divide-surface-100 dark:divide-surface-700">
      <AnnouncementAnnouncementItem
        v-for="item in displayItems"
        :key="item.id"
        :item="item"
        :show-pin-control="isAdmin"
        @click="onItemClick"
        @pin="onTogglePin"
        @delete="onDelete"
      />
    </div>
  </DashboardWidgetCard>
</template>
