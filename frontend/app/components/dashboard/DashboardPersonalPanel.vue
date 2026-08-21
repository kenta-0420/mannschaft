<script setup lang="ts">
/**
 * F22.1 個人パネル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §1 / 03 §3.9
 * - 既存 dashboard.vue の中身をそのまま内包（要件 3・widget 構成は F02.2 のまま不変）。
 * - 対象3-B: 18ウィジェットを useDashboardWidgets('personal') 経由で DB 永続化（並び替え・表示制御）。
 * - FamilyHub / AdminBusinessAlert は条件付き固定パネル（v-if）として並び替え対象外。
 * - WidgetCommandCenter（司令塔「今やること」・ADHD-UX戦役第四陣）は常時固定パネル（v-if なし）
 *   として挨拶ヘッダー直下・ウィジェットグリッドの上に描画し、並び替え対象外・KEYS 非登録。
 * - 広告（Spotlight 掲載面）は末尾固定・非表示不可・並び替え対象外。
 * - マイカレンダーは PERSONAL_DATA_WIDGET_KEYS に含めて lg:col-span-2 で横広描画。
 */
import type { SpotlightItem } from '~/composables/useSpotlightApi'
const authStore = useAuthStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const dashboardStore = useDashboardStore()
const greeting = useGreeting()
const timedMessage = useTimedMessage()

const hasFamilyTeam = computed(() => teamStore.myTeams.some((t) => t.template === 'FAMILY'))

// ADMIN / DEPUTY_ADMIN ロールを1件以上持つ場合にウィジェットを表示
const hasAdminOrDeputyRole = computed(() =>
  teamStore.myTeams.some(t =>
    t.role === 'ADMIN' || t.role === 'SYSTEM_ADMIN' || t.role === 'DEPUTY_ADMIN',
  ),
)

const showTeamCreateDialog = ref(false)
const showOrgCreateDialog = ref(false)

function onTeamCreated() {
  teamStore.fetchMyTeams()
}

function onOrgCreated() {
  orgStore.fetchMyOrganizations()
}

const loading = ref(true)

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([
      teamStore.fetchMyTeams(),
      orgStore.fetchMyOrganizations(),
      dashboardStore.fetchPersonalDashboard(),
    ])
  } finally {
    loading.value = false
  }
  // 広告掲載面は非必須のため loading ゲートとは独立に取得する（失敗してもページを止めない）。
  void loadSpotlight()
})

// 個人ダッシュボードウィジェット（DB 永続化・対象3-B）
const { sortedWidgets, visibleWidgets, isVisible, toggleWidget, reorder } = useDashboardWidgets(
  'personal',
)

// ── F09.19.4 Spotlight 掲載面（DASHBOARD_TILE・末尾固定 2 枠） ──────────────
// 親パネルが 1 回だけ count=2 で取得し items[0]→Primary・items[1]→Secondary に配る。
// PERSONAL スコープのため scopeId は付与しない（有料プランゲート等の判定は BE が行う）。
// spotlightPrimary/Secondary は v-for 外の固定 order-last 描画であり、
// KEYS/linkTo には登録しない（結線パリティ規約 project_dashboard_personal_panel_widget_wiring_parity は本 2 枠に非適用）。
const spotlightApi = useSpotlightApi()
const spotlightItems = ref<SpotlightItem[]>([])
// 候補なしは枠ごと非表示: primary=items[0] / secondary=items[1]（存在時のみ描画）
const spotlightPrimary = computed(() => spotlightItems.value[0])
const spotlightSecondary = computed(() => spotlightItems.value[1])

async function loadSpotlight() {
  spotlightItems.value = await spotlightApi.fetchContent('DASHBOARD_TILE', 2, {
    scopeType: 'PERSONAL',
  })
}

// ドラッグ&ドロップ状態
const dragIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)
const collapsedKeys = ref<Set<string>>(new Set())

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
  collapsedKeys.value = new Set(collapsedKeys.value)
}

/** 解散通知リマインダーウィジェットにコンテンツがあるか（0件時のグリッドスロット占有防止） */
const dismissalHasContent = ref(false)

const showConfig = ref(false)

/**
 * データ表示型（コンテンツを内包）ウィジェットのキー集合。
 * これらは lg:col-span-2 で横広描画し、実コンポーネントのコンテンツを表示する。
 * 特に my-calendar は旧来の lg:col-span-2 レイアウトを維持するためここに含める。
 */
