<script setup lang="ts">
import type { ScopeType } from '~/types/scopeFolder'

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
 */

const { getNotices, markNoticeRead, markAllNoticesRead } = useDashboardApi()
const api = useApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()
const { t } = useI18n()
const foldersStore = useScopeFoldersStore()

interface Notice {
  id: number
  type: string
  title: string
  message: string | null
  isRead: boolean
  createdAt: string
  linkUrl: string | null
}

/** タブの選択状態。`'all'` は全件、それ以外は `{ scopeType, folderId }`。 */
type TabKey =
  | { kind: 'all' }
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

/** タブキーを文字列化（キャッシュのキーとして使用）。 */
function tabCacheKey(tab: TabKey): string {
  return tab.kind === 'all' ? 'all' : `${tab.scopeType}-${tab.folderId}`
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

/** 全タブ分を並列プリフェッチしてキャッシュに保存し、現在タブを表示する。 */
async function load() {
  loading.value = true
  noticeCache.clear()
  try {
    await Promise.all(
      tabs.value.map(async (tabDef) => {
        try {
          noticeCache.set(tabCacheKey(tabDef.tab), await fetchForTab(tabDef.tab))
        }
        catch (error) {
          captureQuiet(error, { context: `WidgetNotices: ${tabDef.key} 取得` })
          noticeCache.set(tabCacheKey(tabDef.tab), [])
        }
      }),
    )
  }
  finally {
    notices.value = noticeCache.get(tabCacheKey(currentTab.value)) ?? []
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
  notices.value = noticeCache.get(tabCacheKey(tab)) ?? []
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

/** タブ定義（表示順: すべて → TEAM 各フォルダ → ORG 各フォルダ）。 */
const tabs = computed<FolderTabDef[]>(() => {
  const result: FolderTabDef[] = [
    {
      key: 'all',
      label: t('scopeFolder.all'),
      tab: { kind: 'all' },
      unreadBadge: 0, // 「すべて」は集計 API が無いためバッジ非表示
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
  </DashboardWidgetCard>
</template>
