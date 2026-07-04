<script setup lang="ts">
import type { ScopeType } from '~/types/scopeFolder'
import type {
  CirculationActionItem,
  SurveyActionItem,
  AttendanceActionItem,
  ScopeTabType,
} from '~/types/dashboard-scope'
import type { PersonalActionItem } from '~/composables/usePersonalActionRequired'

/**
 * F15.3 改修ポイント:
 *  - ウィジェット冒頭にフォルダ別タブ列を追加（「すべて」「未分類」「（各フォルダ）」）
 *  - 各タブ右上に未読件数バッジ（0 は非表示）
 *  - タブクリック時、TEAM/ORGANIZATION の通知集計を `useScopeFoldersStore`
 *    から取得し、フォルダ別の通知一覧は `/api/v1/notifications?folderId=` で取得
 *  - 既存「全て既読」「すべて表示」リンクの責務は維持
 *
 * 設計書 §7.5 に準拠。フォルダタブと種別タブの二軸混在は混乱招くため、
 * 本フェーズはフォルダタブのみとする（plan §⑤）。
 *
 * F15.3 第二陣追加:
 *  - 「要対応」タブ追加: GET /api/v1/dashboard/action-required で全スコープ横断アイテム表示
 *  - 各アイテムクリックでモーダルを開き押印・回答ができる（AC-19）
 */

const { getNotices, markNoticeRead, markAllNoticesRead } = useDashboardApi()
const api = useApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()
const { t } = useI18n()
const foldersStore = useScopeFoldersStore()
const { fetchActionRequired } = usePersonalActionRequired()

interface Notice {
  id: number
  type: string
  title: string
  message: string | null
  isRead: boolean
  createdAt: string
  linkUrl: string | null
}

/** タブの選択状態。`'all'` は全件、`'action-required'` は要対応タブ、それ以外は `{ scopeType, folderId }`。 */
type TabKey =
  | { kind: 'all' }
  | { kind: 'action-required' }
  | { kind: 'folder', scopeType: ScopeType, folderId: number }

const notices = ref<Notice[]>([])
const loading = ref(true)
const currentTab = ref<TabKey>({ kind: 'all' })

/**
 * `/api/v1/notifications` のレスポンス（PagedResponse 形）の最低限の形。
 * 設計書 §5.2.4 で folderId による絞り込みが Backend 側に実装済み。
 */
interface NotificationItem {
  id: number
  notificationType: string
  title: string
  body: string | null
  isRead: boolean
  actionUrl: string | null
  createdAt: string
}

// === 要対応タブ状態 ===
const actionRequiredItems = ref<PersonalActionItem[]>([])
const actionRequiredLoading = ref(false)
const actionRequiredLoaded = ref(false)

// === 要対応モーダル状態 ===
const circulationModal = ref<{
  visible: boolean
  item: CirculationActionItem | null
  scopeType: ScopeTabType
  scopeId: string
}>({
  visible: false,
  item: null,
  scopeType: 'TEAM',
  scopeId: '',
})
const surveyModal = ref<{
  visible: boolean
  item: SurveyActionItem | null
  scopeType: ScopeTabType
  scopeId: string
}>({
  visible: false,
  item: null,
  scopeType: 'TEAM',
  scopeId: '',
})
const attendanceModal = ref<{
  visible: boolean
  item: AttendanceActionItem | null
  scopeType: ScopeTabType
  scopeId: string
}>({
  visible: false,
  item: null,
  scopeType: 'TEAM',
  scopeId: '',
})

/** タブキーを文字列化（キャッシュのキーとして使用）。 */
function tabCacheKey(tab: TabKey): string {
  if (tab.kind === 'all') return 'all'
  if (tab.kind === 'action-required') return 'action-required'
  return `${tab.scopeType}-${tab.folderId}`
}

/** タブ別データキャッシュ。初回マウント時に全タブ分を一括取得して保持する。 */
const noticeCache = new Map<string, Notice[]>()

/** 指定タブのデータを API から取得して返す。 */
async function fetchForTab(tab: TabKey): Promise<Notice[]> {
  if (tab.kind === 'all') {
    const res = await getNotices({ limit: 5 })
    return res.data.items.map(n => ({
      id: n.id,
      type: n.type,
      title: n.title,
      message: n.body,
      isRead: n.is_read,
      createdAt: n.created_at,
      linkUrl: n.action_url,
    }))
  }
  if (tab.kind === 'action-required') {
    // 要対応タブは notices キャッシュに入れない（別ステートで管理）
    return []
  }
  const params = new URLSearchParams()
  params.set('folderId', String(tab.folderId))
  params.set('scopeType', tab.scopeType)
  params.set('size', '5')
  params.set('page', '0')
  const res = await api<{ data: NotificationItem[] }>(
    `/api/v1/notifications?${params.toString()}`,
  )
  return res.data.map(n => ({
    id: n.id,
    type: n.notificationType,
    title: n.title,
    message: n.body,
    isRead: n.isRead,
    createdAt: n.createdAt,
    linkUrl: n.actionUrl,
  }))
}

