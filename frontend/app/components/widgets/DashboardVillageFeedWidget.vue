<script setup lang="ts">
/**
 * F17.1 村機能 Phase 1-FE — 個人ダッシュボード村フィードウィジェット
 *
 * 設計書: docs/features/F17.1_village_community.md §4.13（ダッシュボード集約 API）
 *
 * 機能:
 *   - お気に入りピン村のショートカット表示（最大 6 件）
 *   - 横断フィード（TIMELINE / LOBBY）一覧表示（最大 20 件）
 *   - 各クリックで `/villages/{villageId}` へ遷移
 *
 * データソース: GET /api/v1/me/village-feed（VillageFeedController）
 *   - レスポンス: VillageFeedResponse { feed, pinnedVillages }
 *
 * 状態:
 *   - loading（スケルトン 3 件）
 *   - error（再試行ボタン）
 *   - empty（村一覧への誘導）
 *   - 正常（ピン村 + フィード一覧）
 *
 * 既存ダッシュボードへの統合方針:
 *   個人ダッシュボード本体は dashboard widget 登録機構（`useDashboardWidgets`
 *   等）と連動して描画されているため、コンポ単体作成のみとする。
 *   統合の際は ScopeDashboard.vue または dashboard widget 設定に
 *   `DashboardVillageFeedWidget` を追加すれば良い（別軍議で実施予定）。
 *
 * 使用例（ダッシュボード本体へ追加する際の参考）:
 *   <DashboardWidgetCard :title="$t('village.feed.dashboardTitle')">
 *     <DashboardVillageFeedWidget />
 *   </DashboardWidgetCard>
 */
import type {
  VillageFeedItemResponse,
  VillageFeedResponse,
  VillagePinnedSummaryResponse,
} from '~/types/village'

const { t } = useI18n()
const villageApi = useVillageApi()
const { captureQuiet } = useErrorReport()
const { formatDateTime } = useDatetime()

// =============================================================================
// 状態管理
// =============================================================================
const loading = ref(true)
const error = ref<string | null>(null)
const feed = ref<VillageFeedItemResponse[]>([])
const pinnedVillages = ref<VillagePinnedSummaryResponse[]>([])

/** ピン村ショートカットの最大表示件数 */
const PINNED_DISPLAY_LIMIT = 6
/** フィード本体の最大表示件数（バックエンドは最大 20 件返す想定） */
const FEED_DISPLAY_LIMIT = 20

const displayedPinned = computed<VillagePinnedSummaryResponse[]>(() =>
  pinnedVillages.value.slice(0, PINNED_DISPLAY_LIMIT),
)
const displayedFeed = computed<VillageFeedItemResponse[]>(() =>
  feed.value.slice(0, FEED_DISPLAY_LIMIT),
)
const isEmpty = computed(
  () => pinnedVillages.value.length === 0 && feed.value.length === 0,
)

