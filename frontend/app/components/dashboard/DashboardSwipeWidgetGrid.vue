<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useScopeWidgetPreview } from '~/composables/useScopeWidgetPreview'
import type {
  ScopeTabType,
  TeamDashboardResponse,
  OrgDashboardResponse,
  ScopeUpcomingEventItem,
  ScopeBlogPostItem,
  ScopeChatChannelItem,
  ScopeTodoItem,
  ScopeCalendarSummary,
  ScopeChatSummary,
  ScopeTodoSummary,
  ScopeUnreadThreads,
  ScopeBulletinThreadItem,
} from '~/types/dashboard-scope'

/** F22.1 メンバー向け8ウィジェット。選択した項目は詳細を確認してから元ページへ進む。 */
const props = defineProps<{
  scopeType: ScopeTabType
  /** 遷移先とslug対応APIに使う所属slug。 */
  scopeId: string
  /** タイムライン・掲示板・ブログ・応答scope照合用の内部ID。slugと混同しない。 */
  numericScopeId?: string
  isFeatureVisible?: (featureKey: string) => boolean
  data: TeamDashboardResponse | OrgDashboardResponse
}>()
const featureVisible = (key: string) => props.isFeatureVisible?.(key) ?? true
// 数字だけの公開slugも有効なので、タイムラインには所属一覧の内部IDだけを渡す。
const timelineScopeId = computed(() =>
  props.numericScopeId && /^[1-9]\d*$/.test(props.numericScopeId) ? props.numericScopeId : undefined,
)
const actionSections = computed(() => ({ circulation: featureVisible('circulation'), survey: featureVisible('surveys'), attendance: featureVisible('schedule') }))
const showActionRequired = computed(() => Object.values(actionSections.value).some(Boolean))
const preview = useScopeWidgetPreview(() => ({
  scopeType: props.scopeType,
  scopeSlug: props.scopeId,
  numericScopeId: props.numericScopeId,
}))
const { state, target, detail, error, trigger } = preview
const previewMounted = ref(false)
watch(state, value => {
  if (value !== 'CLOSED') previewMounted.value = true
})
const team = computed(() =>
  props.scopeType === 'TEAM' ? (props.data as TeamDashboardResponse) : null,
)
const org = computed(() =>
  props.scopeType === 'ORGANIZATION' ? (props.data as OrgDashboardResponse) : null,
)
const basePath = computed(
  () =>
    `/${props.scopeType === 'TEAM' ? 'teams' : 'organizations'}/${encodeURIComponent(props.scopeId)}`,
)
const upcomingEvents = computed<ScopeUpcomingEventItem[]>(
  () => (team.value ? team.value.teamUpcomingEvents : org.value?.orgUpcomingEvents) ?? [],
)
const blogPosts = computed<ScopeBlogPostItem[]>(
  () => (team.value ? team.value.teamLatestBlogPosts : org.value?.orgLatestBlogPosts) ?? [],
)
const chatSummary = computed<ScopeChatSummary | null>(
  () => (team.value ? team.value.teamChatSummary : org.value?.orgChatSummary) ?? null,
)
const chatChannels = computed<ScopeChatChannelItem[]>(() => chatSummary.value?.channels ?? [])
const calendarSummary = computed<ScopeCalendarSummary | null>(
  () => (team.value ? team.value.teamCalendarSummary : org.value?.orgCalendarSummary) ?? null,
)
const todoSummary = computed<ScopeTodoSummary | null>(
  () => (team.value ? team.value.teamTodo : org.value?.orgTodo) ?? null,
)
const todoItems = computed<ScopeTodoItem[]>(() => todoSummary.value?.items ?? [])
const bulletinData = computed<ScopeUnreadThreads | null>(
  () => (team.value ? team.value.teamUnreadThreads : org.value?.orgUnreadThreads) ?? null,
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
    <DashboardScopeListWidget
      v-if="featureVisible('schedule')"
      :title="$t('swipeWidgets.upcoming')"
      icon="pi pi-calendar-plus"
      :items="upcomingEvents"
      :empty-message="$t('swipeWidgets.list.upcomingEmpty')"
      :to="`${basePath}/schedule`"
    >
      <template #item="{ item }: { item: ScopeUpcomingEventItem }">
        <DashboardScopeWidgetItem kind="schedule" :item="item" @select="preview.open" />
      </template>
    </DashboardScopeListWidget>
    <DashboardWidgetCard v-if="featureVisible('timeline')" :title="$t('swipeWidgets.timeline')" icon="pi pi-comments">
      <TimelineFeed
        v-if="timelineScopeId"
        :key="`${scopeType}:${timelineScopeId}`"
        :scope-type="scopeType"
        :scope-id="timelineScopeId"
        :limit="3"
      />
      <PageLoading v-else />
    </DashboardWidgetCard>
    <DashboardScopeListWidget
      v-if="featureVisible('bulletin')"
      :title="$t('swipeWidgets.bulletin')"
      icon="pi pi-megaphone"
      :items="bulletinThreadItems"
      :empty-message="$t('swipeWidgets.list.bulletinEmpty')"
    >
      <template #item="{ item }: { item: ScopeBulletinThreadItem }">
        <DashboardScopeWidgetItem kind="bulletin" :item="item" @select="preview.open" />
      </template>
    </DashboardScopeListWidget>
    <DashboardScopeListWidget
      v-if="featureVisible('blog')"
      :title="$t('swipeWidgets.blog')"
      icon="pi pi-book"
      :items="blogPosts"
      :empty-message="$t('swipeWidgets.list.blogEmpty')"
    >
      <template #item="{ item }: { item: ScopeBlogPostItem }">
        <DashboardScopeWidgetItem kind="blog" :item="item" @select="preview.open" />
      </template>
    </DashboardScopeListWidget>
    <DashboardScopeListWidget
      v-if="featureVisible('chat')"
      :title="$t('swipeWidgets.chat')"
      icon="pi pi-send"
      :items="chatChannels"
      :empty-message="$t('swipeWidgets.list.chatEmpty')"
    >
      <template #item="{ item }: { item: ScopeChatChannelItem }">
        <DashboardScopeWidgetItem kind="chat" :item="item" @select="preview.open" />
      </template>
    </DashboardScopeListWidget>
    <DashboardWidgetCard v-if="featureVisible('schedule')" :title="$t('swipeWidgets.calendar')" icon="pi pi-calendar">
      <DashboardScopeWidgetItem
        v-if="calendarSummary"
        kind="calendar"
        :item="calendarSummary"
        @select="preview.open"
      />
      <DashboardEmptyState
        v-else
        icon="pi pi-calendar"
        :message="$t('swipeWidgets.list.calendarEmpty')"
      />
    </DashboardWidgetCard>
    <DashboardScopeListWidget
      v-if="featureVisible('todos')"
      :title="$t('swipeWidgets.todo')"
      icon="pi pi-check-circle"
      :items="todoItems"
      :empty-message="$t('swipeWidgets.list.todoEmpty')"
      :to="`${basePath}/todos`"
    >
      <template #item="{ item }: { item: ScopeTodoItem }">
        <DashboardScopeWidgetItem kind="todo" :item="item" @select="preview.open" />
      </template>
    </DashboardScopeListWidget>
    <ScopeActionRequiredWidget v-if="showActionRequired" :key="scopeType + scopeId" :sections="actionSections" :scope-type="scopeType" :scope-id="scopeId" />
  </div>
  <LazyDashboardScopePreviewModal
    v-if="previewMounted"
    :state="state"
    :target="target"
    :detail="detail"
    :error="error"
    :trigger="trigger"
    @close="preview.close"
    @retry="preview.retry"
  />
</template>
