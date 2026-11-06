<script setup lang="ts">
import type { TeamResponse } from '~/types/team'

// 子ページはNuxt既定のslugを含むpage keyで再生成され、別チームの取得状態を共有しない。
definePageMeta({
  layout: 'team',
  middleware: ['auth', 'admin-console'],
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { t } = useI18n()
const { getTeam } = useTeamApi()
const { handleApiError } = useErrorHandler()
const team = ref<TeamResponse | null>(null)
const loading = ref(true)
const error = shallowRef<unknown>(null)

useHead({ title: () => `${t('adminConsole.cards.reservations.title')} | Mannschaft` })

async function loadPage(): Promise<void> {
  loading.value = true
  error.value = null
  team.value = null
  try {
    const result = await getTeam(teamSlug)
    team.value = result.data
  }
  catch (cause) {
    error.value = cause
    handleApiError(cause)
  }
  finally {
    loading.value = false
  }
}

onMounted(loadPage)
</script>

<template>
  <div class="space-y-6 p-4 md:p-6" data-testid="team-admin-reservations">
    <PageHeader
      :title="t('adminConsole.cards.reservations.title')"
      :back-to="`/teams/${teamSlug}/admin`"
      :back-label="t('adminConsole.team.title')"
    />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="loadPage" />
    <TeamReservationsPanel
      v-else-if="team"
      :team-id="teamSlug"
      :team-timezone="team.timezone ?? 'Asia/Tokyo'"
      :management-view="true"
    />
  </div>
</template>
