<script setup lang="ts">
/**
 * F22.1 チーム / 組織パネルの厳選 8 ウィジェットグリッド。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §3 / §4
 * - 8 種: 今後の予定 / タイムライン / 掲示板 / ブログ / チャット / カレンダー / TODO / 要対応。
 * - 管理者限定ウィジェット（課金・アクセス解析）は含めない（§1.4 / README §2.3）。
 * - widget_key は SWIPE_ プレフィックスで F02.2 詳細ページと名前空間分離（§2.1）。
 *   可視性・min_role 判定はサーバー側で済んでおり、null フィールドはスキップする（§1.4 / §6）。
 *
 * 要対応ウィジェットのみ ScopeActionRequiredWidget でビューポート遅延取得する（§5）。
 * その他は dashboard レスポンスの実装済みサマリフィールドを表示する。
 */
import type {
  ScopeTabType,
  TeamDashboardResponse,
  OrgDashboardResponse,
} from '~/types/dashboard-scope'

const props = defineProps<{
  scopeType: ScopeTabType
  scopeId: string
  /** GET /dashboard/{team|organization}/{id} のレスポンス。 */
  data: TeamDashboardResponse | OrgDashboardResponse
}>()

/**
 * 配列フィールドの件数を安全に取り出す（null / undefined を 0 に丸める）。
 * BE フィールドの多くが Record<string, unknown>[] | null のため、件数表示に留める。
 */
function arrayLen(v: unknown[] | null | undefined): number {
  return Array.isArray(v) ? v.length : 0
}

/** TODO サマリ（teamTodo / orgTodo は Record で pending 件数等を持つ想定）。 */
function todoCount(v: Record<string, unknown> | null | undefined): number {
  if (!v) return 0
  const pending = v.pendingCount ?? v.count ?? v.total
  return typeof pending === 'number' ? pending : 0
}

// --- チーム / 組織で参照するフィールドを正規化 ---
const team = computed(() =>
  props.scopeType === 'TEAM' ? (props.data as TeamDashboardResponse) : null,
)
const org = computed(() =>
  props.scopeType === 'ORGANIZATION' ? (props.data as OrgDashboardResponse) : null,
)

// ① 今後の予定
const upcomingCount = computed(() =>
  team.value
    ? arrayLen(team.value.teamUpcomingEvents)
    : arrayLen(org.value?.orgUpcomingEvents),
)
// ② タイムライン
const timelineCount = computed(() =>
  team.value
    ? arrayLen(team.value.teamLatestPosts)
    : arrayLen(org.value?.orgLatestPosts),
)
// ③ 掲示板
const bulletinThreads = computed(() =>
  team.value ? team.value.teamUnreadThreads : org.value?.orgUnreadThreads,
)
const bulletinUnread = computed(() => {
  const t = bulletinThreads.value
  if (!t) return 0
  const v = t.unreadCount ?? t.totalUnread
  return typeof v === 'number' ? v : 0
})
// ④ ブログ
const blogPosts = computed(() =>
  team.value ? team.value.teamLatestBlogPosts : org.value?.orgLatestBlogPosts,
)
// ⑤ チャット
const chatSummary = computed(() =>
  team.value ? team.value.teamChatSummary : org.value?.orgChatSummary,
)
// ⑥ カレンダー
const calendarSummary = computed(() =>
  team.value ? team.value.teamCalendarSummary : org.value?.orgCalendarSummary,
)
// ⑦ TODO
const todoSummary = computed(() =>
  team.value ? todoCount(team.value.teamTodo) : todoCount(org.value?.orgTodo),
)

/** カウントだけのサマリウィジェット定義（要対応以外の 6 枚は簡易サマリ）。 */
const summaryWidgets = computed(() => [
  { key: 'upcoming', icon: 'pi pi-calendar-plus', label: 'swipeWidgets.upcoming', count: upcomingCount.value },
  { key: 'timeline', icon: 'pi pi-comments', label: 'swipeWidgets.timeline', count: timelineCount.value },
  { key: 'bulletin', icon: 'pi pi-megaphone', label: 'swipeWidgets.bulletin', count: bulletinUnread.value },
  { key: 'blog', icon: 'pi pi-book', label: 'swipeWidgets.blog', count: arrayLen(blogPosts.value) },
  { key: 'chat', icon: 'pi pi-send', label: 'swipeWidgets.chat', count: chatSummary.value?.totalUnread ?? 0 },
  { key: 'todo', icon: 'pi pi-check-circle', label: 'swipeWidgets.todo', count: todoSummary.value },
])
</script>

<template>
  <div
    class="grid grid-cols-1 gap-4 md:grid-cols-2"
    :data-testid="`swipe-widget-grid-${scopeType}`"
  >
    <!-- ①②③④⑤⑦ 簡易サマリ（件数 + 空状態） -->
    <SectionCard
      v-for="w in summaryWidgets"
      :key="w.key"
      :title="$t(w.label)"
    >
      <div class="flex items-center gap-3">
        <i :class="w.icon" class="text-2xl text-primary" />
        <span v-if="w.count > 0" class="text-2xl font-bold">{{ w.count }}</span>
        <span v-else class="text-sm text-surface-500">{{ $t('swipeWidgets.emptyState') }}</span>
      </div>
    </SectionCard>

    <!-- ⑥ カレンダー -->
    <SectionCard :title="$t('swipeWidgets.calendar')">
      <div v-if="calendarSummary" class="flex items-center gap-3">
        <i class="pi pi-calendar text-2xl text-primary" />
        <span class="text-2xl font-bold">{{ calendarSummary.eventsThisWeek }}</span>
      </div>
      <span v-else class="text-sm text-surface-500">{{ $t('swipeWidgets.emptyState') }}</span>
    </SectionCard>

    <!-- ⑧ 要対応（ビューポート遅延取得） -->
    <ScopeActionRequiredWidget :scope-type="scopeType" :scope-id="scopeId" />
  </div>
</template>
