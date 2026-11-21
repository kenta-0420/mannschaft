<script setup lang="ts">
import type { NotificationResponse } from '~/types/notification'
import type { SnoozePreset } from '~/utils/snoozePreset'
import { computeSnoozeUntil } from '~/utils/snoozePreset'
import { getAuthSessionContext, type AuthSessionSnapshot } from '~/composables/authSessionContext'
import { useNotificationStore } from '~/stores/useNotificationStore'

const { getNotifications, markAsRead, markAsUnread, markAllAsRead, snooze } = useNotificationApi()
const { confirmNotification, confirmPersonalNotification, getNotificationDetail } =
  useConfirmableNotificationApi()
const { confirmClosure } = useEmergencyClosureApi()
const { showError } = useNotification()
const { captureQuiet } = useErrorReport()
const { t } = useI18n()
const router = useRouter()
const { relativeTime } = useRelativeTime()
const authStore = useAuthStore()
const session = getAuthSessionContext(authStore)
const notificationStore = useNotificationStore()
const toast = useToast()

const notifications = ref<NotificationResponse[]>([])
const notifBadgeCount = useState<number>('badge:notifCount', () => 0)
const loading = ref(false)
const nextPage = ref(0)
const hasNext = ref(false)
const filter = ref<'all' | 'unread'>('all')
let disposed = false
let viewRevision = 0
let mutationRevision = 0
let pendingPage: number | null = null
let refreshPending = false
let loadFlight: Promise<void> | null = null
function current(snapshot: AuthSessionSnapshot): boolean {
  return !disposed && session.current(snapshot)
}
function mutationSucceeded(): void {
  mutationRevision += 1
  if (loadFlight) refreshPending = true
}
function patchNotification(
  notif: NotificationResponse,
  patch: Partial<NotificationResponse>,
): void {
  Object.assign(notif, patch)
  const displayed = notifications.value.find((row) => row.id === notif.id)
  if (displayed && displayed !== notif) Object.assign(displayed, patch)
}

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
const snoozeMenuRefs = ref<
  Record<number, InstanceType<typeof import('primevue/menu').default> | null>
>({})

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
  const snapshot = session.capture()
  const revision = viewRevision
  const tz = authStore.user?.timezone ?? 'Asia/Tokyo'
  const snoozedUntil = computeSnoozeUntil(preset, tz)

  // 楽観更新: 一覧から除去
  const idx = notifications.value.findIndex((n) => n.id === notif.id)
  if (idx >= 0) {
    notifications.value.splice(idx, 1)
  }

  try {
    await snooze(notif.id, snoozedUntil)
    if (!current(snapshot)) return
    mutationSucceeded()
    notifications.value = notifications.value.filter((row) => row.id !== notif.id)
    toast.add({
      severity: 'success',
      summary: t('inbox.action.snoozed'),
      life: 3000,
    })
  } catch {
    if (!current(snapshot)) return
    // ロールバック: 除去した通知を元の位置に戻す
    if (
      revision === viewRevision &&
      idx >= 0 &&
      !notifications.value.some((row) => row.id === notif.id)
    ) {
      notifications.value.splice(idx, 0, notif)
    } else if (revision !== viewRevision) {
      requestRefresh()
    }
    showError(t('inbox.action.snoozeFailed'))
  }
}

// ─── 通知読み込み ──────────────────────────────

function loadNotifications(page = 0): Promise<void> {
  pendingPage = page
  return drainLoads()
}

function requestRefresh(): void {
  refreshPending = true
  void drainLoads()
}

function drainLoads(): Promise<void> {
  if (loadFlight) return loadFlight
  if (disposed || session.accountId === null) return Promise.resolve()
  loadFlight = (async () => {
    while (!disposed && session.accountId !== null && (pendingPage !== null || refreshPending)) {
      const page = pendingPage
      pendingPage = null
      if (page === 0 || page === null) refreshPending = false
      await fetchNotifications(page)
    }
  })().finally(() => {
    loadFlight = null
    if (!disposed && session.accountId !== null && (pendingPage !== null || refreshPending))
      void drainLoads()
  })
  return loadFlight
}

