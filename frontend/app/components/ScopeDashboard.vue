<script setup lang="ts">
import type { ViewerRole, WidgetVisibilitySetting } from '~/types/dashboard'
import type { SpotlightItem, SpotlightScopeType } from '~/composables/useSpotlightApi'

const props = withDefaults(
  defineProps<{
    scopeType: 'personal' | 'team' | 'organization'
    scopeId?: string
    /**
     * F09.19.4 Spotlight 掲載面用のスコープ数値 ID（BE は scopeId に Long を要求する）。
     * team は数値 BIGINT の文字列（team.id）、organization は現状 UUID public_id のため
     * 数値化できない（数値化できないスコープでは掲載面を取得しない・後述コメント参照）。
     */
    scopeNumericId?: string
    scopeName?: string
    scopeTemplate?: string
    viewerRole?: ViewerRole
    isAdminOrDeputy?: boolean
    visibilityMap?: WidgetVisibilitySetting[]
  }>(),
  {
    scopeId: undefined,
    scopeNumericId: undefined,
    scopeName: undefined,
    scopeTemplate: undefined,
    viewerRole: undefined,
    isAdminOrDeputy: false,
    visibilityMap: () => [],
  },
)

const { sortedWidgets, visibleWidgets, isVisible, toggleWidget, reorder, ready } = useDashboardWidgets(
  props.scopeType,
  props.scopeId,
  props.viewerRole,
  props.visibilityMap,
)

const publicHintDismissed = ref(false)
const publicHintStorageKey = computed(
  () => `dashboard-public-hint-dismissed:${props.scopeType}:${props.scopeId ?? 0}`,
)

const showPublicHint = computed(
  () =>
    props.viewerRole === 'PUBLIC' &&
    props.scopeType !== 'personal' &&
    !publicHintDismissed.value,
)

function dismissPublicHint() {
  publicHintDismissed.value = true
  if (import.meta.client) {
    localStorage.setItem(publicHintStorageKey.value, '1')
  }
}

onMounted(() => {
  if (import.meta.client && localStorage.getItem(publicHintStorageKey.value) === '1') {
    publicHintDismissed.value = true
  }
})

// ── F09.19.4 Spotlight 掲載面（DASHBOARD_TILE・末尾固定 2 枠） ──────────────
// 親が 1 回だけ count=2 で取得し items[0]→Primary・items[1]→Secondary に配る。
// spotlightPrimary/Secondary は v-for 外の固定 order-last 描画であり KEYS/linkTo には登録しない
// （結線パリティ規約 project_dashboard_personal_panel_widget_wiring_parity は本 2 枠に非適用）。
// scopeId には数値 ID が必須（BE Long）。organization は現状 UUID public_id しか FE に無いため
// 数値化できず、その場合は掲載面を取得しない（誤った ID を送らない）。team は team.id が数値文字列。
const spotlightApi = useSpotlightApi()
const spotlightItems = ref<SpotlightItem[]>([])

const spotlightScopeType = computed<SpotlightScopeType>(() => {
  if (props.scopeType === 'team') return 'TEAM'
  if (props.scopeType === 'organization') return 'ORGANIZATION'
  return 'PERSONAL'
})

const spotlightScopeId = computed<number | undefined>(() => {
  if (props.scopeNumericId == null || props.scopeNumericId === '') return undefined
  const n = Number(props.scopeNumericId)
  return Number.isFinite(n) ? n : undefined
})

async function loadSpotlight() {
  // TEAM / ORGANIZATION は数値 scopeId が無いと BE が 400 を返すため取得を見送る。
  if (spotlightScopeId.value == null) {
    spotlightItems.value = []
    return
  }
  spotlightItems.value = await spotlightApi.fetchContent('DASHBOARD_TILE', 2, {
    scopeType: spotlightScopeType.value,
    scopeId: spotlightScopeId.value,
    template: props.scopeTemplate,
  })
}

onMounted(() => {
  void loadSpotlight()
})

const showConfig = ref(false)
const dragIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)
const collapsedKeys = ref<Set<string>>(new Set())
// 対象2: team/organization は SSR（useAsyncData）で保存順を確定させるため、初回描画時点で
// 既に保存順になっており、マウント後の再ソートが発生しない＝TransitionGroup の move アニメも
// 初回は発火しない。よって旧 isReady による move-class 抑制は不要になったため撤廃した。
onMounted(() => {
  nextTick(() => {
    // モバイルでは全ウィジェットをデフォルト折り畳み状態にする
    if (window.innerWidth < 768) {
      collapsedKeys.value = new Set(visibleWidgets.value.map((w) => w.key))
    }
  })
})

