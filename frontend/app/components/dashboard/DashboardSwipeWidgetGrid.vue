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
 * 第二陣（dashboard-scope-panel-content）: BE が既に応答へ詰めている「直近3件リスト」を
 * 個人パネルと同じ見た目（DashboardWidgetCard 内包）で描画する。掲示板（③）・要対応（⑧）は
 * リスト実装が未整備のため現状維持（件数タイル / ScopeActionRequiredWidget）。
 *
 * scopeId は slug（例 fc-u-18）。予定 / TODO の「すべて見る」導線 to は
 * /teams/{slug}/... または /organizations/{slug}/... で組む。タイムラインは
 * TimelineFeed に scope-id=slug を渡す（BE の TimelineScopeIdResolver が slug を解決する）。
 */
import dayjs from 'dayjs'
import type {
  ScopeTabType,
  TeamDashboardResponse,
  OrgDashboardResponse,
  ScopeUpcomingEventItem,
  ScopeBlogPostItem,
  ScopeChatChannelItem,
  ScopeTodoItem,
  ScopeTodoSummary,
  ScopeChatSummary,
  ScopeCalendarSummary,
  ScopeUnreadThreads,
  ScopeBulletinThreadItem,
} from '~/types/dashboard-scope'

const props = defineProps<{
  scopeType: ScopeTabType
  scopeId: string
  /** GET /dashboard/{team|organization}/{id} のレスポンス。 */
  data: TeamDashboardResponse | OrgDashboardResponse
}>()

const { formatDate, formatDateTime } = useDatetime()

// --- チーム / 組織で参照するフィールドを正規化 ---
const team = computed(() =>
  props.scopeType === 'TEAM' ? (props.data as TeamDashboardResponse) : null,
)
const org = computed(() =>
  props.scopeType === 'ORGANIZATION' ? (props.data as OrgDashboardResponse) : null,
)

/** 「すべて見る」導線の基底パス（チーム / 組織で対称・scopeId は slug）。 */
const basePath = computed(() =>
  props.scopeType === 'TEAM'
    ? `/teams/${props.scopeId}`
    : `/organizations/${props.scopeId}`,
)

// ① 今後の予定
const upcomingEvents = computed<ScopeUpcomingEventItem[]>(() =>
  (team.value ? team.value.teamUpcomingEvents : org.value?.orgUpcomingEvents) ?? [],
)

// ④ ブログ
const blogPosts = computed<ScopeBlogPostItem[]>(() =>
  (team.value ? team.value.teamLatestBlogPosts : org.value?.orgLatestBlogPosts) ?? [],
)

// ⑤ チャット
const chatSummary = computed<ScopeChatSummary | null>(() =>
  (team.value ? team.value.teamChatSummary : org.value?.orgChatSummary) ?? null,
)
const chatChannels = computed<ScopeChatChannelItem[]>(
  () => chatSummary.value?.channels ?? [],
)

// ⑥ カレンダー
const calendarSummary = computed<ScopeCalendarSummary | null>(() =>
  (team.value ? team.value.teamCalendarSummary : org.value?.orgCalendarSummary) ?? null,
)
const hasCalendarContent = computed(() => {
  const c = calendarSummary.value
  return !!c && (!!c.next_event || c.events_this_week > 0 || c.events_today > 0)
})

// ⑦ TODO
const todoSummary = computed<ScopeTodoSummary | null>(() =>
  (team.value ? team.value.teamTodo : org.value?.orgTodo) ?? null,
)
const todoItems = computed<ScopeTodoItem[]>(() => todoSummary.value?.items ?? [])

/**
 * TODO が期限切れか（due_date が今日より前）。overdue_count はスコープ全体の集計のため、
 * 個別カードのバッジは due_date の実値で判定する（設計書 04 §4 ⑦）。
 */
function isTodoOverdue(item: ScopeTodoItem): boolean {
  if (!item.due_date) return false
  return dayjs(item.due_date).isBefore(dayjs(), 'day')
}

