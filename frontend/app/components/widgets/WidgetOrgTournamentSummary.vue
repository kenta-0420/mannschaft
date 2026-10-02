<script setup lang="ts">
import type { OrganizationTournamentSummaryEntry } from '~/types/tournament'

// F08.7.1 / 02 ②: 主催大会サマリウィジェット（各大会×各部の首位・参加数・status）。
const props = defineProps<{
  orgId: string
}>()

const { getOrganizationSummary } = useTournamentWidgetApi()
const { captureQuiet } = useErrorReport()

const loading = ref(false)
const tournaments = ref<OrganizationTournamentSummaryEntry[]>([])

async function load() {
  loading.value = true
  try {
    const res = await getOrganizationSummary(props.orgId)
    tournaments.value = res.data.tournaments ?? []
  } catch (err) {
    captureQuiet(err, { context: 'WidgetOrgTournamentSummary: サマリ取得' })
    tournaments.value = []
  } finally {
    loading.value = false
  }
}

function statusSeverity(status: string): string {
  switch (status) {
    case 'IN_PROGRESS':
      return 'success'
    case 'OPEN':
      return 'info'
    case 'COMPLETED':
      return 'secondary'
    case 'CANCELLED':
    case 'ARCHIVED':
      return 'contrast'
    default:
      return 'info'
  }
}

onMounted(load)
watch(() => props.orgId, load)
</script>

<template>
  <div @click.stop>
    <div v-if="loading" class="space-y-2 py-4">
      <Skeleton height="2rem" />
      <Skeleton height="6rem" />
    </div>

    <div
      v-else-if="tournaments.length === 0"
      class="py-8 text-center text-sm text-surface-400"
    >
      {{ $t('dashboard_widgets.empty_org_summary') }}
    </div>

    <div v-else class="max-h-96 space-y-4 overflow-y-auto pr-1">
      <div
        v-for="t in tournaments"
        :key="t.tournamentId"
        class="rounded-lg border border-surface-100 p-3 dark:border-surface-800"
      >
        <div class="mb-2 flex items-center justify-between gap-2">
          <h4 class="truncate text-sm font-semibold">{{ t.name }}</h4>
          <Tag :value="t.status" :severity="statusSeverity(t.status)" />
        </div>

        <table class="w-full text-left text-xs">
          <thead class="text-surface-400">
            <tr>
              <th class="py-1">{{ $t('dashboard_widgets.division') }}</th>
              <th class="py-1 text-center">{{ $t('dashboard_widgets.participant_count') }}</th>
              <th class="py-1">{{ $t('dashboard_widgets.leader') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="d in t.divisions"
              :key="d.divisionId"
              class="border-t border-surface-100 dark:border-surface-800"
            >
              <td class="py-1">{{ d.name }}</td>
              <td class="py-1 text-center">{{ d.participantCount }}</td>
              <td class="py-1">{{ d.leaderTeamName ?? '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
