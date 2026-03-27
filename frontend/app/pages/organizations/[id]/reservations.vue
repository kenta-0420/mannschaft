<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)

// 組織配下のチーム一覧（既存のuseOrganizationApiを使用）
const orgApi = useOrganizationApi()
const teams = ref<Array<{ id: number; name: string }>>([])
const loading = ref(true)

onMounted(async () => {
  loading.value = true
  try {
    const res = await orgApi.getTeamsInOrg(orgId)
    teams.value = (res.data ?? []) as Array<{ id: number; name: string }>
  }
  catch { teams.value = [] }
  finally { loading.value = false }
})
</script>

<template>
  <div>
    <h1 class="mb-4 text-2xl font-bold">予約管理</h1>
    <p class="mb-4 text-sm text-surface-500">予約はチーム単位で管理されます。各チームの予約ページをご利用ください。</p>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="teams.length > 0" class="space-y-2">
      <NuxtLink
        v-for="team in teams"
        :key="team.id"
        :to="`/teams/${team.id}/reservations`"
        class="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
      >
        <i class="pi pi-users text-primary" />
        <span class="font-medium">{{ team.name }}</span>
        <i class="pi pi-chevron-right ml-auto text-surface-400" />
      </NuxtLink>
    </div>
    <DashboardEmptyState v-else icon="pi pi-calendar" message="組織にチームがありません" />
  </div>
</template>
