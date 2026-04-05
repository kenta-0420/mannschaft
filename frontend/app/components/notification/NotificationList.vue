<script setup lang="ts">
import type { NotificationResponse } from '~/types/notification'

const { getNotifications, markAsRead, markAsUnread, markAllAsRead } = useNotificationApi()
const { showError } = useNotification()
const router = useRouter()
const { relativeTime } = useRelativeTime()

const notifications = ref<NotificationResponse[]>([])
const loading = ref(false)
const nextCursor = ref<number | null>(null)
const hasNext = ref(false)
const filter = ref<'all' | 'unread'>('all')

async function loadNotifications(cursor?: number) {
  loading.value = true
  try {
    const res = await getNotifications({
      cursor,
      isRead: filter.value === 'unread' ? false : undefined,
    })
    if (!cursor) {
      notifications.value = res.data
    } else {
      notifications.value.push(...res.data)
    }
    nextCursor.value = res.meta.nextCursor
    hasNext.value = res.meta.hasNext
  } catch {
    showError('通知の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function onToggleRead(notif: NotificationResponse) {
  try {
    if (notif.isRead) {
      await markAsUnread(notif.id)
      notif.isRead = false
    } else {
      await markAsRead(notif.id)
      notif.isRead = true
    }
  } catch {
    showError('操作に失敗しました')
  }
}

async function onMarkAllRead() {
  try {
    await markAllAsRead()
    notifications.value.forEach(n => n.isRead = true)
  } catch {
    showError('一括既読に失敗しました')
  }
}

function onClickNotification(notif: NotificationResponse) {
  if (!notif.isRead) {
    markAsRead(notif.id)
    notif.isRead = true
  }
  if (notif.actionUrl) {
    router.push(notif.actionUrl)
  }
}

function getPriorityColor(priority: string): string {
  switch (priority) {
    case 'URGENT': return 'text-red-600'
    case 'HIGH': return 'text-orange-500'
    default: return 'text-surface-500'
  }
}

function getIcon(sourceType: string): string {
  switch (sourceType) {
    case 'SCHEDULE': return 'pi pi-calendar'
    case 'CHAT_MESSAGE': return 'pi pi-comment'
    case 'TIMELINE_POST': return 'pi pi-comments'
    case 'BLOG_POST': return 'pi pi-book'
    case 'SYSTEM': return 'pi pi-info-circle'
    default: return 'pi pi-bell'
  }
}

watch(filter, () => loadNotifications())
onMounted(() => loadNotifications())

defineExpose({ refresh: () => loadNotifications() })
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <SelectButton
          v-model="filter"
          :options="[
            { label: 'すべて', value: 'all' },
            { label: '未読', value: 'unread' },
          ]"
          option-label="label"
          option-value="value"
        />
      </div>
      <Button label="すべて既読にする" text size="small" @click="onMarkAllRead" />
    </div>

    <!-- 通知一覧 -->
    <div class="flex flex-col">
      <button
        v-for="notif in notifications"
        :key="notif.id"
        class="flex items-start gap-3 border-b border-surface-100 px-4 py-3 text-left transition-colors hover:bg-surface-50"
        :class="notif.isRead ? 'opacity-60' : ''"
        @click="onClickNotification(notif)"
      >
        <!-- 未読ドット -->
        <div class="mt-2 flex shrink-0 items-center">
          <div
            v-if="!notif.isRead"
            class="h-2 w-2 rounded-full bg-primary"
          />
          <div v-else class="h-2 w-2" />
        </div>

        <!-- アイコン -->
        <div class="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-surface-100">
          <i :class="getIcon(notif.sourceType)" class="text-sm text-surface-500" />
        </div>

        <!-- 内容 -->
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <p class="text-sm font-medium" :class="getPriorityColor(notif.priority)">
              {{ notif.title }}
            </p>
            <span v-if="notif.scopeName" class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500">
              {{ notif.scopeName }}
            </span>
          </div>
          <p v-if="notif.body" class="mt-0.5 truncate text-xs text-surface-400">
            {{ notif.body }}
          </p>
          <div class="mt-1 flex items-center gap-2 text-xs text-surface-400">
            <span v-if="notif.actor">{{ notif.actor.displayName }}</span>
            <span>{{ relativeTime(notif.createdAt) }}</span>
          </div>
        </div>

        <!-- 既読/未読トグル -->
        <button
          class="mt-1 shrink-0 p-1 text-surface-300 hover:text-surface-600"
          :title="notif.isRead ? '未読にする' : '既読にする'"
          @click.stop="onToggleRead(notif)"
        >
          <i :class="notif.isRead ? 'pi pi-envelope' : 'pi pi-check'" class="text-xs" />
        </button>
      </button>
    </div>

    <!-- 空状態 -->
    <div v-if="!loading && notifications.length === 0" class="py-12 text-center">
      <i class="pi pi-bell-slash mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">通知はありません</p>
    </div>

    <!-- もっと読む -->
    <div v-if="hasNext" class="flex justify-center py-4">
      <Button label="もっと読む" text :loading="loading" @click="loadNotifications(nextCursor!)" />
    </div>

    <div v-if="loading && notifications.length === 0" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>
  </div>
</template>
