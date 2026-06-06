<script setup lang="ts">
import type { TournamentDivision } from '~/types/tournament'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgId = String(route.params.id)
const tId = Number(route.params.tId)

const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)
const { getTournament, getDivisions } = useTournamentApi()
const notification = useNotification()

const tournamentName = ref('')
const divisions = ref<TournamentDivision[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    await loadPermissions()
    const [tournamentRes, divisionsRes] = await Promise.all([
      getTournament(orgId, tId),
      getDivisions(orgId, tId),
    ])
    tournamentName.value = tournamentRes.data.content?.name ?? ''
    divisions.value = divisionsRes.data
  }
  catch {
    notification.error(useI18n().t('tournament.files.load_error'))
  }
  finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton :to="`/organizations/${orgId}/tournaments/${tId}`" :label="tournamentName || String(tId)" />
    </div>

    <PageHeader :title="$t('tournament.files.title')" />

    <PageLoading v-if="loading" size="40px" />

    <TournamentFileBrowser
      v-else
      :tournament-id="tId"
      :divisions="divisions"
      :is-admin="isAdminOrDeputy"
    />
  </div>
</template>