// ③ 掲示板（直近スレッド一覧へコンテンツ化・第二陣）
// BE 未デプロイ環境では bulletin_threads が undefined になりうるため空配列へ縮退する。
const bulletinData = computed<ScopeUnreadThreads | null>(() =>
  (team.value ? team.value.teamUnreadThreads : org.value?.orgUnreadThreads) ?? null,
)
const bulletinThreadItems = computed<ScopeBulletinThreadItem[]>(
  () => bulletinData.value?.bulletin_threads ?? [],
)
</script>

<template>
  <div
    class="grid grid-cols-1 items-stretch gap-4 md:grid-cols-2"
    :data-testid="`swipe-widget-grid-${scopeType}`"
  >
    <!-- ① 今後の予定（タイトル + 開始日時。all_day は日付、それ以外は日時） -->
    <DashboardScopeListWidget
      :title="$t('swipeWidgets.upcoming')"
      icon="pi pi-calendar-plus"
      :items="upcomingEvents"
      :empty-message="$t('swipeWidgets.list.upcomingEmpty')"
      :to="`${basePath}/events`"
    >
      <template #item="{ item }: { item: ScopeUpcomingEventItem }">
        <div
          class="flex items-center gap-3 rounded-lg border-2 border-surface-300 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-700/50"
        >
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ item.title }}</p>
            <p class="text-xs text-surface-500">
              <i class="pi pi-clock mr-1" />{{
                item.all_day ? formatDate(item.start_at) : formatDateTime(item.start_at)
              }}
            </p>
            <p v-if="item.location" class="truncate text-xs text-surface-400">
              <i class="pi pi-map-marker mr-1" />{{ item.location }}
            </p>
          </div>
          <Tag
            v-if="item.all_day"
            :value="$t('swipeWidgets.list.allDay')"
            severity="secondary"
            rounded
          />
        </div>
      </template>
    </DashboardScopeListWidget>

    <!-- ② タイムライン（TimelineFeed を流用・著者名込みで直近3件） -->
    <DashboardWidgetCard :title="$t('swipeWidgets.timeline')" icon="pi pi-comments">
      <TimelineFeed
        :scope-type="scopeType === 'TEAM' ? 'TEAM' : 'ORGANIZATION'"
        :scope-id="scopeId"
        :limit="3"
      />
    </DashboardWidgetCard>

    <!-- ③ 掲示板（スレッド名 + 更新日時 + 未読ドット。導線無し） -->
    <DashboardScopeListWidget
      :title="$t('swipeWidgets.bulletin')"
      icon="pi pi-megaphone"
      :items="bulletinThreadItems"
      :empty-message="$t('swipeWidgets.list.bulletinEmpty')"
    >
      <template #item="{ item }: { item: ScopeBulletinThreadItem }">
        <div
          class="flex items-center gap-3 rounded-lg border-2 border-surface-300 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-700/50"
        >
          <span
            v-if="!item.is_read"
            class="h-2 w-2 flex-shrink-0 rounded-full bg-primary"
            :aria-label="$t('swipeWidgets.list.unread')"
          />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ item.title }}</p>
            <p class="text-xs text-surface-500">
              <i class="pi pi-clock mr-1" />{{ formatDateTime(item.updated_at) }}
            </p>
          </div>
          <Tag
            v-if="!item.is_read"
            :value="$t('swipeWidgets.list.unread')"
            severity="danger"
            rounded
          />
        </div>
      </template>
    </DashboardScopeListWidget>

    <!-- ④ ブログ（タイトル + 著者 + 公開日。導線無し） -->
    <DashboardScopeListWidget
      :title="$t('swipeWidgets.blog')"
      icon="pi pi-book"
      :items="blogPosts"
      :empty-message="$t('swipeWidgets.list.blogEmpty')"
    >
      <template #item="{ item }: { item: ScopeBlogPostItem }">
        <div
          class="rounded-lg border-2 border-surface-300 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-700/50"
        >
          <p class="truncate text-sm font-medium">{{ item.title }}</p>
          <p class="mt-0.5 flex flex-wrap items-center gap-x-3 text-xs text-surface-500">
            <span v-if="item.author">
              <i class="pi pi-user mr-1" />{{ item.author }}
            </span>
            <span v-if="item.published_at">
              <i class="pi pi-calendar mr-1" />{{ formatDate(item.published_at) }}
            </span>
          </p>
        </div>
      </template>
    </DashboardScopeListWidget>

    <!-- ⑤ チャット（チャンネル名 + 未読数 + 最新一言。導線無し） -->
    <DashboardScopeListWidget
      :title="$t('swipeWidgets.chat')"
      icon="pi pi-send"
      :items="chatChannels"
      :empty-message="$t('swipeWidgets.list.chatEmpty')"
    >
      <template #item="{ item }: { item: ScopeChatChannelItem }">
        <div
          class="rounded-lg border-2 border-surface-300 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-700/50"
        >
          <div class="flex items-center justify-between gap-2">
            <p class="min-w-0 flex-1 truncate text-sm font-medium">
              <i class="pi pi-hashtag mr-1 text-surface-400" />{{ item.name }}
            </p>
            <Badge
              v-if="item.unread_count > 0"
              :value="item.unread_count"
              severity="danger"
            />
          </div>
          <p
            v-if="item.last_message_preview"
            class="mt-0.5 line-clamp-1 text-xs text-surface-500"
          >
            {{ item.last_message_preview }}
          </p>
        </div>
      </template>
    </DashboardScopeListWidget>

    <!-- ⑥ カレンダー（リスト化せず「次の予定 + 今週件数」で充実表示） -->
    <DashboardWidgetCard :title="$t('swipeWidgets.calendar')" icon="pi pi-calendar">
      <div v-if="hasCalendarContent" class="space-y-3">
        <div
          class="rounded-lg border-2 border-surface-300 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-700/50"
        >
          <p class="text-xs text-surface-500">{{ $t('swipeWidgets.list.nextEvent') }}</p>
          <p class="truncate text-sm font-medium">
            {{ calendarSummary?.next_event ?? $t('swipeWidgets.list.noNextEvent') }}
          </p>
        </div>
        <div class="flex items-center gap-3">
          <i class="pi pi-calendar text-2xl text-primary" />
          <span class="text-sm text-surface-600 dark:text-surface-300">
            {{ $t('swipeWidgets.list.eventsThisWeek', { count: calendarSummary?.events_this_week ?? 0 }) }}
          </span>
        </div>
      </div>
      <DashboardEmptyState
        v-else
        icon="pi pi-calendar"
        :message="$t('swipeWidgets.list.calendarEmpty')"
      />
    </DashboardWidgetCard>

    <!-- ⑦ TODO（タイトル + 期限。overdue_count>0 で期限切れバッジ） -->
    <DashboardScopeListWidget
      :title="$t('swipeWidgets.todo')"
      icon="pi pi-check-circle"
      :items="todoItems"
      :empty-message="$t('swipeWidgets.list.todoEmpty')"
      :to="`${basePath}/todos`"
    >
      <template #item="{ item }: { item: ScopeTodoItem }">
        <div
          class="flex items-center gap-3 rounded-lg border-2 border-surface-300 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-700/50"
        >
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ item.title }}</p>
            <p v-if="item.due_date" class="text-xs text-surface-500">
              <i class="pi pi-clock mr-1" />{{ formatDate(item.due_date) }}
            </p>
          </div>
          <Tag
            v-if="isTodoOverdue(item)"
            :value="$t('swipeWidgets.list.overdue')"
            severity="danger"
            rounded
          />
        </div>
      </template>
    </DashboardScopeListWidget>

    <!-- ⑧ 要対応（ビューポート遅延取得・現状維持） -->
    <ScopeActionRequiredWidget :scope-type="scopeType" :scope-id="scopeId" />
  </div>
</template>
