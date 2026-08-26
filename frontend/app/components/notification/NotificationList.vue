<script setup lang="ts">
import type { NotificationResponse } from '~/types/notification'
import type { SnoozePreset } from '~/utils/snoozePreset'
import { computeSnoozeUntil } from '~/utils/snoozePreset'

const { getNotifications, markAsRead, markAsUnread, markAllAsRead, snooze } = useNotificationApi()
const { confirmNotification, getNotificationDetail } = useConfirmableNotificationApi()
const { confirmClosure } = useEmergencyClosureApi()
const { showError } = useNotification()
const { t } = useI18n()
const router = useRouter()
const { relativeTime } = useRelativeTime()
const authStore = useAuthStore()
const toast = useToast()

const notifications = ref<NotificationResponse[]>([])
const notifBadgeCount = useState<number>('badge:notifCount', () => 0)
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

// ─── スヌーズ ─────────────────────────────────
/** スヌーズメニューの PrimeVue Menu ref（notif.id をキーとして管理）*/
const snoozeMenuRefs = ref<Record<number, InstanceType<typeof import('primevue/menu').default> | null>>({})

/** スヌーズプリセット定義（表示ラベルは i18n）*/
const SNOOZE_PRESETS: SnoozePreset[] = ['in3h', 'tonight', 'tomorrowMorning', 'nextWeek']

function getSnoozeMenuItems(notif: NotificationResponse) {
  return SNOOZE_PRESETS.map((preset) => ({
    label: t(`inbox.snoozePreset.${preset}`),
    icon: 'pi pi-clock',
    command: () => onSnooze(notif, preset),
  }))
}

function toggleSnoozeMenu(event: MouseEvent, notif: NotificationResponse) {
  const menuRef = snoozeMenuRefs.value[notif.id]
  if (menuRef) {
    menuRef.toggle(event)
  }
}

/** スヌーズ実行（楽観更新）*/
async function onSnooze(notif: NotificationResponse, preset: SnoozePreset) {
  const tz = authStore.user?.timezone ?? 'Asia/Tokyo'
  const snoozedUntil = computeSnoozeUntil(preset, tz)

  // 楽観更新: 一覧から除去
  const idx = notifications.value.findIndex((n) => n.id === notif.id)
  if (idx >= 0) {
    notifications.value.splice(idx, 1)
  }

  try {
    await snooze(notif.id, snoozedUntil)
    toast.add({
      severity: 'success',
      summary: t('inbox.action.snoozed'),
      life: 3000,
    })
  }
  catch {
    // ロールバック: 除去した通知を元の位置に戻す
    if (idx >= 0) {
      notifications.value.splice(idx, 0, notif)
    }
    showError(t('inbox.action.snoozeFailed'))
  }
}

// ─── 通知読み込み ──────────────────────────────

async function loadNotifications(cursor?: number) {
  loading.value = true
  try {
    const res = await getNotifications({
      cursor,
      isRead: filter.value === 'unread' ? false : undefined,
    })
    if (!cursor) {
      notifications.value = res.data
    }
    else {
      notifications.value.push(...res.data)
    }
    nextCursor.value = res.meta.nextCursor
    hasNext.value = res.meta.hasNext
    // 確認通知のサマリ（未確認件数等）を並列取得（権限が無いものは静かにスキップ）
    await loadConfirmableSummariesForList()
  }
  catch {
    showError('通知の取得に失敗しました')
  }
  finally {
    loading.value = false
  }
}

async function onToggleRead(notif: NotificationResponse) {
  try {
    if (notif.isRead) {
      await markAsUnread(notif.id)
      notif.isRead = false
    }
    else {
      await markAsRead(notif.id)
      notif.isRead = true
    }
  }
  catch {
    showError('操作に失敗しました')
  }
}

async function onMarkAllRead() {
  try {
    await markAllAsRead()
    notifications.value.forEach((n) => (n.isRead = true))
    notifBadgeCount.value = 0 // ベルバッジを即時リセット
  }
  catch {
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
    case 'EMERGENCY_CLOSURE': return 'pi pi-exclamation-triangle'
    default: return 'pi pi-bell'
  }
}

/** 確認通知かどうか判定する */
function isConfirmableNotification(notif: NotificationResponse): boolean {
  return notif.sourceType === 'CONFIRMABLE_NOTIFICATION'
}

/** 緊急休業通知かどうか判定する */
function isEmergencyClosureNotification(notif: NotificationResponse): boolean {
  return notif.sourceType === 'EMERGENCY_CLOSURE'
}

/**
 * 緊急休業通知の「確認する」ボタンをクリックした時の処理。
 * scopeId（チームslug: string）と sourceId（closureId: number）を使って
 * useEmergencyClosureApi().confirmClosure(teamId, closureId) を呼ぶ。
 */
async function onConfirmEmergencyClosure(notif: NotificationResponse) {
  if (!notif.scopeId || !notif.sourceId) {
    showError(t('emergency_closure.error.info_missing'))
    return
  }
  try {
    await confirmClosure(notif.scopeId, notif.sourceId)
    await markAsRead(notif.id)
    notif.isRead = true
    toast.add({
      severity: 'success',
      summary: t('emergency_closure.confirm_success'),
      life: 3000,
    })
    // 一覧を再取得して確認状態を反映
    await loadNotifications()
  }
  catch {
    showError(t('emergency_closure.confirm_failed'))
  }
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
  }
  catch {
    // 認可エラー等は無視（メンバーが閲覧権限を持たないケース）
  }
}