/** refreshは現在filterの取得済みpage深さをREST正本から再取得する。旧rowは継ぎ足さない。 */
async function fetchNotifications(page: number | null) {
  const snapshot = session.capture()
  const revision = viewRevision
  const mutation = mutationRevision
  const selectedFilter = filter.value
  const depth = Math.max(1, nextPage.value)
  const valid = () =>
    current(snapshot) && revision === viewRevision && selectedFilter === filter.value
  loading.value = true
  try {
    const pages = page === null ? Array.from({ length: depth }, (_, index) => index) : [page]
    const rows: NotificationResponse[] = []
    let totalPages = 0
    for (const requestedPage of pages) {
      const res = await getNotifications({
        page: requestedPage,
        size: 20,
        isRead: selectedFilter === 'unread' ? false : undefined,
      })
      if (!valid()) return
      if (mutation !== mutationRevision) {
        refreshPending = true
        return
      }
      rows.push(...res.data)
      totalPages = res.meta.totalPages
    }
    const combined = page !== null && page > 0 ? [...notifications.value, ...rows] : rows
    notifications.value = [...new Map(combined.map((row) => [row.id, row])).values()]
    nextPage.value = page === null ? depth : page + 1
    hasNext.value = nextPage.value < totalPages
    // 確認通知のサマリ（未確認件数等）を並列取得（権限が無いものは静かにスキップ）
    await loadConfirmableSummariesForList()
  } catch {
    if (valid()) showError(t('notification.list.loadError'))
  } finally {
    if (!disposed) loading.value = false
  }
}

async function onToggleRead(notif: NotificationResponse) {
  const snapshot = session.capture()
  try {
    if (notif.isRead) {
      await markAsUnread(notif.id)
      if (!current(snapshot)) return
      mutationSucceeded()
      patchNotification(notif, { isRead: false })
    } else {
      await markAsRead(notif.id)
      if (!current(snapshot)) return
      mutationSucceeded()
      patchNotification(notif, { isRead: true })
    }
  } catch {
    if (!current(snapshot)) return
    showError(t('notification.list.actionError'))
  }
}

async function onMarkAllRead() {
  const snapshot = session.capture()
  try {
    await markAllAsRead()
    if (!current(snapshot)) return
    mutationSucceeded()
    notifications.value.forEach((n) => (n.isRead = true))
    notifBadgeCount.value = 0 // ベルバッジを即時リセット
  } catch {
    if (!current(snapshot)) return
    showError(t('notification.list.markAllReadError'))
  }
}

function onClickNotification(notif: NotificationResponse) {
  const snapshot = session.capture()
  if (!notif.isRead) {
    // 遷移は待たない。既読化の失敗は静かに記録し、未読のまま残す（再クリックで再試行できる）。
    markAsRead(notif.id)
      .then(() => {
        if (!current(snapshot)) return
        mutationSucceeded()
        patchNotification(notif, { isRead: true })
      })
      .catch((error: unknown) => {
        if (!current(snapshot)) return
        captureQuiet(error, { context: 'NotificationList: 通知クリックの既読化' })
      })
  }
  if (notif.actionUrl) {
    router.push(notif.actionUrl)
  }
}

function getPriorityColor(priority: string): string {
  switch (priority) {
    case 'URGENT':
      return 'text-red-600'
    case 'HIGH':
      return 'text-orange-500'
    default:
      return 'text-surface-500'
  }
}

