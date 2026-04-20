<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const authStore = useAuthStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const dashboardStore = useDashboardStore()
const greeting = useGreeting()
const timedMessage = useTimedMessage()

const hasFamilyTeam = computed(() => teamStore.myTeams.some((t) => t.template === 'FAMILY'))

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
            {{ greeting }}、{{ authStore.currentUser?.displayName ?? 'ユーザー' }}さん
          </h1>
          <p class="mt-1 text-sm text-surface-500">{{ timedMessage }}</p>
        </div>
      </div>

      <!-- データウィジェット群 (広告込み) -->
      <div class="mb-8 grid grid-cols-1 gap-4 md:grid-cols-2">
        <WidgetFamilyHub v-if="hasFamilyTeam" />
        <WidgetNotices />
        <WidgetUpcomingEvents />
        <WidgetPersonalTodo />
        <WidgetUnreadThreads />
        <WidgetTeamAnnouncements />
        <WidgetOrgAnnouncements />
        <WidgetMyBlog />
        <WidgetMyTeams />
        <WidgetMyOrganizations />
        <WidgetAmazonAd scope-type="personal" />
        <WidgetRakutenAd scope-type="personal" />
        <WidgetRecentActivity />
      </div>

      <!-- チームを探す / チームを作る -->
      <div class="mt-8 grid grid-cols-1 gap-4 md:grid-cols-2">
        <!-- 探す -->
        <div
          class="rounded-xl border border-dashed border-surface-300 bg-surface-50 p-6 dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="mb-3 flex items-center gap-2">
            <i class="pi pi-search text-primary" />
            <h2 class="text-lg font-semibold">新しいチーム・組織を見つける</h2>
          </div>
          <p class="mb-4 text-sm text-surface-500">
            参加したいチームや組織を検索してサポーターとして申請できます。<br />
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
            家族・スポーツ・地域・企業など新しいグループを作成できます。<br />
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
