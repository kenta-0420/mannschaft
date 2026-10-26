<script setup lang="ts">
// F08.10 バレーボール用セット入力シート（sports/04_volleyball.md §8.1 / §G.16a）。
// 主動線: セットごとに home_points/away_points を数値ステッパーで直接入力。
// デュース条件を満たしたときのみ「セット確定」ボタンが有効化（§4.2）。
// 全セット確定 or 3 セット先取で試合決着 → completeMatch emit。
// 副動線（詳細記録・ラリー逐次）は +1/-1 ボタン列で提供。
import type { MatchSetTrackerReturn } from '~/composables/match/sport/useMatchSetTracker'

const props = defineProps<{
  /** 親（live.vue）から渡されたセットトラッカー（SET_BASED モジュールのもの）。 */
  tracker: MatchSetTrackerReturn
  /** 自チームのサイド（スコア表示のホーム/アウェイラベルに使う）。 */
  ownTeamSide: 'HOME' | 'AWAY'
  /** 相手チーム名（表示用・nullable）。 */
  opponentName: string | null
  /** 記録権限があるか（false=閲覧専用・入力 UI を出さない）。 */
  canRecord: boolean
}>()

const emit = defineEmits<{
  /** 試合完了を親へ通知（completeMatch 呼び出し要求）。 */
  completeMatch: []
}>()

const { t } = useI18n()

const {
  trackerState,
  sets,
  currentSetNumber,
  currentSet,
  homeWins,
  awayWins,
  isDeuce,
  canConfirmSet,
  isCompleted,
  startFirstSet,
  incrementHome,
  incrementAway,
  decrementHome,
  decrementAway,
  setHomePoints,
  setAwayPoints,
  confirmCurrentSet,
  setsToWin,
} = props.tracker

/** ホームラベル（自チーム名またはデフォルト）。 */
const homeLabel = computed(() =>
  props.ownTeamSide === 'HOME'
    ? t('match.live.scoreboard.home')
    : (props.opponentName ?? t('match.live.scoreboard.away')),
)
/** アウェイラベル。 */
const awayLabel = computed(() =>
  props.ownTeamSide === 'AWAY'
    ? t('match.live.scoreboard.home')
    : (props.opponentName ?? t('match.live.scoreboard.away')),
)

/** 現在セットの目標点数ラベル（25 or 15）。 */
const targetLabel = computed(() => {
  const sn = currentSetNumber.value
  if (!sn) return 25
  const finalSet = props.tracker.bestOf === 5 ? 5 : 3
  return sn === finalSet ? 15 : 25
})

/** ホームの InputNumber バインド（v-model 互換）。 */
const homePointsModel = computed({
  get: () => currentSet.value?.homePoints ?? 0,
  set: (v: number) => setHomePoints(v),
})

/** アウェイの InputNumber バインド（v-model 互換）。 */
const awayPointsModel = computed({
  get: () => currentSet.value?.awayPoints ?? 0,
  set: (v: number) => setAwayPoints(v),
})

function onConfirmSet(): void {
  confirmCurrentSet()
  // セット確定後に試合決着（3 セット先取）となった場合、親に通知
  if (isCompleted.value) {
    emit('completeMatch')
  }
}

function onStartFirstSet(): void {
  startFirstSet()
}

/** セット番号の表示ラベル（「第 N セット」）。 */
function setLabel(n: number): string {
  return t('match.set.set_label', { n })
}
</script>

