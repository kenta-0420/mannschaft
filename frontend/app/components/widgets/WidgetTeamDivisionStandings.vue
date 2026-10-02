<script setup lang="ts">
import type {
  TournamentHistoryEntry,
  TournamentStanding,
} from '~/types/tournament'

// F08.7.1 / 02 ③: 順位表ウィジェット（参加中ディビジョンの順位表）。
// 2 段取得: tournament-history → 最新エントリの org/tournament/division → standings。
const props = defineProps<{
  teamId: string
}>()

const { getTeamHistory, getStandings } = useTournamentWidgetApi()
const { captureQuiet } = useErrorReport()

const loading = ref(false)
const standingsLoading = ref(false)
const entries = ref<TournamentHistoryEntry[]>([])
const selectedKey = ref<string | null>(null)
const standings = ref<TournamentStanding[]>([])

function entryKey(e: TournamentHistoryEntry): string {
  return `${e.organizationId ?? 0}:${e.identifiers?.tournamentId ?? 0}:${e.identifiers?.divisionId ?? 0}`
}

// 複数大会切替用セレクタ option（history は BE 側で createdAt 降順）
const tournamentOptions = computed(() =>
  entries.value.map((e) => ({
    key: entryKey(e),
    label: `${e.meta?.tournamentName ?? '-'} / ${e.meta?.divisionName ?? '-'}`,
  })),
)

const selectedEntry = computed(
  () => entries.value.find((e) => entryKey(e) === selectedKey.value) ?? null,
)

async function loadStandings() {
  const e = selectedEntry.value
  const orgId = e?.organizationId
  const tournamentId = e?.identifiers?.tournamentId
  const divisionId = e?.identifiers?.divisionId
  if (orgId == null || tournamentId == null || divisionId == null) {
    standings.value = []
    return
  }
  standingsLoading.value = true
  try {
    const res = await getStandings(String(orgId), tournamentId, divisionId)
    standings.value = res.data ?? []
  } catch (err) {
    captureQuiet(err, { context: 'WidgetTeamDivisionStandings: 順位表取得' })
    standings.value = []
  } finally {
    standingsLoading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const res = await getTeamHistory(props.teamId)
    entries.value = res.data.history ?? []
    // デフォルト選択 = 最新（先頭）エントリ
    const first = entries.value[0]
    selectedKey.value = first ? entryKey(first) : null
    if (selectedKey.value) await loadStandings()
  } catch (err) {
    captureQuiet(err, { context: 'WidgetTeamDivisionStandings: 履歴取得' })
    entries.value = []
  } finally {
    loading.value = false
  }
}

watch(selectedKey, loadStandings)
onMounted(load)
watch(() => props.teamId, load)
</script>

<template>
  <div @click.stop>
    <div v-if="loading" class="space-y-2 py-4">
      <Skeleton height="2rem" />
      <Skeleton height="8rem" />
    </div>

    <div
      v-else-if="entries.length === 0"
      class="py-8 text-center text-sm text-surface-400"
    >
      {{ $t('dashboard_widgets.empty_division_standings') }}
    </div>

    <div v-else class="space-y-3">
      <!-- 複数大会切替セレクタ -->
      <Select
        v-if="tournamentOptions.length > 1"
        v-model="selectedKey"
        :options="tournamentOptions"
        option-label="label"
        option-value="key"
        :placeholder="$t('dashboard_widgets.select_tournament')"
        class="w-full"
        size="small"
      />

      <div v-if="standingsLoading" class="py-4">
        <Skeleton height="8rem" />
      </div>

      <div v-else class="max-h-96 overflow-y-auto pr-1">
        <table class="w-full text-left text-xs">
          <thead class="sticky top-0 bg-surface-0 text-surface-400 dark:bg-surface-900">
            <tr>
              <th class="py-1 text-center">{{ $t('dashboard_widgets.rank') }}</th>
              <th class="py-1">{{ $t('dashboard_widgets.team') }}</th>
              <th class="py-1 text-center">{{ $t('dashboard_widgets.win_draw_loss') }}</th>
              <th class="py-1 text-center">{{ $t('dashboard_widgets.points') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(s, i) in standings"
              :key="s.id ?? i"
              class="border-t border-surface-100 dark:border-surface-800"
            >
              <td class="py-1 text-center font-semibold">
                {{ s.team?.rank != null ? s.team.rank : '-' }}
              </td>
              <td class="py-1">{{ s.team?.teamName ?? '-' }}</td>
              <td class="py-1 text-center">
                {{ s.record?.wins ?? 0 }}/{{ s.record?.draws ?? 0 }}/{{ s.record?.losses ?? 0 }}
              </td>
              <td class="py-1 text-center font-semibold">{{ s.score?.points ?? 0 }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