/** 要対応タブ: API からアイテム一覧を取得。 */
async function loadActionRequired() {
  if (actionRequiredLoaded.value) return
  actionRequiredLoading.value = true
  try {
    const res = await fetchActionRequired()
    actionRequiredItems.value = res.items
    actionRequiredLoaded.value = true
  }
  catch (error) {
    captureQuiet(error, { context: 'WidgetNotices: 要対応取得' })
    actionRequiredItems.value = []
  }
  finally {
    actionRequiredLoading.value = false
  }
}

/** 全タブ分を並列プリフェッチしてキャッシュに保存し、現在タブを表示する。 */
async function load() {
  loading.value = true
  noticeCache.clear()
  actionRequiredLoaded.value = false
  try {
    await Promise.all(
      tabs.value.map(async (tabDef) => {
        if (tabDef.tab.kind === 'action-required') return // 別ステートで管理
        try {
          noticeCache.set(tabCacheKey(tabDef.tab), await fetchForTab(tabDef.tab))
        }
        catch (error) {
          captureQuiet(error, { context: `WidgetNotices: ${tabDef.key} 取得` })
          noticeCache.set(tabCacheKey(tabDef.tab), [])
        }
      }),
    )
    // 要対応タブも並列取得
    await loadActionRequired()
  }
  finally {
    if (currentTab.value.kind !== 'action-required') {
      notices.value = noticeCache.get(tabCacheKey(currentTab.value)) ?? []
    }
    loading.value = false
  }
}

async function onMarkRead(id: number) {
  await markNoticeRead(id)
  const item = notices.value.find(n => n.id === id)
  if (item) item.isRead = true
}

async function onMarkAllRead() {
  await markAllNoticesRead()
  notices.value.forEach((n) => {
    n.isRead = true
  })
  notification.success('全て既読にしました')
}

const unreadCount = computed(() => notices.value.filter(n => !n.isRead).length)

/** タブ切替。キャッシュから即座に表示するだけ（API呼び出しなし）。 */
function switchTab(tab: TabKey) {
  currentTab.value = tab
  if (tab.kind !== 'action-required') {
    notices.value = noticeCache.get(tabCacheKey(tab)) ?? []
  }
}

/**
 * フォルダ別未読集計を TEAM/ORGANIZATION 両方分取得する。
 * 失敗時はサイレント（ウィジェットの本体機能は維持）。
 */
async function refreshSummaries() {
  try {
    await Promise.all([
      foldersStore.refreshNotificationSummary('TEAM'),
      foldersStore.refreshNotificationSummary('ORGANIZATION'),
    ])
  }
  catch (error) {
    captureQuiet(error, { context: 'WidgetNotices: フォルダ別未読集計取得' })
  }
}

/** フォルダ一覧をストアからロード（未取得の場合のみ）。 */
async function ensureFoldersLoaded() {
  try {
    if (foldersStore.foldersFor('TEAM').length === 0) {
      await foldersStore.fetchAll('TEAM')
    }
    if (foldersStore.foldersFor('ORGANIZATION').length === 0) {
      await foldersStore.fetchAll('ORGANIZATION')
    }
  }
  catch (error) {
    // フォルダ一覧取得失敗は致命的でないため、「すべて」タブだけで動作継続
    captureQuiet(error, { context: 'WidgetNotices: フォルダ一覧取得' })
  }
}

interface FolderTabDef {
  key: string
  label: string
  tab: TabKey
  unreadBadge: number
}

/** タブ定義（表示順: すべて → 要対応 → TEAM 各フォルダ → ORG 各フォルダ）。 */
const tabs = computed<FolderTabDef[]>(() => {
  const result: FolderTabDef[] = [
    {
      key: 'all',
      label: t('scopeFolder.all'),
      tab: { kind: 'all' },
      unreadBadge: 0, // 「すべて」は集計 API が無いためバッジ非表示
    },
    {
      key: 'action-required',
      label: t('dashboard.notices.actionRequiredTab'),
      tab: { kind: 'action-required' },
      unreadBadge: actionRequiredItems.value.length,
    },
  ]

  const appendFor = (scopeType: ScopeType) => {
    const list = foldersStore.foldersFor(scopeType)
    for (const folder of list) {
      result.push({
        key: `${scopeType}-${folder.id}`,
        label: folder.isDefault ? t('scopeFolder.untagged') : folder.name,
        tab: { kind: 'folder', scopeType, folderId: folder.id },
        unreadBadge: foldersStore.unreadCountOf(folder.id),
      })
    }
  }
  appendFor('TEAM')
  appendFor('ORGANIZATION')
  return result
})

