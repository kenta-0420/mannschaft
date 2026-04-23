<script setup lang="ts">
import type { NotificationResponse } from '~/types/notification'

const { getNotifications, markAsRead, markAsUnread, markAllAsRead } = useNotificationApi()
const { confirmNotification, getNotificationDetail } = useConfirmableNotificationApi()
const { showError } = useNotification()
const router = useRouter()
const { relativeTime } = useRelativeTime()

const notifications = ref<NotificationResponse[]>([])
const loading = ref(false)
const nextCursor = ref<number | null>(null)
const hasNext = ref(false)
const filter = ref<'all' | 'unread'>('all')

/** 確認通知の sourceId をキーとした未確認サマリのキャッシュ */
interface UnconfirmedSummary {
  unconfirmedCount: number
  totalRecipientCount: number
  /** 公開範囲。ALL_MEMBERS かつメンバーでも閲覧可能な場合に未確認数を表示する */
  visibility: import('~/types/confirmable').UnconfirmedVisibility
}
const confirmableSummaries = ref<Record<number, UnconfirmedSummary>>({})

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
    // 確認通知のサマリ（未確認件数等）を並列取得（権限が無いものは静かにスキップ）
    await loadConfirmableSummariesForList()
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
    case 'CONFIRMABLE_NOTIFICATION': return 'pi pi-check-circle'
    default: return 'pi pi-bell'
  }
}

/** 確認通知かどうか判定する */
function isConfirmableNotification(notif: NotificationResponse): boolean {
  return notif.sourceType === 'CONFIRMABLE_NOTIFICATION'
}

/** 確認通知の期限日を取得する（sourceIdを使って別途取得する場合のプレースホルダ。現状はbodyから判断） */
function getConfirmableDeadlineFromBody(_notif: NotificationResponse): string | null {
  // NotificationResponseにdeadlineAtは含まれないため、
  // actionUrlや本文に期限情報がある場合は表示する
  // 将来的にAPIレスポンスに追加されたら更新すること
  return null
}

/**
 * 確認通知の詳細を取得して未確認サマリをキャッシュする。
 * 認可分岐により、メンバーで閲覧不可の場合（HIDDEN/CREATOR_AND_ADMIN）はサーバが 403 を返すので
 * その場合はキャッシュに保存しない（無視して通常表示にフォールバック）。
 */
async function loadConfirmableSummary(notif: NotificationResponse) {
  if (!notif.sourceId || notif.sourceId in confirmableSummaries.value) return
  const scopeType = notif.scopeType
  if (!notif.scopeId || (scopeType !== 'TEAM' && scopeType !== 'ORGANIZATION')) return
  try {
    const res = await getNotificationDetail(scopeType, notif.scopeId, notif.sourceId)
    const detail = res.data
    confirmableSummaries.value[notif.sourceId] = {
      unconfirmedCount: detail.totalRecipientCount - detail.confirmedCount,
      totalRecipientCount: detail.totalRecipientCount,
      visibility: detail.unconfirmedVisibility,
    }
  } catch {
    // 認可エラー等は無視（メンバーが閲覧権限を持たないケース）
  }
}

/** 通知一覧読み込み後に確認通知のサマリを並列取得する */
async function loadConfirmableSummariesForList() {
  const targets = notifications.value.filter(n => isConfirmableNotification(n) && n.sourceId)
  await Promise.all(targets.map(n => loadConfirmableSummary(n)))
}

/** 通知に紐づく未確認サマリを取得する（テンプレートから参照） */
function getConfirmableSummary(notif: NotificationResponse): UnconfirmedSummary | null {
  if (!notif.sourceId) return null
  return confirmableSummaries.value[notif.sourceId] ?? null
}

/** 確認通知の「確認する」ボタンをクリックした時の処理 */
async function onConfirmNotification(notif: NotificationResponse) {
  if (!notif.scopeType || !notif.scopeId || !notif.sourceId) {
    showError('確認通知の情報が不足しています')
    return
  }
  // scopeTypeをTEAM/ORGANIZATIONに変換（PersonalやSystemには来ない想定）
  const scopeType = notif.scopeType as 'TEAM' | 'ORGANIZATION'
  if (scopeType !== 'TEAM' && scopeType !== 'ORGANIZATION') {
    showError('スコープが不正です')
    return
  }
  try {
    await confirmNotification(scopeType, notif.scopeId, notif.sourceId)
    await markAsRead(notif.id)
    notif.isRead = true
    const toast = useToast()
    toast.add({ severity: 'success', summary: '確認しました', life: 3000 })
    // 一覧を再取得
    await loadNotifications()
  } catch (err) {
    console.error('確認通知の確認処理に失敗しました', err)
    showError('確認処理に失敗しました')
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
        :class="[
          notif.isRead ? 'opacity-60' : '',
          isConfirmableNotification(notif) ? 'bg-amber-50 hover:bg-amber-100' : '',
        ]"
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
        <div
          class="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full"
          :class="isConfirmableNotification(notif) ? 'bg-amber-200' : 'bg-surface-100'"
        >
          <i
            :class="getIcon(notif.sourceType)"
            class="text-sm"
            :style="isConfirmableNotification(notif) ? 'color: #d97706' : ''"
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
            <!-- 確認通知バッジ -->
            <span
              v-if="isConfirmableNotification(notif)"
              class="rounded bg-amber-200 px-1.5 py-0.5 text-xs font-medium text-amber-800"
            >
              {{ $t('confirmable.title') }}
            </span>
          </div>
          <p v-if="notif.body" class="mt-0.5 truncate text-xs text-surface-400">
            {{ notif.body }}
          </p>
          <div class="mt-1 flex items-center gap-2 text-xs text-surface-400">
            <span v-if="notif.actor">{{ notif.actor.displayName }}</span>
            <span>{{ relativeTime(notif.createdAt) }}</span>
            <!-- 期限カウントダウン（将来的にAPIにdeadlineAtが追加された場合に対応） -->
            <span
              v-if="isConfirmableNotification(notif) && getConfirmableDeadlineFromBody(notif)"
              class="font-medium text-amber-600"
            >
              <i class="pi pi-clock mr-0.5" />
              {{ $t('confirmable.deadline') }}: {{ getConfirmableDeadlineFromBody(notif) }}
            </span>
            <!-- 未確認件数サマリ（公開範囲が許す場合のみ表示） -->
            <span
              v-if="isConfirmableNotification(notif) && getConfirmableSummary(notif)"
              class="font-medium text-amber-700"
            >
              <i class="pi pi-users mr-0.5" />
              {{ $t('confirmable.unconfirmed_count', { count: getConfirmableSummary(notif)?.unconfirmedCount ?? 0 }) }}
            </span>
          </div>

          <!-- 「確認する」ボタン（CONFIRMABLE_NOTIFICATION かつ未確認の場合） -->
          <div
            v-if="isConfirmableNotification(notif) && !notif.isRead"
            class="mt-2"
          >
            <Button
              :label="$t('confirmable.confirm_button')"
              size="small"
              severity="warn"
              icon="pi pi-check"
              @click.stop="onConfirmNotification(notif)"
            />
          </div>

          <!-- 確認済みラベル -->
          <div
            v-else-if="isConfirmableNotification(notif) && notif.isRead"
            class="mt-1"
          >
            <span class="text-xs text-surface-400">
              <i class="pi pi-check mr-1 text-green-500" />
              {{ $t('confirmable.already_confirmed') }}
            </span>
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
