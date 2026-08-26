<script setup lang="ts">
// F08.10 試合一覧カード（04_frontend_and_ux.md §G.1）。
// 日付・相手・スコア・種別バッジ・進行中バッジを表示し、タップで詳細/ライブへ。
import type { MatchSummaryResponse } from '~/types/match'

const props = defineProps<{
  match: MatchSummaryResponse
}>()

const emit = defineEmits<{
  (e: 'select', match: MatchSummaryResponse): void
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

const isInProgress = computed(() => props.match.status === 'IN_PROGRESS')
const isCompleted = computed(() => props.match.status === 'COMPLETED')

const kickoffLabel = computed(() => formatDateTime(props.match.kickoffAt))

const opponentLabel = computed(() =>
  props.match.opponentName?.trim()
    ? props.match.opponentName
    : t('match.list.no_opponent'),
)

const scoreLabel = computed(() => {
  if (!isCompleted.value) return null
  const home = props.match.homeScore ?? 0
  const away = props.match.awayScore ?? 0
  // 表示は自チーム視点ではなく home-away をそのまま並べる（BE 集計準拠）
  return t('match.list.score_vs', { home, away })
})

const kindLabel = computed(() =>
  props.match.kind ? t(`match.kind.${props.match.kind}`) : '',
)

// 勝敗バッジ（COMPLETED かつホーム/アウェイが判明している場合）
const resultBadge = computed<{ label: string; cls: string } | null>(() => {
  if (!isCompleted.value) return null
  const home = props.match.homeScore ?? 0
  const away = props.match.awayScore ?? 0
  const side = props.match.homeAway
  // 中立や不明はスコアのみ表示し勝敗バッジは出さない
  if (side !== 'HOME' && side !== 'AWAY') return null
  const ourScore = side === 'HOME' ? home : away
  const theirScore = side === 'HOME' ? away : home
  if (ourScore > theirScore) {
    return { label: t('match.list.result.win_label'), cls: 'bg-green-100 text-green-700' }
  }
  if (ourScore < theirScore) {
    return { label: t('match.list.result.loss_label'), cls: 'bg-red-100 text-red-600' }
  }
  return { label: t('match.list.result.draw_label'), cls: 'bg-surface-200 text-surface-600' }
})
</script>

<template>
  <button
    type="button"
    class="block w-full rounded-xl border border-surface-300 bg-surface-0 p-4 text-left transition hover:border-primary-400 hover:shadow-sm"
    @click="emit('select', match)"
  >
    <div class="mb-2 flex items-center justify-between gap-2">
      <span class="rounded bg-surface-100 px-2 py-0.5 text-xs text-surface-600">
        {{ kindLabel }}
      </span>
      <span
        v-if="isInProgress"
        class="flex items-center gap-1 rounded bg-orange-100 px-2 py-0.5 text-xs font-medium text-orange-700"
      >
        <i class="pi pi-circle-fill text-[0.5rem]" />
        {{ t('match.list.in_progress_badge') }}
      </span>
      <span
        v-else-if="resultBadge"
        :class="resultBadge.cls"
        class="rounded px-2 py-0.5 text-xs font-bold"
      >
        {{ resultBadge.label }}
      </span>
    </div>

    <h3 class="text-sm font-semibold">
      {{ t('match.list.vs', { opponent: opponentLabel }) }}
    </h3>

    <div class="mt-2 flex items-center justify-between gap-3 text-xs text-surface-500">
      <span v-if="kickoffLabel">{{ kickoffLabel }}</span>
      <span v-if="match.venue" class="truncate">{{ match.venue }}</span>
      <span class="ml-auto text-base font-bold text-primary-600">
        {{ scoreLabel ?? t('match.list.no_score') }}
      </span>
    </div>
  </button>
</template>