function isActiveTab(tab: TabKey): boolean {
  const cur = currentTab.value
  if (cur.kind === 'all' && tab.kind === 'all') return true
  if (cur.kind === 'action-required' && tab.kind === 'action-required') return true
  if (cur.kind === 'folder' && tab.kind === 'folder') {
    return cur.scopeType === tab.scopeType && cur.folderId === tab.folderId
  }
  return false
}

/** 「もっと見る」リンク先。タブによって folderId クエリを付与する。 */
const moreLink = computed<string>(() => {
  const cur = currentTab.value
  if (cur.kind === 'folder') {
    return `/notifications?folderId=${cur.folderId}&scopeType=${cur.scopeType}`
  }
  return '/notifications'
})

// === 要対応アイテムクリック → モーダル表示 ===
function onActionItemClick(item: PersonalActionItem) {
  if (item.itemType === 'CIRCULATION') {
    circulationModal.value = {
      visible: true,
      item: {
        id: item.itemId,
        title: item.title,
        circulatedAt: '',
        deadline: item.deadline,
      },
      scopeType: item.scopeType,
      scopeId: String(item.scopeId),
    }
  }
  else if (item.itemType === 'SURVEY') {
    surveyModal.value = {
      visible: true,
      item: {
        id: Number(item.itemId),
        title: item.title,
        deadline: item.deadline,
      },
      scopeType: item.scopeType,
      scopeId: String(item.scopeId),
    }
  }
  else if (item.itemType === 'ATTENDANCE') {
    attendanceModal.value = {
      visible: true,
      item: {
        scheduleId: Number(item.itemId),
        eventTitle: item.title,
        startsAt: item.startsAt ?? '',
      },
      scopeType: item.scopeType,
      scopeId: String(item.scopeId),
    }
  }
}

/** 完了後にアイテムをリストから除去 */
function removeActionItem(item: PersonalActionItem) {
  actionRequiredItems.value = actionRequiredItems.value.filter(
    i => !(i.itemType === item.itemType && i.itemId === item.itemId && i.scopeId === item.scopeId),
  )
}

function onCirculationConfirmed() {
  if (circulationModal.value.item) {
    const target = actionRequiredItems.value.find(
      i => i.itemType === 'CIRCULATION' && i.itemId === circulationModal.value.item!.id,
    )
    if (target) removeActionItem(target)
  }
}

function onSurveySubmitted() {
  if (surveyModal.value.item) {
    const target = actionRequiredItems.value.find(
      i => i.itemType === 'SURVEY' && Number(i.itemId) === surveyModal.value.item!.id,
    )
    if (target) removeActionItem(target)
  }
}

function onAttendanceSubmitted() {
  if (attendanceModal.value.item) {
    const target = actionRequiredItems.value.find(
      i => i.itemType === 'ATTENDANCE' && Number(i.itemId) === attendanceModal.value.item!.scheduleId,
    )
    if (target) removeActionItem(target)
  }
}

onMounted(async () => {
  await ensureFoldersLoaded()
  await refreshSummaries()
  await load()
})
</script>

