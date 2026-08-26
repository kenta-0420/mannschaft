<script setup lang="ts">
import type { TeamTournamentStatsResponse, TournamentHistoryEntry } from '~/types/tournament'

// F08.7.1 / 02 ①: 自チーム大会成績ウィジェット（通算成績 + 直近順位履歴）。
const props = defineProps<{
  teamId: string
}>()

const { getTeamStats, getTeamHistory } = useTournamentWidgetApi()
const { captureQuiet } = useErrorReport()

const loading = ref(false)
const stats = ref<TeamTournamentStatsResponse | null>(null)
const history = ref<TournamentHistoryEntry[]>([])

const hasData = computed(
  () => (stats.value?.totalTournaments ?? 0) > 0 || history.value.length > 0,
)

async function load() {
  loading.value = true
  try {
    const [statsRes, historyRes] = await Promise.allSettled([
      getTeamStats(props.teamId),
      getTeamHistory(props.teamId),
    ])
    stats.value = statsRes.status === 'fulfilled' ? statsRes.value.data : null
    history.value =
      historyRes.status === 'fulfilled' ? (historyRes.value.data.history ?? []) : []
  } catch (err) {
    captureQuiet(err, { context: 'WidgetTeamTournamentRecord: 成績取得' })
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.teamId, load)
</script>

<template>
  <div @click.stop>
    <div v-if="loading" class="space-y-2 py-4">
      <Skeleton height="2rem" />
      <Skeleton height="6rem" />
    </div>

    <div
      v-else-if="!hasData"
      class="py-8 text-center text-sm text-surface-400"
    >
      {{ $t('dashboard_widgets.empty_team_record') }}
    </div>

    <div v-else class="space-y-4">
      <!-- 通算成績サマリ -->
      <div v-if="stats" class="grid grid-cols-3 gap-2 text-center sm:grid-cols-6">
        <div class="rounded-lg bg-surface-50 p-2 dark:bg-surface-800">
          <div class="text-[10px] text-surface-500">{{ $t('dashboard_widgets.tournaments') }}</div>
          <div class="text-lg font-semibold">{{ stats.totalTournaments ?? 0 }}</div>
        </div>
        <div class="rounded-lg bg-surface-50 p-2 dark:bg-surface-800">
          <div class="text-[10px] text-surface-500">{{ $t('dashboard_widgets.win_draw_loss') }}</div>
          <div class="text-sm font-semibold">
            {{ stats.totalWins ?? 0 }}/{{ stats.totalDraws ?? 0 }}/{{ stats.totalLosses ?? 0 }}
          </div>
        </div>
        <div class="rounded-lg bg-surface-50 p-2 dark:bg-surface-800">
          <div class="text-[10px] text-surface-500">{{ $t('dashboard_widgets.played') }}</div>
          <div class="text-lg font-semibold">{{ stats.totalPlayed ?? 0 }}</div>
        </div>
        <div class="rounded-lg bg-surface-50 p-2 dark:bg-surface-800">
          <div class="text-[10px] text-surface-500">{{ $t('dashboard_widgets.goals') }}</div>
          <div class="text-sm font-semibold">
            {{ stats.totalScoreFor ?? 0 }}-{{ stats.totalScoreAgainst ?? 0 }}
          </div>
        </div>
        <div class="rounded-lg bg-surface-50 p-2 dark:bg-surface-800">
          <div class="text-[10px] text-surface-500">{{ $t('dashboard_widgets.best_rank') }}</div>
          <div class="text-lg font-semibold">
            {{ stats.bestRank != null ? stats.bestRank : '-' }}
          </div>
        </div>
      </div>

      <!-- 直近順位履歴 -->
      <div v-if="history.length > 0" class="max-h-96 overflow-y-auto pr-1">
        <table class="w-full text-left text-xs">
          <thead class="text-surface-400">
            <tr>
              <th class="py-1">{{ $t('dashboard_widgets.tournament') }}</th>
              <th class="py-1">{{ $t('dashboard_widgets.division') }}</th>
              <th class="py-1 text-center">{{ $t('dashboard_widgets.rank') }}</th>
              <th class="py-1 text-center">{{ $t('dashboard_widgets.points') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(h, i) in history"
              :key="`${h.identifiers?.tournamentId ?? 0}-${h.identifiers?.divisionId ?? 0}-${i}`"
              class="border-t border-surface-100 dark:border-surface-800"
            >
              <td class="py-1">{{ h.meta?.tournamentName ?? '-' }}</td>
              <td class="py-1">{{ h.meta?.divisionName ?? '-' }}</td>
              <td class="py-1 text-center">{{ h.meta?.finalRank != null ? h.meta.finalRank : '-' }}</td>
              <td class="py-1 text-center">{{ h.record?.points ?? 0 }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