function getIcon(sourceType: string): string {
  switch (sourceType) {
    case 'SCHEDULE':
      return 'pi pi-calendar'
    case 'CHAT_MESSAGE':
      return 'pi pi-comment'
    case 'TIMELINE_POST':
      return 'pi pi-comments'
    case 'BLOG_POST':
      return 'pi pi-book'
    case 'SYSTEM':
      return 'pi pi-info-circle'
    case 'CONFIRMABLE_NOTIFICATION':
      return 'pi pi-check-circle'
    case 'EMERGENCY_CLOSURE':
      return 'pi pi-exclamation-triangle'
    default:
      return 'pi pi-bell'
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
  const snapshot = session.capture()
  if (!notif.scopeId || !notif.sourceId) {
    showError(t('emergency_closure.error.info_missing'))
    return
  }
  try {
    await confirmClosure(notif.scopeId, notif.sourceId)
    if (!current(snapshot)) return
    mutationSucceeded()
    // 確認は成功済み。既読化の失敗で「確認失敗」と表示しない。
    try {
      await markAsRead(notif.id)
      if (!current(snapshot)) return
      mutationSucceeded()
      patchNotification(notif, { isRead: true })
    } catch (error) {
      if (!current(snapshot)) return
      captureQuiet(error, { context: 'NotificationList: 緊急休業確認後の既読化' })
    }
    toast.add({
      severity: 'success',
      summary: t('emergency_closure.confirm_success'),
      life: 3000,
    })
    // 一覧を再取得して確認状態を反映
    await loadNotifications()
  } catch {
    if (!current(snapshot)) return
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
  const snapshot = session.capture()
  const revision = viewRevision
  if (!notif.sourceId || notif.sourceId in confirmableSummaries.value) return
  const scopeType = notif.scopeType
  if (!notif.scopeId || (scopeType !== 'TEAM' && scopeType !== 'ORGANIZATION')) return
  try {
    const res = await getNotificationDetail(scopeType, notif.scopeId, notif.sourceId)
    if (!current(snapshot) || revision !== viewRevision) return
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
  const snapshot = session.capture()
  if (!notif.scopeType || !notif.sourceId) {
    showError(t('notification.list.confirmInfoMissing'))
    return
  }
  const scopeType = notif.scopeType
  if (scopeType !== 'TEAM' && scopeType !== 'ORGANIZATION' && scopeType !== 'SYSTEM') {
    showError(t('notification.list.invalidScope'))
    return
  }
  try {
    if (scopeType === 'SYSTEM') {
      await confirmPersonalNotification(notif.sourceId)
    } else {
      if (!notif.scopeId) {
        showError(t('notification.list.confirmInfoMissing'))
        return
      }
      await confirmNotification(scopeType, notif.scopeId, notif.sourceId)
    }
    if (!current(snapshot)) return
    mutationSucceeded()
    patchNotification(notif, { isConfirmed: true })
    if (!notif.isRead) {
      try {
        await markAsRead(notif.id)
        if (!current(snapshot)) return
        mutationSucceeded()
        patchNotification(notif, { isRead: true })
      } catch {
        if (!current(snapshot)) return
        showError(t('inbox.action.readFailed'))
      }
    }
    toast.add({ severity: 'success', summary: t('notification.list.confirmed'), life: 3000 })
    // 追加読み込み済みの通知を保持したまま、対象通知のサマリだけを更新する。
    confirmableSummaries.value = Object.fromEntries(
      Object.entries(confirmableSummaries.value).filter(
        ([sourceId]) => sourceId !== String(notif.sourceId),
      ),
    )
    await loadConfirmableSummary(notif)
  } catch {
    if (!current(snapshot)) return
    showError(t('notification.list.confirmError'))
  }
}

watch(filter, () => {
  viewRevision += 1
  void loadNotifications()
})
watch(
  () => notificationStore.latestNotification,
  (payload) => {
    if (payload) requestRefresh()
  },
)
watch(
  () => authStore.user?.id ?? null,
  () => {
    viewRevision += 1
    notifications.value = []
    confirmableSummaries.value = {}
    nextPage.value = 0
    hasNext.value = false
    refreshPending = false
    pendingPage = null
    if (session.accountId !== null) void loadNotifications()
  },
  { flush: 'sync' },
)
onMounted(() => loadNotifications())
onUnmounted(() => {
  disposed = true
  pendingPage = null
  refreshPending = false
})

defineExpose({ refresh: () => loadNotifications() })
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div class="flex items-center gap-2">
        <SelectButton
          v-model="filter"
          class="notification-filter"
          :options="[
            { label: $t('notification.list.all'), value: 'all' },
            { label: $t('notification.list.unread'), value: 'unread' },
          ]"
          option-label="label"
          option-value="value"
        />
      </div>
      <Button
        :label="$t('notification.list.markAllRead')"
        class="min-h-11 w-full sm:w-auto"
        text
        size="small"
        @click="onMarkAllRead"
      />
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
        :data-notification-id="notif.id"
        role="button"
        tabindex="0"
        class="grid grid-cols-[8px_32px_minmax(0,1fr)] items-start gap-x-3 border-b border-surface-100 px-4 py-3 text-left transition-colors hover:bg-surface-50 sm:flex sm:gap-3"
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
          <div v-if="!notif.isRead" class="h-2 w-2 rounded-full bg-primary" />
          <div v-else class="h-2 w-2" />
        </div>

        <!-- アイコン -->
        <div
          class="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full"
          :class="[
            isConfirmableNotification(notif) ? 'bg-amber-200' : '',
            isEmergencyClosureNotification(notif) ? 'bg-red-100' : '',
            !isConfirmableNotification(notif) && !isEmergencyClosureNotification(notif)
              ? 'bg-surface-100'
              : '',
          ]"
        >
          <i
            :class="getIcon(notif.sourceType)"
            class="text-sm"
            :style="{
              color: isConfirmableNotification(notif)
                ? '#d97706'
                : isEmergencyClosureNotification(notif)
                  ? '#dc2626'
                  : undefined,
            }"
          />
        </div>

        <!-- 内容 -->
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2 sm:flex-nowrap">
            <p
              class="min-w-0 break-words text-sm font-medium"
              :class="getPriorityColor(notif.priority)"
            >
              {{ notif.title }}
            </p>
            <span
              v-if="notif.scopeName"
              class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500"
            >
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
          <p
            v-if="notif.body"
            class="mt-0.5 break-words whitespace-normal text-xs text-surface-400 sm:truncate"
          >
            {{ notif.body }}
          </p>
          <div class="mt-1 flex flex-wrap items-center gap-2 text-xs text-surface-400">
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
              {{
                $t('confirmable.unconfirmed_count', {
                  count: getConfirmableSummary(notif)?.unconfirmedCount ?? 0,
                })
              }}
            </span>
          </div>

          <!-- 「確認する」ボタン（CONFIRMABLE_NOTIFICATION かつ未確認の場合） -->
          <div v-if="isConfirmableNotification(notif) && notif.isConfirmed === false" class="mt-2">
            <Button
              :label="$t('confirmable.confirm_button')"
              class="min-h-11"
              size="small"
              severity="warn"
              icon="pi pi-check"
              @click.stop="onConfirmNotification(notif)"
            />
          </div>

          <!-- 確認済みラベル（CONFIRMABLE_NOTIFICATION） -->
          <div
            v-else-if="isConfirmableNotification(notif) && notif.isConfirmed === true"
            class="mt-1"
          >
            <span class="text-xs text-surface-400">
              <i class="pi pi-check mr-1 text-green-500" />
              {{ $t('confirmable.already_confirmed') }}
            </span>
          </div>

          <!-- 「確認する」ボタン（EMERGENCY_CLOSURE かつ未確認の場合） -->
          <div v-if="isEmergencyClosureNotification(notif) && !notif.isRead" class="mt-2">
            <Button
              :label="$t('emergency_closure.confirm_button')"
              class="min-h-11"
              size="small"
              severity="danger"
              icon="pi pi-check"
              @click.stop="onConfirmEmergencyClosure(notif)"
            />
          </div>

          <!-- 確認済みラベル（EMERGENCY_CLOSURE） -->
          <div v-else-if="isEmergencyClosureNotification(notif) && notif.isRead" class="mt-1">
            <span class="text-xs text-surface-400">
              <i class="pi pi-check mr-1 text-green-500" />
              {{ $t('emergency_closure.already_confirmed') }}
            </span>
          </div>
        </div>

        <!-- アクションボタン群（スヌーズ + 既読トグル）-->
        <div class="col-start-3 mt-2 flex shrink-0 items-center gap-1 sm:col-auto sm:mt-1">
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
            :ref="
              (el) => {
                snoozeMenuRefs[notif.id] = el as InstanceType<
                  typeof import('primevue/menu').default
                > | null
              }
            "
            :model="getSnoozeMenuItems(notif)"
            :popup="true"
          />

          <!-- 既読/未読トグル（ヒット領域44x44。アイコン視覚サイズはtext-xsのまま維持） -->
          <button
            class="inline-flex min-h-11 min-w-11 items-center justify-center p-1 text-surface-300 hover:text-surface-600"
            :aria-label="
              notif.isRead ? $t('notification.list.markUnread') : $t('notification.list.markRead')
            "
            :title="
              notif.isRead ? $t('notification.list.markUnread') : $t('notification.list.markRead')
            "
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
      <p class="text-surface-400">{{ $t('notification.list.empty') }}</p>
    </div>

    <!-- もっと読む -->
    <div v-if="hasNext" class="flex justify-center py-4">
      <Button
        :label="$t('notification.list.loadMore')"
        class="min-h-11"
        text
        :loading="loading"
        @click="loadNotifications(nextPage)"
      />
    </div>

    <div v-if="loading && notifications.length === 0" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
  </div>
</template>

<style scoped>
:deep(.notification-filter .p-togglebutton) {
  min-width: 2.75rem;
  min-height: 2.75rem;
}
</style>