const PERSONAL_DATA_WIDGET_KEYS = new Set([
  'my-calendar',                 // マイカレンダー（SectionCard 内包・lg:col-span-2）
  'timetable-today',             // F03.15 Phase 3: 今日の時間割
  'quick-memo',                  // F02.5: ポイっとメモ
  'event-dismissal-reminder',    // F03.12 §16: 解散通知リマインダー
  'notices',                     // プラットフォームお知らせ
  'upcoming-events',             // 今後の予定
  'personal-todo',               // 個人TODO
  'weather',                     // F02.10: 天気
  'todo-countdown',              // TODOカウントダウン
  'reflection-today',            // F06.5: 今日の振り返り
  'unread-threads',              // 未読チャット
  'team-announcements',          // チームのお知らせ
  'org-announcements',           // 組織のお知らせ
  'my-blog',                     // マイブログ
  'my-teams',                    // 所属チーム
  'my-organizations',            // 所属組織
  'favorites',                   // お気に入り
  'my-timeline',                 // 個人集約タイムライン（所属 team/org 横断）
  'recent-activity',             // 最近のアクティビティ
  'recruitment-feed',            // Phase2 F03.11 新着募集（WidgetRecruitmentFeed・自前カード）
  'my-recruitments',             // Phase2 F03.11 参加予定（WidgetMyRecruitments・自前カード）
  'my-corkboard',                // F09.8.1 マイコルクボード（WidgetMyCorkboard・内容のみ→外枠必要）
  'village-lobby-digest',        // F17.1 井戸端ダイジェスト（WidgetVillageLobbyDigest・内容のみ→外枠必要）
  'return-stay-plan',
])

function isDataWidget(key: string): boolean {
  return PERSONAL_DATA_WIDGET_KEYS.has(key)
}

/** ナビゲーション型ウィジェットのリンク先 */
function linkTo(widgetKey: string): string | undefined {
  const personalLinks: Record<string, string> = {
    'event-dismissal-reminder': '/events',
    notices: '/notifications',
    'upcoming-events': '/calendar',
    'personal-todo': '/todos',
    weather: '/settings/profile',
    'todo-countdown': '/todos',
    'reflection-today': '/my/reflection',
    'unread-threads': '/chat',
    'team-announcements': '/notifications',
    'org-announcements': '/notifications',
    'my-blog': '/my/blog',
    'my-teams': '/teams',
    'my-organizations': '/organizations',
    favorites: '/my/favorites',
    'recent-activity': '/timeline',
  }
  return personalLinks[widgetKey]
}

