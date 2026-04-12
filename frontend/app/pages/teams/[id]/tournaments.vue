<script setup lang="ts">
import type { TournamentHistory, TournamentTeamStats, IndividualRanking } from '~/types/tournament'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const router = useRouter()
const teamId = Number(route.params.id)

const {
  getTeamTournamentHistory,
  getTeamTournamentStats,
  getIndividualRankings,
} = useTournamentApi()
const { showError } = useNotification()

const history = ref<TournamentHistory[]>([])
const stats = ref<TournamentTeamStats | null>(null)
const scoringRankings = ref<IndividualRanking[]>([])
const loading = ref(false)
const rankingLoading = ref(false)
const selectedTournamentId = ref<number | null>(null)
const activeTab = ref<'history' | 'stats' | 'ranking'>('history')

async function load() {
  loading.value = true
  try {
    const [histRes, statsRes] = await Promise.all([
      getTeamTournamentHistory(teamId),
      getTeamTournamentStats(teamId),
    ])
    history.value = histRes.data
    stats.value = statsRes.data
  } catch {
    showError('大会履歴の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadRankings(orgId: number, tournamentId: number) {
  rankingLoading.value = true
  selectedTournamentId.value = tournamentId
  try {
    const res = await getIndividualRankings(orgId, tournamentId, 'goals')
    scoringRankings.value = res.data
    activeTab.value = 'ranking'
  } catch {
    showError('得点ランキングの取得に失敗しました')
  } finally {
    rankingLoading.value = false
  }
}

function getFinalRankLabel(rank: number | null): string {
  if (rank === null) return '-'
  return `第${rank}位`
}

function getFinalRankClass(rank: number | null): string {
  if (rank === 1) return 'text-yellow-500 font-bold'
  if (rank !== null && rank <= 3) return 'text-orange-500 font-semibold'
  return 'text-primary-600 font-semibold'
}

const winRate = computed(() => {
  if (!stats.value || stats.value.totalMatches === 0) return 0
  return Math.round((stats.value.wins / stats.value.totalMatches) * 100)
})

const goalDifference = computed(() => {
  if (!stats.value) return 0
  return stats.value.goalsFor - stats.value.goalsAgainst
})

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-5xl">
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1 class="text-2xl font-bold">参加大会・リーグ</h1>
    </div>

    <PageLoading v-if="loading" size="40px" />

    <template v-else>
      <!-- 統計サマリー -->
      <div v-if="stats" class="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-center">
          <p class="text-2xl font-bold text-primary-600">{{ stats.totalTournaments }}</p>
          <p class="text-xs text-surface-400">参加大会数</p>
        </div>
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-center">
          <p class="text-2xl font-bold text-green-600">{{ stats.wins }}</p>
          <p class="text-xs text-surface-400">勝利</p>
        </div>
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-center">
          <p class="text-2xl font-bold text-surface-500">{{ stats.draws }}</p>
          <p class="text-xs text-surface-400">引き分け</p>
        </div>
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-center">
          <p class="text-2xl font-bold text-red-500">{{ stats.losses }}</p>
          <p class="text-xs text-surface-400">敗北</p>
        </div>
      </div>

      <!-- タブ -->
      <div class="mb-4 flex gap-1 border-b border-surface-200">
        <button
          class="px-4 py-2 text-sm font-medium transition-colors"
          :class="
            activeTab === 'history'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700'
          "
          @click="activeTab = 'history'"
        >
          参加履歴
        </button>
        <button
          class="px-4 py-2 text-sm font-medium transition-colors"
          :class="
            activeTab === 'stats'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700'
          "
          @click="activeTab = 'stats'"
        >
          通算成績
        </button>
        <button
          class="px-4 py-2 text-sm font-medium transition-colors"
          :class="
            activeTab === 'ranking'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700'
          "
          @click="activeTab = 'ranking'"
        >
          得点ランキング
        </button>
      </div>

      <!-- 参加履歴タブ -->
      <template v-if="activeTab === 'history'">
        <div v-if="history.length > 0" class="grid gap-4 sm:grid-cols-2">
          <div
            v-for="t in history"
            :key="`${t.tournamentId}-${t.divisionName}`"
            class="rounded-xl border border-surface-300 bg-surface-0 p-4"
          >
            <div class="mb-2 flex items-center justify-between gap-2">
              <span class="rounded bg-surface-100 px-2 py-0.5 text-xs text-surface-600">
                {{ t.seasonYear }}年度
              </span>
              <span :class="getFinalRankClass(t.finalRank)" class="text-sm">
                {{ getFinalRankLabel(t.finalRank) }}
              </span>
            </div>
            <h3 class="text-sm font-semibold">{{ t.title }}</h3>
            <p class="mt-0.5 text-xs text-surface-400">{{ t.divisionName }}</p>
            <div class="mt-3 flex items-center gap-3 text-xs text-surface-500">
              <span>{{ t.played }}試合</span>
              <span class="text-green-600">{{ t.won }}勝</span>
              <span>{{ t.drawn }}分</span>
              <span class="text-red-500">{{ t.lost }}敗</span>
              <span class="ml-auto font-medium">{{ t.points }}pt</span>
            </div>
          </div>
        </div>

        <div v-else class="py-12 text-center">
          <i class="pi pi-trophy mb-3 text-4xl text-surface-300" />
          <p class="text-surface-400">参加した大会がありません</p>
        </div>
      </template>

      <!-- 通算成績タブ -->
      <template v-if="activeTab === 'stats' && stats">
        <SectionCard title="通算成績">
          <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <div class="rounded-lg bg-surface-50 p-4">
              <p class="text-xs text-surface-400">参加大会数</p>
              <p class="mt-1 text-2xl font-bold">{{ stats.totalTournaments }} 大会</p>
            </div>
            <div class="rounded-lg bg-surface-50 p-4">
              <p class="text-xs text-surface-400">総試合数</p>
              <p class="mt-1 text-2xl font-bold">{{ stats.totalMatches }} 試合</p>
            </div>
            <div class="rounded-lg bg-green-50 p-4">
              <p class="text-xs text-green-500">勝利</p>
              <p class="mt-1 text-2xl font-bold text-green-700">{{ stats.wins }} 勝</p>
            </div>
            <div class="rounded-lg bg-surface-50 p-4">
              <p class="text-xs text-surface-400">引き分け</p>
              <p class="mt-1 text-2xl font-bold">{{ stats.draws }} 分</p>
            </div>
            <div class="rounded-lg bg-red-50 p-4">
              <p class="text-xs text-red-400">敗北</p>
              <p class="mt-1 text-2xl font-bold text-red-600">{{ stats.losses }} 負</p>
            </div>
            <div class="rounded-lg bg-surface-50 p-4">
              <p class="text-xs text-surface-400">得点 / 失点</p>
              <p class="mt-1 text-2xl font-bold">
                {{ stats.goalsFor }}
                <span class="text-base text-surface-400">/</span>
                {{ stats.goalsAgainst }}
              </p>
              <p
                class="text-xs font-medium"
                :class="goalDifference >= 0 ? 'text-green-600' : 'text-red-500'"
              >
                得失点差: {{ goalDifference >= 0 ? '+' : '' }}{{ goalDifference }}
              </p>
            </div>
          </div>

          <div class="mt-6">
            <p class="mb-2 text-sm font-medium text-surface-500">
              勝率: <span class="font-bold text-green-600">{{ winRate }}%</span>
            </p>
            <div class="h-3 overflow-hidden rounded-full bg-surface-200">
              <div
                class="h-full rounded-full bg-green-500 transition-all duration-500"
                :style="{ width: `${winRate}%` }"
              />
            </div>
          </div>
        </SectionCard>
      </template>

      <!-- 得点ランキングタブ -->
      <template v-if="activeTab === 'ranking'">
        <PageLoading v-if="rankingLoading" size="40px" />
        <template v-else>
          <div v-if="scoringRankings.length > 0">
            <div class="overflow-hidden rounded-xl border border-surface-200 bg-surface-0">
              <table class="w-full text-sm">
                <thead class="bg-surface-50">
                  <tr>
                    <th class="px-4 py-3 text-left font-medium text-surface-500">順位</th>
                    <th class="px-4 py-3 text-left font-medium text-surface-500">選手名</th>
                    <th class="px-4 py-3 text-left font-medium text-surface-500">所属チーム</th>
                    <th class="px-4 py-3 text-right font-medium text-surface-500">得点</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-surface-100">
                  <tr
                    v-for="r in scoringRankings"
                    :key="r.userId"
                    class="transition-colors hover:bg-surface-50"
                  >
                    <td class="px-4 py-3">
                      <span
                        class="inline-flex h-7 w-7 items-center justify-center rounded-full text-xs font-bold"
                        :class="
                          r.rank === 1
                            ? 'bg-yellow-100 text-yellow-700'
                            : r.rank === 2
                              ? 'bg-surface-200 text-surface-700'
                              : r.rank === 3
                                ? 'bg-orange-100 text-orange-700'
                                : 'bg-surface-100 text-surface-500'
                        "
                      >
                        {{ r.rank }}
                      </span>
                    </td>
                    <td class="px-4 py-3 font-medium">{{ r.displayName }}</td>
                    <td class="px-4 py-3 text-surface-500">{{ r.teamName }}</td>
                    <td class="px-4 py-3 text-right font-bold text-primary-600">{{ r.value }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <div v-else class="py-12 text-center">
            <i class="pi pi-star mb-3 text-4xl text-surface-300" />
            <p class="text-surface-400">得点ランキングを表示するには履歴から大会を選択してください</p>
          </div>
        </template>
      </template>
    </template>
  </div>
</template>
