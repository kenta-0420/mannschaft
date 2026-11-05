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
  markOrganizationItemsRead,
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
      // チーム項目は従来どおり team read-all。組織項目は取得済み一覧の未読だけを組織ごとに read-batch へ送る
      // （組織の read-all は表示していない告知まで既読にしてしまうため呼ばない）。
      const teamScopes = new Set(feed.value.filter(item => item.scopeType === 'TEAM').map(item => item.scopeId))
      const unreadByOrganization = new Map<string, number[]>()
      for (const item of feed.value) {
        if (item.scopeType !== 'ORGANIZATION' || item.isRead) continue
        const key = String(item.scopeId)
        unreadByOrganization.set(key, [...(unreadByOrganization.get(key) ?? []), item.id])
      }
      await Promise.all([
        ...[...teamScopes].map(teamId => api(`/api/v1/teams/${teamId}/announcements/read-all`, { method: 'POST' })),
        ...[...unreadByOrganization].map(([organizationId, ids]) => markOrganizationItemsRead(organizationId, ids)),
      ])
      emit('refresh')
    }
  } catch (error) { handleApiError(error) }
}
</script>

<template>
  <!-- 高さ上限（既定 15rem）はインライン style のためスマホ幅だけ !important で外す。
       スマホ幅は見出しが2段になり、上限のままだと一覧が1件ぶんに潰れる -->
  <DashboardWidgetCard data-announcement-list class="max-sm:!max-h-none">
    <!-- ヘッダー -->
    <template #header>
      <!-- 狭い幅（スマホ等）では見出し群とボタン群が2段に折り返す。各ラベルは語の途中で折らない -->
      <div data-announcement-header-row class="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
        <div data-announcement-title-group class="flex flex-wrap items-center gap-2">
          <h3 tabindex="-1" data-announcement-heading class="whitespace-nowrap font-semibold text-surface-700 dark:text-surface-200">
            {{ t('announcement.widget_title') }}
          </h3>
          <span
            v-if="unreadCount > 0"
            data-announcement-unread-badge
            class="whitespace-nowrap rounded-full bg-primary px-2 py-0.5 text-xs font-bold text-white"
          >
            {{ t('announcement.unread_count', { count: unreadCount }) }}
          </span>
        </div>
        <div data-announcement-actions class="flex flex-wrap items-center gap-1">
          <Button
            v-if="unreadCount > 0"
            :label="t('announcement.mark_all_read')"
            size="small"
            text
            class="min-h-11 whitespace-nowrap text-xs sm:min-h-0"
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
          <NuxtLink v-if="allAnnouncementsPath" :to="allAnnouncementsPath" class="whitespace-nowrap max-sm:hidden">
            <Button
              :label="t('announcement.all_announcements')"
              icon="pi pi-arrow-right"
              icon-pos="right"
              size="small"
              text
              class="min-h-11 whitespace-nowrap text-xs sm:min-h-0"
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

    <!-- スマホ幅では「すべてのお知らせ」を一覧の後ろへ移し、見出しを2段に抑える -->
    <div v-if="allAnnouncementsPath" data-announcement-footer-link class="mt-2 flex justify-end sm:hidden">
      <NuxtLink :to="allAnnouncementsPath" class="whitespace-nowrap">
        <Button
          :label="t('announcement.all_announcements')"
          icon="pi pi-arrow-right"
          icon-pos="right"
          size="small"
          text
          class="min-h-11 whitespace-nowrap text-xs"
        />
      </NuxtLink>
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