/** ナビ型カードのクリック。リンク未定義時は何もしない（'#' への遷移で上部に戻るのを防ぐ）。 */
function onNavCardClick(widgetKey: string) {
  if (dragIndex.value !== null) return
  const to = linkTo(widgetKey)
  if (to) navigateTo(to)
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
  <div>
    <PageLoading v-if="loading" />
    <div v-else>
      <!-- 挨拶ヘッダー -->
      <div class="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
            {{ greeting }}、{{ authStore.currentUser?.fullName ?? 'ユーザー' }}さん
          </h1>
          <p class="mt-1 text-sm text-surface-500">{{ timedMessage }}</p>
        </div>
      </div>

      <!-- ウィジェット設定ボタン -->
      <div class="mb-2 flex justify-end">
        <Button
          :label="$t('dashboard.widget_settings.config_button')"
          icon="pi pi-cog"
          text
          size="small"
          @click="showConfig = true"
        />
      </div>

      <!-- 固定パネル: 司令塔「今やること」（v-if のまま・並び替え対象外・KEYS非登録） -->
      <!-- ADHD-UX戦役 第四陣: 挨拶ヘッダー直下・ウィジェットグリッドの上に固定表示する -->
      <WidgetCommandCenter />

      <!-- 条件付き固定パネル: FamilyHub（v-if のまま・並び替え対象外） -->
      <div v-if="hasFamilyTeam" class="mb-4">
        <WidgetFamilyHub />
      </div>

      <!-- 18ウィジェット（DB永続化 / 並び替え・表示制御対象） -->
      <TransitionGroup
        tag="div"
        class="mb-8 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3"
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

        <div
          v-for="(w, index) in visibleWidgets"
          v-show="w.key !== 'event-dismissal-reminder' || dismissalHasContent"
          :key="w.key"
          class="group relative flex h-full flex-col cursor-default transition-all"
          :class="[
            (w.key === 'notices' || w.key === 'my-calendar') ? 'col-span-1 md:col-span-2' : 'col-span-1',
            { 'opacity-40': dragIndex === index },
          ]"
          draggable="true"
          @dragstart="onDragStart(index, $event)"
          @dragover="onDragOver(index, $event)"
          @dragleave="onDragLeave($event)"
          @drop.prevent="onDrop(index)"
          @dragend="onDragEnd"
        >
          <!-- ドロップインジケーター（データウィジェット用） -->
          <div
            v-if="isDataWidget(w.key) && dropTargetIndex === index && dragIndex !== index"
            class="pointer-events-none absolute inset-x-0 top-0 z-10 h-[3px] rounded-t-xl bg-primary"
          />

          <!-- ─── データウィジェット ─── -->
          <!-- 各ウィジェットが自前の DashboardWidgetCard を持つため、パネル側はコンポーネントを直接描画する -->
          <template v-if="isDataWidget(w.key)">
            <!-- マイカレンダー（SectionCard 内包） -->
            <template v-if="w.key === 'my-calendar'">
              <SectionCard>
                <WidgetMyCalendar />
              </SectionCard>
            </template>
            <!-- F03.15 Phase 3: 今日の時間割 -->
            <DashboardTimetableTodayWidget v-else-if="w.key === 'timetable-today'" />
            <!-- F02.5: ポイっとメモ -->
            <DashboardQuickMemoWidget v-else-if="w.key === 'quick-memo'" />
            <!-- F03.12 §16: 解散通知リマインダー -->
            <WidgetEventDismissalReminder
              v-else-if="w.key === 'event-dismissal-reminder'"
              @has-content="dismissalHasContent = $event"
            />
            <!-- プラットフォームお知らせ（WidgetNotices に統合済み） -->
            <WidgetNotices v-else-if="w.key === 'notices'" />
            <!-- 今後の予定 -->
            <WidgetUpcomingEvents v-else-if="w.key === 'upcoming-events'" />
            <WidgetReturnStayPlan v-else-if="w.key === 'return-stay-plan'" />
            <!-- 個人TODO -->
            <WidgetPersonalTodo v-else-if="w.key === 'personal-todo'" />
            <!-- F02.10: 天気 -->
            <WidgetWeather v-else-if="w.key === 'weather'" />
            <!-- TODOカウントダウン -->
            <WidgetTodoCountdown v-else-if="w.key === 'todo-countdown'" />
            <!-- F06.5: 今日の振り返り -->
            <WidgetReflectionToday v-else-if="w.key === 'reflection-today'" />
            <!-- 未読チャット -->
            <WidgetUnreadThreads v-else-if="w.key === 'unread-threads'" />
            <!-- チームのお知らせ -->
            <WidgetTeamAnnouncements v-else-if="w.key === 'team-announcements'" />
            <!-- 組織のお知らせ -->
            <WidgetOrgAnnouncements v-else-if="w.key === 'org-announcements'" />
            <!-- マイブログ -->
            <WidgetMyBlog v-else-if="w.key === 'my-blog'" />
            <!-- 所属チーム -->
            <WidgetMyTeams v-else-if="w.key === 'my-teams'" />
            <!-- 所属組織 -->
            <WidgetMyOrganizations v-else-if="w.key === 'my-organizations'" />
            <!-- お気に入り -->
            <WidgetFavorites v-else-if="w.key === 'favorites'" />
            <!-- 個人集約タイムライン（所属 team/org 横断） -->
            <WidgetMyTimeline v-else-if="w.key === 'my-timeline'" />
            <!-- 最近のアクティビティ -->
            <WidgetRecentActivity v-else-if="w.key === 'recent-activity'" />
            <!-- Phase2 F03.11: 新着募集 -->
            <WidgetRecruitmentFeed v-else-if="w.key === 'recruitment-feed'" />
            <!-- Phase2 F03.11: 参加予定 -->
            <WidgetMyRecruitments v-else-if="w.key === 'my-recruitments'" />
            <!-- F09.8.1: マイコルクボード（内容のみ→外枠カードで包む） -->
            <DashboardWidgetCard
              v-else-if="w.key === 'my-corkboard'"
              :title="$t(w.labelKey)"
              :icon="w.icon"
              to="/my/corkboard"
              :scrollable="false"
            >
              <WidgetMyCorkboard />
            </DashboardWidgetCard>
            <!-- F17.1: 井戸端ダイジェスト（内容のみ→外枠カードで包む） -->
            <DashboardWidgetCard
              v-else-if="w.key === 'village-lobby-digest'"
              :title="$t(w.labelKey)"
              :icon="w.icon"
              to="/villages"
              :scrollable="false"
            >
              <WidgetVillageLobbyDigest />
            </DashboardWidgetCard>
          </template>

          <!-- ─── ナビゲーションウィジェット ─── -->
          <!-- クリックで画面遷移 + DashboardWidgetCard がカードUIとドラッグ視覚フィードバックを担う -->
          <DashboardWidgetCard
            v-else
            title=""
            :scrollable="false"
            :is-dragging="dragIndex === index"
            :is-drop-target="dropTargetIndex === index && dragIndex !== index"
            @click="onNavCardClick(w.key)"
          >
            <!-- ドラッグハンドル（hover 時に表示） -->
            <i
              class="pi pi-grip-vertical absolute right-3 top-3 cursor-grab text-sm text-surface-300 opacity-0 transition-opacity group-hover:opacity-100 active:cursor-grabbing dark:text-surface-600"
            />

            <div class="flex items-center gap-3" :class="collapsedKeys.has(w.key) ? '' : 'mb-3'">
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
              <!-- 折り畳みボタン（モバイルのみ） -->
              <button
                class="md:hidden flex items-center justify-center rounded-lg p-1.5 text-surface-400 transition-colors hover:bg-surface-100"
                @click.stop="toggleCollapse(w.key)"
              >
                <i
                  class="pi text-sm transition-transform duration-200"
                  :class="collapsedKeys.has(w.key) ? 'pi-chevron-down' : 'pi-chevron-up'"
                />
              </button>
              <!-- ナビゲーション矢印 -->
              <i
                class="pi pi-chevron-right hidden md:block text-xs text-surface-400 opacity-0 transition-opacity group-hover:opacity-100"
              />
            </div>

            <!-- 説明文 -->
            <p
              class="text-xs text-surface-500"
              :class="collapsedKeys.has(w.key) ? 'hidden md:block' : ''"
            >
              {{ $t(w.descriptionKey) }}
            </p>
          </DashboardWidgetCard>
        </div>

        <!-- 条件付き固定パネル: AdminBusinessAlert（v-if のまま・並び替え対象外・グリッド内末尾側） -->
        <!-- F10.7: 業務アラートウィジェット（ADMIN/DEPUTY_ADMIN のみ） -->
        <div
          v-if="hasAdminOrDeputyRole"
          key="admin-business-alert"
          class="col-span-1"
        >
          <WidgetAdminBusinessAlert />
        </div>

        <!-- 広告タイル（Spotlight 掲載面・非表示不可・常に最後・並び替え対象外） -->
        <!-- 候補なしは枠ごと非表示（items.length=1→Secondary 非描画・0→両方非描画）。スケルトンも確保しない（末尾のため CLS 許容）。 -->
        <!-- key は placement 値ベース（spotlight-primary/spotlight-secondary）。KEYS/linkTo には登録しない固定描画。 -->
        <WidgetSpotlightPrimary
          v-if="spotlightPrimary"
          key="spotlight-primary"
          class="order-last"
          :item="spotlightPrimary"
        />
        <WidgetSpotlightSecondary
          v-if="spotlightSecondary"
          key="spotlight-secondary"
          class="order-last"
          :item="spotlightSecondary"
        />
      </TransitionGroup>

      <!-- チームを探す / チームを作る -->
      <div class="mt-8 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        <!-- 探す -->
        <div
          class="rounded-xl border border-dashed border-surface-300 bg-surface-50 p-6 dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="mb-3 flex items-center gap-2">
            <i class="pi pi-search text-primary" />
            <h2 class="text-lg font-semibold">{{ $t('dashboard.personal.find_team_title') }}</h2>
          </div>
          <p class="mb-4 text-sm text-surface-500">
            {{ $t('dashboard.personal.find_team_description') }}
          </p>
          <div class="flex flex-wrap gap-3">
            <Button :label="$t('dashboard.personal.find_team_button')" icon="pi pi-users" outlined @click="navigateTo('/teams')" />
            <Button
              :label="$t('dashboard.personal.find_org_button')"
              icon="pi pi-building"
              outlined
              @click="navigateTo('/organizations')"
            />
          </div>
        </div>

        <!-- 作る -->
        <div
          class="rounded-xl border border-dashed border-surface-300 bg-surface-50 p-6 dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="mb-3 flex items-center gap-2">
            <i class="pi pi-plus-circle text-primary" />
            <h2 class="text-lg font-semibold">{{ $t('dashboard.personal.create_team_title') }}</h2>
          </div>
          <p class="mb-4 text-sm text-surface-500">
            {{ $t('dashboard.personal.create_team_description') }}
          </p>
          <div class="flex flex-wrap gap-3">
            <Button
              :label="$t('dashboard.personal.create_team_button')"
              icon="pi pi-users"
              outlined
              @click="showTeamCreateDialog = true"
            />
            <Button
              :label="$t('dashboard.personal.create_org_button')"
              icon="pi pi-building"
              outlined
              @click="showOrgCreateDialog = true"
            />
          </div>
        </div>
      </div>

      <EntityCreateDialog
        entity-type="team"
        :visible="showTeamCreateDialog"
        @update:visible="showTeamCreateDialog = $event"
        @created="onTeamCreated"
      />
      <EntityCreateDialog
        entity-type="organization"
        :visible="showOrgCreateDialog"
        @update:visible="showOrgCreateDialog = $event"
        @created="onOrgCreated"
      />

      <!-- ウィジェット設定ダイアログ -->
      <DashboardConfigDialog
        v-model:visible="showConfig"
        :widgets="sortedWidgets"
        :is-visible="isVisible"
        @toggle="toggleWidget"
        @reorder="reorder"
      />
    </div>
  </div>
</template>
