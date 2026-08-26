<script setup lang="ts">
// F08.10 観戦用スコアボード（04_frontend_and_ux.md §G.17）。
// 記録側 MatchScoreboard はタイマー状態（TimerState）を必須とするが、観戦は配信スナップショット
// （スコア＋試合ステータス）のみを保持しタイマーを持たないため、ステータス表示版を別に用意する。
import type { MatchSummaryStatus } from '~/types/match'

const props = defineProps<{
  homeScore: number
  awayScore: number
  homePenaltyScore?: number | null
  awayPenaltyScore?: number | null
  opponentName?: string | null
  status: MatchSummaryStatus | null
}>()

const { t } = useI18n()

const statusLabel = computed(() =>
  props.status ? t(`match.live.spectator.status.${props.status}`) : '',
)
const isLive = computed(() => props.status === 'IN_PROGRESS')
const hasPenalty = computed(
  () => (props.homePenaltyScore ?? 0) > 0 || (props.awayPenaltyScore ?? 0) > 0,
)
const opponentLabel = computed(() => props.opponentName?.trim() || t('match.live.scoreboard.away'))
</script>

<template>
  <div class="rounded-xl bg-surface-900 px-4 py-3 text-surface-0 shadow">
    <div class="flex items-center justify-between gap-3">
      <div class="flex flex-1 flex-col items-center">
        <span class="text-xs text-surface-300">{{ t('match.live.scoreboard.home') }}</span>
        <span class="text-3xl font-bold tabular-nums">{{ homeScore }}</span>
      </div>

      <div class="flex flex-col items-center">
        <span
          v-if="statusLabel"
          class="rounded px-2 py-0.5 text-xs font-medium"
          :class="isLive ? 'bg-green-500/20 text-green-300' : 'bg-surface-700 text-surface-200'"
        >
          {{ statusLabel }}
        </span>
        <span
          v-if="hasPenalty"
          class="mt-1 text-xs text-amber-300"
        >
          {{ t('match.live.scoreboard.penalty', { home: homePenaltyScore ?? 0, away: awayPenaltyScore ?? 0 }) }}
        </span>
      </div>

      <div class="flex flex-1 flex-col items-center">
        <span class="max-w-[8rem] truncate text-xs text-surface-300">{{ opponentLabel }}</span>
        <span class="text-3xl font-bold tabular-nums">{{ awayScore }}</span>
      </div>
    </div>
  </div>
</template>