function toggleCollapse(key: string) {
  if (collapsedKeys.value.has(key)) {
    collapsedKeys.value.delete(key)
  } else {
    collapsedKeys.value.add(key)
  }
  // trigger reactivity
  collapsedKeys.value = new Set(collapsedKeys.value)
}

const DATA_WIDGET_KEYS = new Set([
  'survey-results',
  'attendance-results',
  'recruitment-feed',
  'my-recruitments',
  'schedule',
  // F09.8.1 Phase 4: マイコルクボードはデータ表示型ウィジェット
  'my-corkboard',
  // F14.2: チームメンバー定期更新フォーム
  'member-info',
  // F17.1 §3.12.5: 井戸端ダイジェストはデータ表示型ウィジェット
  'village-lobby-digest',
  // F08.7.1: 成績ウィジェット 3 種はデータ表示型（横長 col-span=2）
  'team-standings-record',
  'team-division-standings',
  'org-tournament-summary',
  // F08.10: チーム試合サマリはデータ表示型
  'team-match-summary',
])

function isDataWidget(key: string): boolean {
  return DATA_WIDGET_KEYS.has(key)
}

const basePath = computed(() => {
  if (props.scopeType === 'personal' || !props.scopeId) return undefined
  return props.scopeType === 'team' ? `/teams/${props.scopeId}` : `/organizations/${props.scopeId}`
})

function linkTo(widgetKey: string): string | undefined {
  if (props.scopeType === 'personal') {
    const personalLinks: Record<string, string> = {
      'upcoming-events': '/calendar',
      todos: '/todos',
      timeline: '/timeline',
      chat: '/chat',
      notifications: '/notifications',
      blog: '/my/blog',
      'recruitment-feed': '/me/recruitment-feed',
      'my-recruitments': '/me/recruitment-listings',
      // F09.8.1 Phase 4: 専用ページは Phase 5 で実装。先行してリンクのみ整える。
      'my-corkboard': '/my/corkboard',
    }
    return personalLinks[widgetKey]
  }
  const base = basePath.value!
  const scopeLinks: Record<string, string> = {
    'upcoming-events': `${base}/schedule`,
    todos: `${base}/todos`,
    timeline: `${base}/timeline`,
    bulletin: `${base}/bulletin`,
    blog: `${base}/blog`,
    chat: `${base}/chat`,
    schedule: `${base}/schedule`,
    members: `${base}/member-profiles`,
    activities: `${base}/activities`,
    gallery: `${base}/gallery`,
    circulation: `${base}/circulation`,
    surveys: `${base}/surveys`,
    'survey-results': `${base}/surveys`,
    'attendance-results': `${base}/schedule`,
    // F14.2: チームメンバー定期更新フォーム
    'member-info': `${base}/member-info`,
    // F08.7.1: 成績ウィジェットの遷移先
    'team-standings-record': `${base}/tournaments`,
    'team-division-standings': `${base}/tournaments`,
    'org-tournament-summary': `${base}/tournaments`,
    // F08.10: チーム試合サマリ → チーム分析ページ
    'team-match-summary': `${base}/match-analytics`,
    // F02.3: プロジェクト
    projects: `${base}/projects`,
  }
  return scopeLinks[widgetKey]
}

function onDragStart(index: number, e: DragEvent) {
  dragIndex.value = index
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
  }
}

function onDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  dropTargetIndex.value = index
}

function onDragLeave(e: DragEvent) {
  // 子要素への移動時は無視
  const target = e.currentTarget as HTMLElement
  if (target.contains(e.relatedTarget as Node)) return
  dropTargetIndex.value = null
}

function onDrop(index: number) {
  if (dragIndex.value !== null && dragIndex.value !== index) {
    reorder(dragIndex.value, index)
  }
  dragIndex.value = null
  dropTargetIndex.value = null
}

function onDragEnd() {
  dragIndex.value = null
  dropTargetIndex.value = null
}
</script>