/** 通知一覧読み込み後に確認通知のサマリを並列取得する */
async function loadConfirmableSummariesForList() {
  const targets = notifications.value.filter((n) => isConfirmableNotification(n) && n.sourceId)
  await Promise.all(targets.map((n) => loadConfirmableSummary(n)))
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
    toast.add({ severity: 'success', summary: '確認しました', life: 3000 })
    // 一覧を再取得
    await loadNotifications()
  }
  catch {
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
    <!--
      行全体を <button> にすると、内側のスヌーズボタン・既読トグルボタン・確認ボタンが
      HTML 仕様上の "nested interactive" になり不正。
      <div role="button"> に変えることで、行クリックの挙動を保ちながらネストを回避する。
      AnnouncementItem.vue と同パターン。
    -->
    <div class="flex flex-col">
      <div
        v-for="notif in notifications"
        :key="notif.id"
        role="button"
        tabindex="0"
        class="flex items-start gap-3 border-b border-surface-100 px-4 py-3 text-left transition-colors hover:bg-surface-50"
        :class="[
          notif.isRead ? 'opacity-60' : '',
          isConfirmableNotification(notif) ? 'bg-amber-50 hover:bg-amber-100' : '',
          isEmergencyClosureNotification(notif) ? 'bg-red-50 hover:bg-red-100' : '',
        ]"
        @click="onClickNotification(notif)"
        @keydown.enter="onClickNotification(notif)"
        @keydown.space.prevent="onClickNotification(notif)"
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
          :class="[
            isConfirmableNotification(notif) ? 'bg-amber-200' : '',
            isEmergencyClosureNotification(notif) ? 'bg-red-100' : '',
            (!isConfirmableNotification(notif) && !isEmergencyClosureNotification(notif)) ? 'bg-surface-100' : '',
          ]"
        >
          <i
            :class="getIcon(notif.sourceType)"
            class="text-sm"
            :style="{
              color: isConfirmableNotification(notif) ? '#d97706'
                : isEmergencyClosureNotification(notif) ? '#dc2626'
                : undefined,
            }"
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
            <!-- 緊急休業通知バッジ -->
            <span
              v-if="isEmergencyClosureNotification(notif)"
              class="rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700"
            >
              {{ $t('emergency_closure.badge') }}
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

          <!-- 確認済みラベル（CONFIRMABLE_NOTIFICATION） -->
          <div
            v-else-if="isConfirmableNotification(notif) && notif.isRead"
            class="mt-1"
          >
            <span class="text-xs text-surface-400">
              <i class="pi pi-check mr-1 text-green-500" />
              {{ $t('confirmable.already_confirmed') }}
            </span>
          </div>

          <!-- 「確認する」ボタン（EMERGENCY_CLOSURE かつ未確認の場合） -->
          <div
            v-if="isEmergencyClosureNotification(notif) && !notif.isRead"
            class="mt-2"
          >
            <Button
              :label="$t('emergency_closure.confirm_button')"
              size="small"
              severity="danger"
              icon="pi pi-check"
              @click.stop="onConfirmEmergencyClosure(notif)"
            />
          </div>

          <!-- 確認済みラベル（EMERGENCY_CLOSURE） -->
          <div
            v-else-if="isEmergencyClosureNotification(notif) && notif.isRead"
            class="mt-1"
          >
            <span class="text-xs text-surface-400">
              <i class="pi pi-check mr-1 text-green-500" />
              {{ $t('emergency_closure.already_confirmed') }}
            </span>
          </div>
        </div>

        <!-- アクションボタン群（スヌーズ + 既読トグル）-->
        <div class="mt-1 flex shrink-0 items-center gap-1">
          <!-- スヌーズボタン（ヒット領域44x44。アイコン視覚サイズはtext-xsのまま維持） -->
          <button
            class="inline-flex min-h-11 min-w-11 items-center justify-center p-1 text-surface-300 hover:text-primary"
            :aria-label="$t('inbox.action.snooze')"
            :title="$t('inbox.action.snooze')"
            @click.stop="toggleSnoozeMenu($event, notif)"
          >
            <i class="pi pi-clock text-xs" />
          </button>
          <!-- スヌーズプリセットメニュー（PrimeVue Menu popup） -->
          <Menu
            :ref="(el) => { snoozeMenuRefs[notif.id] = el as InstanceType<typeof import('primevue/menu').default> | null }"
            :model="getSnoozeMenuItems(notif)"
            :popup="true"
          />

          <!-- 既読/未読トグル（ヒット領域44x44。アイコン視覚サイズはtext-xsのまま維持） -->
          <button
            class="inline-flex min-h-11 min-w-11 items-center justify-center p-1 text-surface-300 hover:text-surface-600"
            :title="notif.isRead ? '未読にする' : '既読にする'"
            @click.stop="onToggleRead(notif)"
          >
            <i :class="notif.isRead ? 'pi pi-envelope' : 'pi pi-check'" class="text-xs" />
          </button>
        </div>
      </div>
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
      <LoadingBounce />
    </div>
  </div>
</template>
