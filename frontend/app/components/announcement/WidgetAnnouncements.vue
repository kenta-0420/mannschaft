<script setup lang="ts">
import type { AnnouncementFeedItem, AnnouncementScopeType } from '~/types/announcement'
import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useApi } from '~/composables/useApi'
import { useErrorHandler } from '~/composables/useErrorHandler'
import { useAnnouncementFeed } from '~/composables/useAnnouncementFeed'
import { useAnnouncementPreview } from '~/composables/useAnnouncementPreview'
import { useRoleAccess } from '~/composables/useRoleAccess'

const props = defineProps<{
  scopeType: AnnouncementScopeType
  scopeId: string
  /** 表示件数（デフォルト 5） */
  limit?: number
  initialItems?: AnnouncementFeedItem[]
  scopeSlug?: string
}>()
const emit = defineEmits<{ refresh: []; unavailable: [id: number] }>()

const { t } = useI18n()
const router = useRouter()
const api = useApi()
const { handleApiError } = useErrorHandler()

const {
  feed,
  meta,
  loading,
  error,
  fetchFeed,
  togglePin,
  deleteAnnouncement,
  markAsReadBeforeOpen,
  markAllAsRead,
  setReadLocally,
  removeFromFeedLocally,
  setFeedItemsLocally,
} = useAnnouncementFeed(props.scopeType, props.scopeId)
const preview = useAnnouncementPreview({
  onRead: item => setReadLocally(item.id),
  onUnavailable: item => {
    removeFromFeedLocally(item.id)
    emit('unavailable', item.id)
  },
})

const { isAdmin } = useRoleAccess(
  props.scopeType === 'TEAM' ? 'team' : 'organization',
  props.scopeSlug ?? props.scopeId,
)

const displayLimit = computed(() => props.limit ?? 5)

/** ピン留めアイテムを先頭 + 残りを最大 displayLimit 件表示 */
const pinnedItems = computed(() => feed.value.filter(item => item.isPinned).slice(0, 3))
const normalItems = computed(() =>
  feed.value.filter(item => !item.isPinned).slice(0, displayLimit.value),
)
const displayItems = computed(() => [...pinnedItems.value, ...normalItems.value])

const unreadCount = computed(() => props.initialItems !== undefined
  ? feed.value.filter(item => !item.isRead).length
  : meta.value?.unreadCount ?? 0)

/** 全件ページへの遷移パス */
const allAnnouncementsPath = computed(() => {
  if (props.scopeType === 'TEAM' && props.scopeSlug) return `/teams/${props.scopeSlug}/announcements`
  return null
})

watch(() => props.initialItems, value => {
  if (value !== undefined) setFeedItemsLocally(value.map(item => ({ ...item })))
}, { immediate: true })

function refresh(): void {
  if (props.initialItems !== undefined) emit('refresh')
  else void fetchFeed({ limit: displayLimit.value + 3 })
}

onMounted(() => {
  if (props.initialItems === undefined) refresh()
})

/** アイテムクリック: 既読マーク → 元コンテンツへ遷移 */
async function onItemClick(item: (typeof feed.value)[number], trigger: HTMLElement) {
  if (item.contentPreviewAvailable) {
    await preview.open(item, trigger)
    return
  }
  if (!item.sourceUrl) return
  // #2495: 期限切れ・削除で既読 API が ANNOUNCE_001 を返した場合は遷移せず、
  // 一覧から取り除いてトーストで知らせる（判定は composable 側に一元化）。
  const canOpen = await markAsReadBeforeOpen(item)
  if (!canOpen) {
    if (!feed.value.some(entry => entry.id === item.id)) emit('unavailable', item.id)
    return
  }
  router.push(item.sourceUrl)
}

async function onTogglePin(id: number) {
  try { await togglePin(id) } catch (error) { handleApiError(error) }
}

async function onDelete(id: number) {
  try { await deleteAnnouncement(id) } catch (error) { handleApiError(error) }
}

async function onMarkAllRead() {
  try {
    if (props.initialItems === undefined) await markAllAsRead()
    else {
      const scopes = new Set(feed.value.map(item => `${item.scopeType === 'TEAM' ? 'teams' : 'organizations'}/${item.scopeId}`))
      await Promise.all([...scopes].map(scope => api(`/api/v1/${scope}/announcements/read-all`, { method: 'POST' })))
      emit('refresh')
    }
  } catch (error) { handleApiError(error) }
}
</script>

<template>
  <DashboardWidgetCard data-announcement-list>
    <!-- ヘッダー -->
    <template #header>
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2">
          <span tabindex="-1" data-announcement-heading class="font-semibold text-surface-700 dark:text-surface-200">
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
            @click="refresh"
          />
          <NuxtLink v-if="allAnnouncementsPath" :to="allAnnouncementsPath">
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
    <DashboardErrorState v-else-if="error" :message="error" @retry="refresh" />

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="displayItems.length === 0"
      icon="pi pi-bell"
      :message="t('announcement.empty')"
    />

    <!-- お知らせ一覧 -->
    <div v-else role="list" class="divide-y divide-surface-100 dark:divide-surface-700">
      <AnnouncementItem
        v-for="item in displayItems"
        :key="item.id"
        :item="item"
        :show-pin-control="isAdmin && item.scopeType === scopeType && String(item.scopeId) === scopeId"
        @click="onItemClick"
        @pin="onTogglePin"
        @delete="onDelete"
      />
    </div>
  </DashboardWidgetCard>
  <AnnouncementDetailModal
    :state="preview.state.value"
    :preview="preview.preview.value"
    :item-title="preview.item.value?.title ?? ''"
    :error="preview.error.value"
    :trigger="preview.trigger.value"
    @close="preview.close"
    @retry="preview.retry"
    @displayed="preview.markDisplayed"
  />
</template>