<template>
  <div class="space-y-6">
    <!-- PUBLIC 閲覧者向けヒントバナー (§3.6 / §6 設計書) -->
    <Message
      v-if="showPublicHint"
      severity="info"
      :closable="true"
      class="text-sm"
      @close="dismissPublicHint"
    >
      {{ $t('dashboard.widget_visibility.public_viewer_hint') }}
    </Message>

    <!-- ウィジェット設定ボタン -->
    <div class="flex justify-end">
      <Button
        :label="$t('dashboard.widget_settings.config_button')"
        icon="pi pi-cog"
        text
        size="small"
        @click="showConfig = true"
      />
    </div>

    <!-- 並び順確定前: スケルトン（位置ジャンプ防止のためウィジェット本体は描画しない） -->
    <div
      v-if="!ready"
      class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3"
      aria-hidden="true"
      data-testid="dashboard-widgets-skeleton"
    >
      <div
        v-for="n in 6"
        :key="`widget-skeleton-${n}`"
        class="h-40 animate-pulse rounded-xl bg-surface-100 dark:bg-surface-800"
      />
    </div>

    <!-- 並び順確定後: 保存順で初描画（ここで初めてマウントするためジャンプしない） -->
    <!-- ウィジェットグリッド -->
    <TransitionGroup
      v-else
      tag="div"
      class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3"
      move-class="transition-all duration-[350ms] ease-in-out"
    >
      <!-- 空状態 -->
      <div
        v-if="visibleWidgets.length === 0"
        key="empty-state"
        class="col-span-full rounded-xl border border-dashed border-surface-400 py-12 text-center dark:border-surface-600"
      >
        <i class="pi pi-th-large mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">{{ $t('dashboard.widget_settings.no_widgets_message') }}</p>
        <Button
          :label="$t('dashboard.widget_settings.add_widget_button')"
          icon="pi pi-plus"
          text
          size="small"
          class="mt-2"
          @click="showConfig = true"
        />
      </div>

      <DashboardWidgetCard
        v-for="(w, index) in visibleWidgets"
        :key="w.key"
        :data-widget-key="w.key"
        title=""
        class="group cursor-default transition-all"
        :col-span="isDataWidget(w.key) ? 2 : 1"
        :scrollable="false"
        :is-dragging="dragIndex === index"
        :is-drop-target="dropTargetIndex === index && dragIndex !== index"
        draggable="true"
        @dragstart="onDragStart(index, $event)"
        @dragover="onDragOver(index, $event)"
        @dragleave="onDragLeave($event)"
        @drop.prevent="onDrop(index)"
        @dragend="onDragEnd"
        @click="!isDataWidget(w.key) && dragIndex === null && navigateTo(linkTo(w.key) ?? '#')"
      >
        <!-- ドラッグハンドル（hover時に表示） -->
        <i
          class="pi pi-grip-vertical absolute right-3 top-3 cursor-grab text-sm text-surface-300 opacity-0 transition-opacity group-hover:opacity-100 active:cursor-grabbing dark:text-surface-600"
        />

        <div class="flex items-center gap-3" :class="collapsedKeys.has(w.key) || isDataWidget(w.key) ? '' : 'mb-3'">
          <div
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary/20"
          >
            <i :class="w.icon" class="text-xl" />
          </div>
          <NuxtLink
            v-if="linkTo(w.key)"
            :to="linkTo(w.key)"
            class="group/title flex-1"
            @click.stop
          >
            <h3
              class="text-[20px] font-semibold text-surface-700 transition-colors group-hover/title:text-primary dark:text-surface-200"
            >
              {{ $t(w.labelKey) }}
            </h3>
          </NuxtLink>
          <h3
            v-else
            class="flex-1 text-[20px] font-semibold text-surface-700 dark:text-surface-200"
          >
            {{ $t(w.labelKey) }}
          </h3>
          <!-- 折り畳みボタン (モバイルのみ) -->
          <button
            class="md:hidden flex items-center justify-center rounded-lg p-1.5 text-surface-400 transition-colors hover:bg-surface-100"
            @click.stop="toggleCollapse(w.key)"
          >
            <i
              class="pi text-sm transition-transform duration-200"
              :class="collapsedKeys.has(w.key) ? 'pi-chevron-down' : 'pi-chevron-up'"
            />
          </button>
          <!-- ナビゲーション矢印 (ナビゲーションウィジェットのみ) -->
          <i
            v-if="!isDataWidget(w.key)"
            class="pi pi-chevron-right hidden md:block text-xs text-surface-400 opacity-0 transition-opacity group-hover:opacity-100"
          />
          <!-- データウィジェット: ページリンク -->
          <NuxtLink
            v-else
            :to="linkTo(w.key)"
            class="shrink-0 text-xs text-surface-400 hover:text-primary"
            @click.stop
          >
            詳細 <i class="pi pi-external-link text-[10px]" />
          </NuxtLink>
        </div>

        <!-- ナビゲーションウィジェット: 説明文 -->
        <p
          v-if="!isDataWidget(w.key)"
          class="text-xs text-surface-500"
          :class="collapsedKeys.has(w.key) ? 'hidden md:block' : ''"
        >
          {{ $t(w.descriptionKey) }}
        </p>

        <!-- データウィジェット: 実コンテンツ -->
        <template v-if="isDataWidget(w.key)">
          <div
            class="mt-3"
            :class="[
              w.key === 'schedule' ? 'min-h-[28rem]' : 'max-h-96 overflow-y-auto pr-1',
              collapsedKeys.has(w.key) ? 'hidden md:block' : '',
            ]"
          >
            <WidgetSurveyResults
              v-if="w.key === 'survey-results' && scopeId"
              :scope-type="(scopeType as 'team' | 'organization')"
              :scope-id="scopeId"
            />
            <WidgetAttendanceResults
              v-else-if="w.key === 'attendance-results' && scopeId"
              :scope-type="(scopeType as 'team' | 'organization')"
              :scope-id="scopeId"
            />
            <!-- Phase 2: F03.11 募集型予約ウィジェット -->
            <WidgetRecruitmentFeed v-else-if="w.key === 'recruitment-feed'" />
            <WidgetMyRecruitments v-else-if="w.key === 'my-recruitments'" />
            <!-- スケジュールカレンダー (team/organization スコープのみ) -->
            <WidgetScheduleCalendar
              v-else-if="w.key === 'schedule' && scopeId"
              :scope-type="(scopeType as 'team' | 'organization')"
              :scope-id="scopeId"
            />
            <!-- F09.8.1 Phase 4: マイコルクボード -->
            <WidgetMyCorkboard v-else-if="w.key === 'my-corkboard' && scopeType === 'personal'" />
            <!-- F14.2: チームメンバー定期更新フォーム -->
            <WidgetMemberInfo
              v-else-if="w.key === 'member-info' && scopeId && scopeType === 'team'"
              :scope-type="scopeType"
              :scope-id="scopeId"
            />
            <!-- F17.1 §3.12.5: 井戸端ダイジェスト（個人ダッシュボードのみ） -->
            <WidgetVillageLobbyDigest
              v-else-if="w.key === 'village-lobby-digest' && scopeType === 'personal'"
            />
            <!-- F08.7.1: 自チーム成績（team スコープのみ） -->
            <WidgetTeamTournamentRecord
              v-else-if="w.key === 'team-standings-record' && scopeId && scopeType === 'team'"
              :team-id="scopeId"
            />
            <!-- F08.7.1: 順位表（team スコープのみ） -->
            <WidgetTeamDivisionStandings
              v-else-if="w.key === 'team-division-standings' && scopeId && scopeType === 'team'"
              :team-id="scopeId"
            />
            <!-- F08.7.1: 主催大会サマリ（organization スコープのみ） -->
            <WidgetOrgTournamentSummary
              v-else-if="w.key === 'org-tournament-summary' && scopeId && scopeType === 'organization'"
              :org-id="scopeId"
            />
            <!-- F08.10: チーム試合サマリ（team スコープのみ） -->
            <WidgetTeamMatchSummary
              v-else-if="w.key === 'team-match-summary' && scopeId && scopeType === 'team'"
              :team-id="scopeId"
            />
          </div>
        </template>
      </DashboardWidgetCard>

      <!-- 広告タイル（Spotlight 掲載面・非表示不可・常に最後・並び替え対象外） -->
      <!-- 候補なしは枠ごと非表示（items.length=1→Secondary 非描画・0→両方非描画）。スケルトンも確保しない（末尾のため CLS 許容）。 -->
      <!-- key は placement 値ベース。KEYS/linkTo には登録しない固定描画。 -->
      <WidgetSpotlightPrimary
        v-if="spotlightItems[0]"
        key="spotlight-primary"
        class="order-last"
        :item="spotlightItems[0]"
      />
      <WidgetSpotlightSecondary
        v-if="spotlightItems[1]"
        key="spotlight-secondary"
        class="order-last"
        :item="spotlightItems[1]"
      />
    </TransitionGroup>

    <!-- 設定ダイアログ -->
    <DashboardConfigDialog
      v-model:visible="showConfig"
      :widgets="sortedWidgets"
      :is-visible="isVisible"
      @toggle="toggleWidget"
      @reorder="reorder"
    />
  </div>
</template>
