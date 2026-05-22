<script setup lang="ts">
import type { AnnouncementFeedItem } from '~/types/announcement'

const props = defineProps<{
  item: AnnouncementFeedItem
  showPinControl?: boolean
}>()

const emit = defineEmits<{
  click: [item: AnnouncementFeedItem]
  pin: [id: number]
  delete: [id: number]
}>()

const { t } = useI18n()
const router = useRouter()
const { formatDate, fromNow } = useDatetime()

/** ソース種別アイコン（PrimeIcons）のマップ */
const sourceTypeIconMap: Record<string, string> = {
  BLOG_POST: 'pi pi-book',
  BULLETIN_THREAD: 'pi pi-clipboard',
  TIMELINE_POST: 'pi pi-comments',
  CIRCULATION: 'pi pi-folder-open',
  SURVEY: 'pi pi-chart-bar',
}

/** 優先度バッジカラー */
const priorityColorMap: Record<string, string> = {
  URGENT: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
  IMPORTANT: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300',
  NORMAL: '',
}

const sourceIcon = computed(() => sourceTypeIconMap[props.item.sourceType] ?? 'pi pi-info-circle')
const priorityClass = computed(() => priorityColorMap[props.item.priority] ?? '')
const showPriorityBadge = computed(() => props.item.priority !== 'NORMAL')

/** F09.17: 広告フィードかどうか */
const isAd = computed(() => props.item.isAdvertisement === true)

/** F09.17: 広告アクション用メニュー */
const menuRef = ref()
const menuItems = computed(() => {
  if (!isAd.value) return []
  return [
    {
      label: t('advertising.actions.report'),
      icon: 'pi pi-flag',
      command: () => {
        reportModalVisible.value = true
      },
    },
    {
      label: t('advertising.actions.stop_ads'),
      icon: 'pi pi-ban',
      command: () => {
        router.push('/settings/ad-preferences')
      },
    },
  ]
})

function toggleMenu(event: MouseEvent) {
  menuRef.value?.toggle(event)
}

/** F09.17: 広告通報モーダル */
const reportModalVisible = ref(false)
const reportCampaignId = computed<string | null>(() => {
  if (!isAd.value || !props.item.messagingCampaignId) return null
  return props.item.messagingCampaignId
})

/** 相対時刻表示 */
function relativeTime(dateStr: string): string {
  return fromNow(dateStr) || formatDate(dateStr)
}

function handleClick() {
  emit('click', props.item)
}
</script>

<template>
  <div
    role="listitem"
    class="group flex cursor-pointer items-start gap-3 rounded-lg p-3 transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
    :class="{ 'opacity-60': item.isRead }"
    tabindex="0"
    @click="handleClick"
    @keydown.enter="handleClick"
    @keydown.space.prevent="handleClick"
  >
    <!-- ピン留めインジケーター -->
    <div class="mt-0.5 flex-shrink-0">
      <i v-if="item.isPinned" class="pi pi-map-marker text-sm text-primary" />
      <span v-else class="inline-block w-4" />
    </div>

    <!-- ソース種別アイコン -->
    <div class="mt-0.5 flex-shrink-0">
      <i :class="[sourceIcon, 'text-base text-surface-400']" />
    </div>

    <!-- メインコンテンツ -->
    <div class="min-w-0 flex-1">
      <!-- タイトル行 -->
      <div class="flex items-start justify-between gap-2">
        <div class="flex min-w-0 flex-1 items-start gap-2">
          <!-- F09.17: 広告ラベルバッジ（景品表示法対応） -->
          <AdLabelBadge v-if="isAd" size="sm" class="mt-0.5 flex-shrink-0" data-testid="ad-label-badge" />
          <p
            class="line-clamp-2 text-sm font-medium leading-snug"
            :class="item.isRead ? 'text-surface-500' : 'text-surface-900 dark:text-surface-0'"
          >
            {{ item.title }}
          </p>
        </div>
        <!-- 優先度バッジ -->
        <span
          v-if="showPriorityBadge"
          class="flex-shrink-0 rounded-full px-2 py-0.5 text-xs font-medium"
          :class="priorityClass"
        >
          {{ t(`announcement.priority.${item.priority}`) }}
        </span>
      </div>

      <!-- 抜粋 -->
      <p v-if="item.excerpt" class="mt-0.5 line-clamp-1 text-xs text-surface-400">
        {{ item.excerpt }}
      </p>

      <!-- メタ情報 -->
      <div class="mt-1 flex items-center gap-2 text-xs text-surface-400">
        <span>{{ t(`announcement.source_type.${item.sourceType}`) }}</span>
        <span v-if="item.author">• {{ item.author.displayName }}</span>
        <span>• {{ relativeTime(item.createdAt) }}</span>
        <span v-if="item.expiresAt" class="text-orange-500">
          {{ t('announcement.expires_at') }}: {{ formatDate(item.expiresAt) }}
        </span>
      </div>
    </div>

    <!-- F09.17: 広告アクションメニュー -->
    <div v-if="isAd" class="flex-shrink-0">
      <Button
        icon="pi pi-ellipsis-v"
        text
        rounded
        size="small"
        :aria-label="t('advertising.actions.report')"
        data-testid="ad-menu-trigger"
        @click.stop="toggleMenu"
      />
      <Menu ref="menuRef" :model="menuItems" :popup="true" />
    </div>

    <!-- 管理者アクション -->
    <div v-if="showPinControl" class="flex-shrink-0 opacity-0 transition-opacity group-hover:opacity-100">
      <Button
        :icon="item.isPinned ? 'pi pi-map-marker' : 'pi pi-map-marker'"
        :title="item.isPinned ? t('announcement.unpin') : t('announcement.pin')"
        :class="item.isPinned ? 'text-primary' : 'text-surface-400'"
        text
        rounded
        size="small"
        @click.stop="emit('pin', item.id)"
      />
      <Button
        icon="pi pi-times"
        :title="t('announcement.remove_from_announcements')"
        class="text-surface-400 hover:text-red-500"
        text
        rounded
        size="small"
        @click.stop="emit('delete', item.id)"
      />
    </div>

    <!-- 未読インジケーター -->
    <div v-if="!item.isRead" class="mt-2 flex-shrink-0">
      <span class="inline-block h-2 w-2 rounded-full bg-primary" />
    </div>

    <!-- F09.17: 通報モーダル（広告フィードのみマウント） -->
    <AdReportModal
      v-if="isAd && reportCampaignId"
      v-model:visible="reportModalVisible"
      :campaign-id="reportCampaignId"
      @click.stop
    />
  </div>
</template>
