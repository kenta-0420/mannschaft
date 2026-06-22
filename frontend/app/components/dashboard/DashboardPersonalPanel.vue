<script setup lang="ts">
/**
 * F22.1 個人パネル。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §1 / 03 §3.9
 * - 既存 dashboard.vue の中身をそのまま内包（要件 3・widget 構成は F02.2 のまま不変）。
 * - タグ行・検索フォームは出さない（個人には所属スコープ選択も「チーム/組織検索」も不要）。
 */
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
})
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

      <!-- F03.12 §16 解散通知未送信リマインダー（主催者向け） -->
      <div class="mb-4">
        <WidgetEventDismissalReminder />
      </div>

      <!-- データウィジェット群 (広告込み) -->
      <div class="mb-8 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        <WidgetFamilyHub v-if="hasFamilyTeam" />
        <WidgetNotices />
        <!-- マイカレンダー（lg:col-span-2 で広く取る） -->
        <div class="lg:col-span-2">
          <SectionCard>
            <WidgetMyCalendar />
          </SectionCard>
        </div>
        <WidgetUpcomingEvents />
        <WidgetPersonalTodo />
        <!-- F02.10: 天気ウィジェット（sort_order=3、NOTICES/UPCOMING_EVENTS/PERSONAL_TODO の次） -->
        <WidgetWeather />
        <WidgetTodoCountdown />
        <!-- F03.15 Phase 3: 個人時間割「今日の時間割」（メモは各コマに inline 折り畳み表示） -->
        <DashboardTimetableTodayWidget />
        <!-- F02.5: ポイっとメモ ダッシュボードウィジェット（未整理メモ最新 5 件） -->
        <DashboardQuickMemoWidget />
        <!-- F06.5 follow-up A: 今日の振り返り導線（reflection の常設入口） -->
        <WidgetReflectionToday />
        <WidgetUnreadThreads />
        <WidgetTeamAnnouncements />
        <WidgetOrgAnnouncements />
        <WidgetMyBlog />
        <WidgetMyTeams />
        <WidgetMyOrganizations />
        <!-- F10.7: 業務アラートウィジェット（ADMIN/DEPUTY_ADMIN のみ） -->
        <WidgetAdminBusinessAlert v-if="hasAdminOrDeputyRole" />
        <!-- F02.9 Phase 2: お気に入りウィジェット -->
        <WidgetFavorites />
        <WidgetAmazonAd scope-type="personal" />
        <WidgetRakutenAd scope-type="personal" />
        <WidgetRecentActivity />
      </div>

      <!-- チームを探す / チームを作る -->
      <div class="mt-8 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        <!-- 探す -->
        <div
          class="rounded-xl border border-dashed border-surface-300 bg-surface-50 p-6 dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="mb-3 flex items-center gap-2">
            <i class="pi pi-search text-primary" />
            <h2 class="text-lg font-semibold">新しいチーム・組織を見つける</h2>
          </div>
          <p class="mb-4 text-sm text-surface-500">
            参加したいチームや組織を検索してサポーターとして申請できます。<br >
            メンバーとして参加するには招待リンクが必要です。
          </p>
          <div class="flex flex-wrap gap-3">
            <Button label="チームを探す" icon="pi pi-users" outlined @click="navigateTo('/teams')" />
            <Button
              label="組織を探す"
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
            <h2 class="text-lg font-semibold">新しいチーム・組織を作る</h2>
          </div>
          <p class="mb-4 text-sm text-surface-500">
            家族・スポーツ・地域・企業など新しいグループを作成できます。<br >
            作成後すぐにメンバーを招待して利用できます。
          </p>
          <div class="flex flex-wrap gap-3">
            <Button
              label="チームを作る"
              icon="pi pi-users"
              outlined
              @click="showTeamCreateDialog = true"
            />
            <Button
              label="組織を作る"
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
    </div>
  </div>
</template>
