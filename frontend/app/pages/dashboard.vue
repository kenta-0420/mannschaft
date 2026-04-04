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
      <div class="mb-6">
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ greeting }}、{{ authStore.currentUser?.displayName ?? 'ユーザー' }}さん
        </h1>
        <p class="mt-1 text-sm text-surface-500">{{ timedMessage }}</p>
      </div>

      <!-- データウィジェット群 (広告込み) -->
      <div class="mb-8 grid grid-cols-1 gap-4 md:grid-cols-2">
        <WidgetFamilyHub />
        <WidgetNotices />
        <WidgetUpcomingEvents />
        <WidgetPersonalTodo />
        <WidgetUnreadThreads />
        <WidgetTeamAnnouncements />
        <WidgetOrgAnnouncements />
        <WidgetMyBlog />
        <WidgetAmazonAd scope-type="personal" />
        <WidgetRakutenAd scope-type="personal" />
        <WidgetRecentActivity />
      </div>

      <!-- マイチーム & マイ組織セクション -->
      <div class="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-2">
        <!-- マイチーム -->
        <div>
          <div class="mb-3 flex items-center justify-between">
            <h2 class="text-lg font-semibold">マイチーム</h2>
            <NuxtLink to="/teams" class="text-sm text-primary hover:underline">すべて表示</NuxtLink>
          </div>
          <div v-if="teamStore.myTeams.length > 0" class="space-y-2">
            <NuxtLink
              v-for="team in teamStore.myTeams.slice(0, 5)"
              :key="team.id"
              :to="`/teams/${team.id}`"
              class="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 p-3 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
            >
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary"
              >
                <i class="pi pi-users" />
              </div>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium">{{ team.nickname1 || team.name }}</p>
                <p class="text-xs text-surface-500">{{ team.template }}</p>
              </div>
              <RoleBadge :role="team.role" />
            </NuxtLink>
          </div>
          <DashboardEmptyState v-else icon="pi pi-users" message="まだチームに参加していません" />
        </div>

        <!-- マイ組織 -->
        <div>
          <div class="mb-3 flex items-center justify-between">
            <h2 class="text-lg font-semibold">マイ組織</h2>
            <NuxtLink to="/organizations" class="text-sm text-primary hover:underline"
              >すべて表示</NuxtLink
            >
          </div>
          <div v-if="orgStore.myOrganizations.length > 0" class="space-y-2">
            <NuxtLink
              v-for="org in orgStore.myOrganizations.slice(0, 5)"
              :key="org.id"
              :to="`/organizations/${org.id}`"
              class="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 p-3 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
            >
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary"
              >
                <i class="pi pi-building" />
              </div>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium">{{ org.nickname1 || org.name }}</p>
                <p class="text-xs text-surface-500">
                  {{ org.orgType === 'NONPROFIT' ? '非営利' : '営利' }}
                </p>
              </div>
              <RoleBadge :role="org.role" />
            </NuxtLink>
          </div>
          <DashboardEmptyState v-else icon="pi pi-building" message="まだ組織に参加していません" />
        </div>
      </div>

      <!-- 新しいチーム・組織を探す -->
      <div
        class="mt-8 rounded-xl border border-dashed border-surface-300 bg-surface-50 p-6 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-search text-primary" />
          <h2 class="text-lg font-semibold">新しいチーム・組織を見つける</h2>
        </div>
        <p class="mb-4 text-sm text-surface-500">
          参加したいチームや組織を検索して、サポーターとして申請できます。メンバーとして参加するには招待リンクが必要です。
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
    </div>
  </div>
</template>
