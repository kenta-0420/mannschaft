<script setup lang="ts">
const teamStore = useTeamStore()
const moduleApi = useModuleApi()
const reservationEnabledTeamIds = ref<number[]>([])

onMounted(async () => {
  const teams = teamStore.myTeams.slice(0, 8)
  const results = await Promise.allSettled(
    teams.map(team => moduleApi.getTeamModules(team.slug))
  )
  const enabled: number[] = []
  results.forEach((result, idx) => {
    const team = teams[idx]
    if (result.status === 'fulfilled' && result.value && team) {
      const modules = result.value.data ?? []
      if (modules.some(m => m.moduleSlug === 'reservation' && m.isEnabled)) {
        enabled.push(team.id)
      }
    }
  })
  reservationEnabledTeamIds.value = enabled
})
</script>

<template>
  <DashboardWidgetCard title="Links：チーム" icon="pi pi-users" to="/teams">
    <template v-if="teamStore.myTeams.length > 0">
      <div class="flex flex-wrap gap-2">
        <div
          v-for="team in teamStore.myTeams.slice(0, 8)"
          :key="team.id"
          class="flex items-center rounded-lg border border-surface-400 bg-surface-50 text-sm transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-700"
        >
          <NuxtLink
            :to="team.slug ? `/teams/${team.slug}` : undefined"
            class="flex flex-1 items-center gap-2 px-3 py-2"
          >
            <i class="pi pi-users text-xs text-primary" />
            <span class="font-medium">{{ team.nickname1 || team.name }}</span>
            <RoleBadge :role="team.role" />
          </NuxtLink>
          <NuxtLink
            v-if="reservationEnabledTeamIds.includes(team.id) && team.slug"
            :to="`/teams/${team.slug}/reservations`"
            v-tooltip.top="$t('dashboard.team_reservation_link')"
            class="border-l border-surface-400 px-3 py-2 text-primary transition-colors hover:bg-surface-100 dark:border-surface-600 dark:hover:bg-surface-600"
          >
            <i class="pi pi-calendar text-sm" />
          </NuxtLink>
        </div>
      </div>
      <div class="mt-3 flex justify-end">
        <NuxtLink to="/teams" class="text-sm text-primary hover:underline">すべて表示</NuxtLink>
      </div>
    </template>
    <DashboardEmptyState v-else icon="pi pi-users" message="まだチームに参加していません" />
  </DashboardWidgetCard>
</template>
