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
    case 'EMERGENCY_CLOSURE': return 'pi pi-exclamation-triangle'
    case 'SCHEDULE': return 'pi pi-calendar'
    case 'CHAT_MESSAGE': return 'pi pi-comment'
    case 'TIMELINE_POST': return 'pi pi-comments'
    case 'BLOG_POST': return 'pi pi-book'
    case 'SYSTEM': return 'pi pi-info-circle'
    default: return 'pi pi-bell'
  }
}

function isEmergency(sourceType: string): boolean {
  return sourceType === 'EMERGENCY_CLOSURE'
}

// 臨時休業確認追跡
const closureApi = useEmergencyClosureApi()
const confirmedClosureIds = ref(new Set<number>())
const confirmingId = ref<number | null>(null)

async function confirmEmergency(notif: NotificationResponse) {
  if (!notif.sourceId || !notif.scopeId) return
  confirmingId.value = notif.sourceId
  try {
    await closureApi.confirmClosure(notif.scopeId, notif.sourceId)
    confirmedClosureIds.value.add(notif.sourceId)
  }
  catch {
    // 既に確認済みの場合も成功扱い（サーバー側で冪等処理）
    confirmedClosureIds.value.add(notif.sourceId!)
  }
  finally {
    confirmingId.value = null
  }
}

function isEmergencyConfirmed(notif: NotificationResponse): boolean {
  return notif.sourceId != null && confirmedClosureIds.value.has(notif.sourceId)
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
        class="flex items-start gap-3 border-b px-4 py-3 text-left transition-colors"
        :class="[
          notif.isRead ? 'opacity-60' : '',
          isEmergency(notif.sourceType)
            ? 'border-l-4 border-l-red-500 border-b-surface-100 bg-red-50 hover:bg-red-100'
            : 'border-b-surface-100 hover:bg-surface-50',
        ]"
        @click="onClickNotification(notif)"
      >
        <!-- 未読ドット -->
        <div class="mt-2 flex shrink-0 items-center">
          <div
            v-if="!notif.isRead"
            class="h-2 w-2 rounded-full"
            :class="isEmergency(notif.sourceType) ? 'bg-red-500' : 'bg-primary'"
          />
          <div v-else class="h-2 w-2" />
        </div>

        <!-- アイコン -->
        <div
          class="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full"
          :class="isEmergency(notif.sourceType) ? 'bg-red-100' : 'bg-surface-100'"
        >
          <i
            :class="getIcon(notif.sourceType)"
            class="text-sm"
            :style="isEmergency(notif.sourceType) ? 'color: #dc2626' : ''"
          />
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
          <!-- 臨時休業: 確認ボタン / 確認済みバッジ -->
          <div v-if="isEmergency(notif.sourceType)" class="mt-2">
            <button
              v-if="!isEmergencyConfirmed(notif)"
              class="inline-flex items-center gap-1.5 rounded-md bg-red-600 px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition hover:bg-red-700 disabled:opacity-50"
              :disabled="confirmingId === notif.sourceId"
              @click.stop="confirmEmergency(notif)"
            >
              <i class="pi pi-check text-xs" />
              {{ confirmingId === notif.sourceId ? '送信中...' : '確認しました' }}
            </button>
            <span
              v-else
              class="inline-flex items-center gap-1 text-xs font-medium text-green-600 dark:text-green-400"
            >
              <i class="pi pi-check-circle text-xs" />
              確認済み
            </span>
          </div>
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
