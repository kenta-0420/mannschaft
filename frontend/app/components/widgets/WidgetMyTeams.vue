<script setup lang="ts">
const teamStore = useTeamStore()
</script>

<template>
  <DashboardWidgetCard title="Links：チーム" icon="pi pi-users" to="/teams">
    <template v-if="teamStore.myTeams.length > 0">
      <div class="flex flex-wrap gap-2">
        <NuxtLink
          v-for="team in teamStore.myTeams.slice(0, 8)"
          :key="team.id"
          :to="`/teams/${team.id}`"
          class="flex items-center gap-2 rounded-lg border border-surface-400 bg-surface-50 px-3 py-2 text-sm transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-700"
        >
          <i class="pi pi-users text-xs text-primary" />
          <span class="font-medium">{{ team.nickname1 || team.name }}</span>
          <RoleBadge :role="team.role" />
        </NuxtLink>
      </div>
      <div class="mt-3 flex justify-end">
        <NuxtLink to="/teams" class="text-sm text-primary hover:underline">すべて表示</NuxtLink>
      </div>
    </template>
    <DashboardEmptyState v-else icon="pi pi-users" message="まだチームに参加していません" />
  </DashboardWidgetCard>
</template>