<template>
  <DashboardWidgetCard
    title="お知らせ"
    icon="pi pi-bell"
    to="/notifications"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <!-- フォルダタブ列（F15.3 追加） -->
    <div
      role="tablist"
      :aria-label="t('scopeFolder.notifications.byFolder')"
      class="mb-2 flex items-center gap-1 overflow-x-auto pb-1 scrollbar-thin-nav"
      data-testid="widget-notices-folder-tabs"
    >
      <button
        v-for="tabDef in tabs"
        :key="tabDef.key"
        type="button"
        role="tab"
        :aria-selected="isActiveTab(tabDef.tab) ? 'true' : 'false'"
        class="relative flex shrink-0 items-center gap-1 rounded-md px-2 py-1 text-xs font-medium whitespace-nowrap transition-colors"
        :class="
          isActiveTab(tabDef.tab)
            ? 'bg-primary/10 text-primary'
            : 'text-surface-600 hover:bg-surface-100'
        "
        :data-testid="`widget-notices-tab-${tabDef.key}`"
        @click="switchTab(tabDef.tab)"
      >
        <span>{{ tabDef.label }}</span>
        <Badge
          v-if="tabDef.unreadBadge > 0"
          :value="tabDef.unreadBadge"
          severity="danger"
          class="!min-w-[1.25rem] !text-[0.625rem]"
        />
      </button>
    </div>

    <!-- 通常タブ（すべて・フォルダ別） -->
    <template v-if="currentTab.kind !== 'action-required'">
      <div v-if="notices.length > 0">
        <div class="mb-2 flex items-center justify-between">
          <Badge v-if="unreadCount > 0" :value="unreadCount" severity="danger" />
          <Button v-if="unreadCount > 0" label="全て既読" text size="small" @click="onMarkAllRead" />
        </div>
        <div class="divide-y divide-surface-300 dark:divide-surface-600">
          <div
            v-for="notice in notices"
            :key="notice.id"
            class="flex items-start gap-3 py-2"
            :class="{ 'opacity-60': notice.isRead }"
          >
            <div
              class="mt-1 h-2 w-2 shrink-0 rounded-full"
              :class="notice.isRead ? 'bg-surface-300' : 'bg-primary'"
            />
            <div class="min-w-0 flex-1">
              <NuxtLink
                v-if="notice.linkUrl"
                :to="notice.linkUrl"
                class="text-sm font-medium hover:text-primary"
                @click="onMarkRead(notice.id)"
              >
                {{ notice.title }}
              </NuxtLink>
              <p v-else class="text-sm font-medium" @click="onMarkRead(notice.id)">
                {{ notice.title }}
              </p>
              <p v-if="notice.message" class="truncate text-xs text-surface-500">
                {{ notice.message }}
              </p>
            </div>
          </div>
        </div>
        <NuxtLink
          :to="moreLink"
          class="mt-2 block text-center text-xs text-primary hover:underline"
        >
          すべて表示
        </NuxtLink>
      </div>
      <DashboardEmptyState v-else icon="pi pi-bell-slash" message="お知らせはありません" />
    </template>

    <!-- 要対応タブ -->
    <template v-else>
      <div v-if="actionRequiredLoading" class="py-4 text-center">
        <i class="pi pi-spin pi-spinner text-surface-400" />
      </div>
      <DashboardEmptyState
        v-else-if="actionRequiredItems.length === 0"
        icon="pi pi-check-circle"
        :message="t('dashboard.notices.actionRequiredEmpty')"
      />
      <div v-else class="divide-y divide-surface-300 dark:divide-surface-600">
        <button
          v-for="item in actionRequiredItems"
          :key="`${item.itemType}-${item.scopeId}-${item.itemId}`"
          type="button"
          class="flex w-full items-start gap-2 py-2 text-left hover:text-primary"
          :data-testid="`action-required-item-${item.itemType}-${item.itemId}`"
          @click="onActionItemClick(item)"
        >
          <i
            class="mt-0.5 shrink-0 text-xs"
            :class="{
              'pi pi-clipboard': item.itemType === 'CIRCULATION',
              'pi pi-file-edit': item.itemType === 'SURVEY',
              'pi pi-check-square': item.itemType === 'ATTENDANCE',
            }"
          />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">
              {{ item.title }}
            </p>
            <p class="text-xs text-surface-500">
              {{ item.scopeName }}
            </p>
          </div>
          <i class="pi pi-chevron-right shrink-0 text-xs text-surface-400" />
        </button>
      </div>
    </template>
  </DashboardWidgetCard>

  <!-- 回覧板確認モーダル -->
  <CirculationConfirmModal
    v-if="circulationModal.item"
    :visible="circulationModal.visible"
    :item="circulationModal.item"
    :scope-type="circulationModal.scopeType"
    :scope-id="circulationModal.scopeId"
    @update:visible="circulationModal.visible = $event"
    @confirmed="onCirculationConfirmed"
  />

  <!-- アンケート回答モーダル -->
  <SurveyAnswerModal
    v-if="surveyModal.item"
    :visible="surveyModal.visible"
    :item="surveyModal.item"
    :scope-type="surveyModal.scopeType"
    :scope-id="surveyModal.scopeId"
    @update:visible="surveyModal.visible = $event"
    @submitted="onSurveySubmitted"
  />

  <!-- 出席確認モーダル -->
  <AttendanceQuickModal
    v-if="attendanceModal.item"
    :visible="attendanceModal.visible"
    :item="attendanceModal.item"
    :scope-type="attendanceModal.scopeType"
    :scope-id="attendanceModal.scopeId"
    @update:visible="attendanceModal.visible = $event"
    @submitted="onAttendanceSubmitted"
  />
</template>