<template>
  <div class="rounded-lg border border-surface-200 bg-white p-4">
    <!-- セット制スコアボード（獲得セット数 + 現セット得点） -->
    <div class="mb-4 text-center">
      <p class="mb-1 text-xs text-surface-400">{{ t('match.set.sets_label') }}</p>
      <div class="flex items-center justify-center gap-4 text-lg font-bold">
        <div class="flex flex-col items-center">
          <span class="text-xs text-surface-500">{{ homeLabel }}</span>
          <span class="text-3xl font-black text-primary">{{ homeWins }}</span>
        </div>
        <span class="text-surface-400">-</span>
        <div class="flex flex-col items-center">
          <span class="text-xs text-surface-500">{{ awayLabel }}</span>
          <span class="text-3xl font-black text-primary">{{ awayWins }}</span>
        </div>
      </div>
      <p class="mt-1 text-xs text-surface-400">
        {{ t('match.set.sets_to_win', { n: setsToWin }) }}
      </p>
    </div>

    <!-- 試合終了 -->
    <div v-if="isCompleted" class="rounded-lg bg-primary-50 p-3 text-center text-sm text-primary">
      <i class="pi pi-trophy mr-2" />
      {{ t('match.set.match_completed') }}
    </div>

    <!-- 試合開始前 -->
    <div v-else-if="trackerState === 'WAITING'" class="text-center">
      <Button
        v-if="canRecord"
        class="w-full !min-h-[3.5rem]"
        :label="t('match.set.start_first_set')"
        icon="pi pi-play"
        @click="onStartFirstSet"
      />
      <p v-else class="text-sm text-surface-400">{{ t('match.live.read_only_notice') }}</p>
    </div>

    <!-- 進行中セット入力 -->
    <template v-else-if="currentSet && !isCompleted">
      <!-- セットヘッダー + デュースバッジ -->
      <div class="mb-3 flex items-center justify-between">
        <h3 class="font-semibold">{{ setLabel(currentSetNumber ?? 1) }}</h3>
        <div class="flex items-center gap-2">
          <span
            v-if="isDeuce"
            class="rounded-full bg-warn-100 px-2 py-0.5 text-xs font-bold text-warn-700"
          >
            {{ t('match.set.deuce') }}
          </span>
          <span class="text-xs text-surface-400">
            {{ t('match.set.target_points', { n: targetLabel }) }}
          </span>
        </div>
      </div>

      <!-- 主動線: ステッパー入力 -->
      <div v-if="canRecord" class="mb-4 grid grid-cols-2 gap-4">
        <!-- ホーム -->
        <div class="flex flex-col items-center gap-2">
          <span class="text-sm font-medium text-surface-600">{{ homeLabel }}</span>
          <InputNumber
            v-model="homePointsModel"
            show-buttons
            button-layout="vertical"
            :min="0"
            :max="99"
            :input-style="{ width: '4rem', textAlign: 'center', fontSize: '1.5rem', fontWeight: '700' }"
            :aria-label="t('match.set.home_points_label')"
          />
          <!-- 副動線: +1/-1 ボタン（詳細記録モード・小型） -->
          <div class="flex gap-1">
            <Button
              size="small"
              severity="secondary"
              outlined
              icon="pi pi-minus"
              :aria-label="t('match.set.decrement_home')"
              @click="decrementHome"
            />
            <Button
              size="small"
              severity="success"
              icon="pi pi-plus"
              :aria-label="t('match.set.increment_home')"
              @click="incrementHome"
            />
          </div>
        </div>

        <!-- アウェイ -->
        <div class="flex flex-col items-center gap-2">
          <span class="text-sm font-medium text-surface-600">{{ awayLabel }}</span>
          <InputNumber
            v-model="awayPointsModel"
            show-buttons
            button-layout="vertical"
            :min="0"
            :max="99"
            :input-style="{ width: '4rem', textAlign: 'center', fontSize: '1.5rem', fontWeight: '700' }"
            :aria-label="t('match.set.away_points_label')"
          />
          <div class="flex gap-1">
            <Button
              size="small"
              severity="secondary"
              outlined
              icon="pi pi-minus"
              :aria-label="t('match.set.decrement_away')"
              @click="decrementAway"
            />
            <Button
              size="small"
              severity="success"
              icon="pi pi-plus"
              :aria-label="t('match.set.increment_away')"
              @click="incrementAway"
            />
          </div>
        </div>
      </div>

      <!-- 閲覧専用スコア表示 -->
      <div v-else class="mb-4 flex items-center justify-center gap-6 text-3xl font-black">
        <span class="text-primary">{{ currentSet.homePoints }}</span>
        <span class="text-surface-400">-</span>
        <span class="text-primary">{{ currentSet.awayPoints }}</span>
      </div>

      <!-- セット確定ボタン -->
      <Button
        v-if="canRecord"
        class="w-full"
        :disabled="!canConfirmSet"
        :label="t('match.set.confirm_set')"
        icon="pi pi-check"
        severity="success"
        @click="onConfirmSet"
      />
      <p v-if="canRecord && !canConfirmSet" class="mt-1 text-center text-xs text-surface-400">
        {{ isDeuce ? t('match.set.deuce_hint') : t('match.set.confirm_hint', { n: targetLabel }) }}
      </p>
    </template>

    <!-- 確定済みセット一覧 -->
    <div v-if="sets.length > 0" class="mt-4">
      <p class="mb-2 text-xs font-medium text-surface-500">{{ t('match.set.past_sets') }}</p>
      <div class="flex flex-col gap-1">
        <div
          v-for="s in sets"
          :key="s.setNumber"
          class="flex items-center justify-between rounded px-2 py-1"
          :class="s.confirmed ? 'bg-surface-50' : 'bg-primary-50'"
        >
          <span class="text-xs text-surface-500">{{ setLabel(s.setNumber) }}</span>
          <span class="font-mono font-bold">{{ s.homePoints }} - {{ s.awayPoints }}</span>
          <span class="text-xs">
            <template v-if="s.confirmed">
              <i class="pi pi-check text-success-500" />
              {{ s.winnerSide === 'HOME' ? homeLabel : awayLabel }}
            </template>
            <span v-else class="text-primary">{{ t('match.set.in_progress') }}</span>
          </span>
        </div>
      </div>
    </div>
  </div>
</template>
