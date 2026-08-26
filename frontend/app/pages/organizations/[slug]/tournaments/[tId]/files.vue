<script setup lang="ts">
import type { TournamentDivision } from '~/types/tournament'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
const tId = Number(route.params.tId)

const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const { getTournament, getDivisions } = useTournamentApi()
const notification = useNotification()

const tournamentName = ref('')
const divisions = ref<TournamentDivision[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    await loadPermissions()
    const [tournamentRes, divisionsRes] = await Promise.all([
      getTournament(orgSlug, tId),
      getDivisions(orgSlug, tId),
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
    <PageHeader :title="$t('tournament.files.title')" :back-to="`/organizations/${orgSlug}/tournaments/${tId}`" :back-label="tournamentName || String(tId)" />

    <PageLoading v-if="loading" size="40px" />

    <TournamentFileBrowser
      v-else
      :tournament-id="tId"
      :divisions="divisions"
      :is-admin="isAdminOrDeputy"
    />
  </div>
</template>