// =============================================================================
// API 呼び出し
// =============================================================================
async function load() {
  loading.value = true
  error.value = null
  try {
    const res: VillageFeedResponse = await villageApi.getFeed()
    feed.value = res.feed ?? []
    pinnedVillages.value = res.pinnedVillages ?? []
  } catch (err) {
    captureQuiet(err, { context: 'DashboardVillageFeedWidget: フィード取得失敗' })
    error.value = t('village.feed.loadingError')
    feed.value = []
    pinnedVillages.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

// =============================================================================
// ナビゲーション
// =============================================================================
function gotoVillage(villageId: string) {
  navigateTo(`/villages/${villageId}`)
}

function gotoVillageList() {
  navigateTo('/villages')
}

// =============================================================================
// 表示ヘルパ
// =============================================================================
function feedTypeLabel(type: VillageFeedItemResponse['type']): string {
  return t(`village.feed.type.${type}`)
}

function feedTypeIcon(type: VillageFeedItemResponse['type']): string {
  switch (type) {
    case 'TIMELINE':
      return 'pi pi-comments'
    case 'LOBBY':
      return 'pi pi-inbox'
    default:
      return 'pi pi-circle'
  }
}

function formatCreatedAt(iso: string): string {
  if (!iso) return ''
  return formatDateTime(iso)
}

/** 村名のイニシャル（アイコンが無いピン村用フォールバック） */
function pinnedInitials(name: string): string {
  if (!name) return '?'
  return [...name].slice(0, 2).join('').toUpperCase()
}
</script>

<template>
  <div @click.stop>
    <!-- ヘッダー: タイトル + 村一覧へのリンク -->
    <div class="mb-3 flex items-center justify-between">
      <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('village.feed.dashboardTitle') }}
      </h3>
      <NuxtLink
        to="/villages"
        class="text-xs text-primary hover:underline"
      >
        {{ t('village.action.viewAll') }}
        <i class="pi pi-external-link text-[10px]" />
      </NuxtLink>
    </div>

    <!-- ローディング: スケルトン 3 件 -->
    <div v-if="loading" class="space-y-2">
      <Skeleton height="3rem" />
      <Skeleton height="3rem" />
      <Skeleton height="3rem" />
    </div>

    <!-- エラー: 再試行ボタン -->
    <div v-else-if="error" class="flex flex-col items-center gap-2 py-6">
      <i class="pi pi-exclamation-triangle text-2xl text-orange-400" />
      <p class="text-sm text-surface-500">{{ error }}</p>
      <Button
        :label="t('village.feed.retry')"
        icon="pi pi-refresh"
        size="small"
        text
        @click="load"
      />
    </div>

    <!-- 空: 村一覧への誘導 -->
    <div
      v-else-if="isEmpty"
      class="flex flex-col items-center gap-3 py-8 text-center"
    >
      <i class="pi pi-home text-3xl text-surface-300" />
      <p class="text-sm text-surface-500">
        {{ t('village.feed.empty') }}
      </p>
      <p class="text-xs text-surface-400">
        {{ t('village.feed.emptyHint') }}
      </p>
      <Button
        :label="t('village.title')"
        icon="pi pi-search"
        size="small"
        severity="secondary"
        outlined
        @click="gotoVillageList"
      />
    </div>

    <!-- 正常: ピン村 + フィード -->
    <div v-else class="space-y-4">
      <!-- ピン村ショートカット（横スクロール） -->
      <section v-if="displayedPinned.length > 0">
        <p class="mb-2 text-xs font-medium text-surface-500 dark:text-surface-400">
          {{ t('village.feed.pinned') }}
        </p>
        <ul
          class="-mx-1 flex flex-nowrap gap-2 overflow-x-auto px-1 pb-1"
          :aria-label="t('village.feed.pinned')"
        >
          <li
            v-for="village in displayedPinned"
            :key="village.id"
            class="shrink-0"
          >
            <button
              type="button"
              class="flex w-20 flex-col items-center gap-1 rounded-md p-2 text-center transition-colors hover:bg-surface-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary dark:hover:bg-surface-800"
              :aria-label="village.name"
              @click="gotoVillage(village.id)"
            >
              <div class="relative">
                <img
                  v-if="village.iconUrl"
                  :src="village.iconUrl"
                  :alt="village.name"
                  class="h-12 w-12 rounded-full object-cover"
                >
                <div
                  v-else
                  class="flex h-12 w-12 items-center justify-center rounded-full bg-surface-100 text-xs font-medium text-surface-500 dark:bg-surface-800 dark:text-surface-300"
                >
                  {{ pinnedInitials(village.name) }}
                </div>
                <!-- 未読バッジ -->
                <span
                  v-if="village.unreadCount > 0"
                  class="absolute -right-1 -top-1 inline-flex h-4 min-w-[1rem] items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-medium leading-none text-white"
                  :aria-label="`${village.unreadCount}`"
                >
                  {{ village.unreadCount > 99 ? '99+' : village.unreadCount }}
                </span>
              </div>
              <span
                class="line-clamp-1 w-full text-[11px] text-surface-700 dark:text-surface-200"
              >
                {{ village.name }}
              </span>
            </button>
          </li>
        </ul>
      </section>

      <!-- フィード一覧 -->
      <section v-if="displayedFeed.length > 0">
        <p class="mb-2 text-xs font-medium text-surface-500 dark:text-surface-400">
          {{ t('village.feed.title') }}
        </p>
        <ul class="space-y-2">
          <li
            v-for="(item, idx) in displayedFeed"
            :key="`${item.type}-${item.villageId}-${item.postId ?? ''}-${item.messageId ?? ''}-${idx}`"
            class="cursor-pointer rounded-md border border-surface-200 bg-surface-0 p-3 transition-colors hover:bg-surface-50 dark:border-surface-700 dark:bg-surface-800 dark:hover:bg-surface-700/50"
            role="button"
            :tabindex="0"
            :aria-label="`${item.villageName}: ${item.snippet}`"
            @click="gotoVillage(item.villageId)"
            @keydown.enter.prevent="gotoVillage(item.villageId)"
            @keydown.space.prevent="gotoVillage(item.villageId)"
          >
            <div class="flex items-start gap-2">
              <i
                :class="feedTypeIcon(item.type)"
                class="mt-0.5 shrink-0 text-sm text-primary"
                :aria-label="feedTypeLabel(item.type)"
              />
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-2 text-[11px] text-surface-500">
                  <span class="font-medium text-surface-700 dark:text-surface-300">{{
                    item.villageName
                  }}</span>
                  <span class="text-surface-400">·</span>
                  <span>{{ feedTypeLabel(item.type) }}</span>
                  <span class="text-surface-400">·</span>
                  <span>{{ formatCreatedAt(item.createdAt) }}</span>
                </div>
                <p
                  class="line-clamp-2 mt-1 text-sm text-surface-700 dark:text-surface-200"
                >
                  {{ item.snippet }}
                </p>
              </div>
            </div>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>
