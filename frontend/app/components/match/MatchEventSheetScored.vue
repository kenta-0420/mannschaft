<script setup lang="ts">
/**
 * F08.10 採点競技（SCORED・フィギュア/体操）採点結果入力シート（sports/07_scored.md §9 / §11）。
 *
 * ## 設計方針
 * - **タイマー無し・セット無し・ターントラッカー無し・タイムラインなし**（採点競技は進行概念が薄い・§3）
 * - **必須は合計点のみ**（コメント等の付随入力は MVP では持たない・合計点 1 値に還元・§4）
 * - **ADHD 配慮（入力摩擦ゼロ）**: 「採点を記録」→ 自/相手の合計点入力 → 確定の最小ステップ（§11）
 * - 合計点は小数（フィギュア 198.45・体操 85.332）。整数スケール×1000 への変換は composable が吸収
 *   （198.45 を入力 → 内部 198450・§4.1）。UI は小数を表示する。
 * - 勝敗は合計点の大小で **BE が導出**する（FE は両合計点を送るだけ・§4.2）。同点は引分（BE 判定）。
 * - フィギュア＝Total Segment Score・体操＝個人総合スコア。MVP の入力 UI は両競技共通（合計点 1 値・§9）。
 * - 結果確定後（COMPLETED）は編集不可（閲覧表示）。
 *
 * ## やらないこと（後段 Phase）
 * - 審判別内訳（TES/PCS・D/E・種目別＝§4B）・多人数順位制（§5B）は BE 未実装ゆえ本シートでは扱わない。
 */
import type { MatchScoreEntryReturn } from '~/composables/match/useMatchScoreEntry'

const props = defineProps<{
  /** 親（live.vue）から渡された採点入力トラッカー（SCORED モジュールのもの）。 */
  tracker: MatchScoreEntryReturn
  /** 自チームのサイド（ホーム/アウェイ判定に使う）。 */
  ownTeamSide: 'HOME' | 'AWAY'
  /** 相手チーム名（表示用・nullable）。 */
  opponentName: string | null
  /** 記録権限があるか（false=閲覧専用・入力 UI を出さない）。 */
  canRecord: boolean
}>()

const emit = defineEmits<{
  /** 採点確定を親へ通知（submit + status COMPLETED 配線要求）。 */
  completeMatch: []
}>()

const { t } = useI18n()

const {
  entryState,
  homeScore,
  awayScore,
  isCompleted,
  isFigureSkating,
  canSubmit,
  start,
  setHomeScore,
  setAwayScore,
} = props.tracker

/** 競技名ラベル（フィギュア/体操で表示を出し分け・§2）。 */
const sportLabel = computed(() =>
  isFigureSkating.value
    ? t('match.scored.sport.figure_skating')
    : t('match.scored.sport.gymnastics'),
)

/** 合計点の項目ラベル（フィギュア=Total Segment Score・体操=個人総合・§2.1）。 */
const totalScoreLabel = computed(() =>
  isFigureSkating.value
    ? t('match.scored.total_label_figure')
    : t('match.scored.total_label_gymnastics'),
)

/** 自チーム側の合計点（ownTeamSide に応じて home/away を割り当て）。 */
const ownScoreLabel = computed(() => t('match.scored.own_score'))
/** 相手側の合計点ラベル。 */
const opponentScoreLabel = computed(
  () => props.opponentName ?? t('match.scored.opponent_score'),
)

// ===== ハンドラ =====

function onStart(): void {
  start()
}

/** 自チーム合計点の入力ハンドラ（ownTeamSide に応じて home/away へ振り分け）。 */
function onOwnScoreChange(value: number | null): void {
  if (props.ownTeamSide === 'HOME') setHomeScore(value)
  else setAwayScore(value)
}

/** 相手合計点の入力ハンドラ。 */
function onOpponentScoreChange(value: number | null): void {
  if (props.ownTeamSide === 'HOME') setAwayScore(value)
  else setHomeScore(value)
}

function onComplete(): void {
  if (!canSubmit.value) return
  emit('completeMatch')
}

/** 自チームの合計点（表示用・ownTeamSide に応じた値）。 */
const ownScore = computed(() => (props.ownTeamSide === 'HOME' ? homeScore.value : awayScore.value))
/** 相手の合計点（表示用）。 */
const opponentScore = computed(() =>
  props.ownTeamSide === 'HOME' ? awayScore.value : homeScore.value,
)

/** 結果表示用: 勝者ラベル（合計点の大小・同点=引分）。 */
const resultLabel = computed(() => {
  const own = ownScore.value ?? 0
  const opp = opponentScore.value ?? 0
  if (own > opp) return ownScoreLabel.value
  if (opp > own) return opponentScoreLabel.value
  return t('match.scored.result.draw')
})
</script>

<template>
  <div class="rounded-lg border border-surface-200 bg-white p-4">
    <!-- 採点結果表示（COMPLETED） -->
    <div v-if="isCompleted" class="rounded-lg bg-primary-50 p-4 text-center">
      <i class="pi pi-star mb-2 text-2xl text-primary" />
      <p class="mb-1 text-sm text-surface-600">{{ sportLabel }}</p>
      <p class="mb-1 text-lg font-bold text-primary">
        {{ ownScore ?? '-' }} - {{ opponentScore ?? '-' }}
      </p>
      <p class="text-sm text-surface-600">{{ resultLabel }}</p>
    </div>

    <!-- 採点前（WAITING） -->
    <div v-else-if="entryState === 'WAITING'" class="text-center">
      <p class="mb-1 text-sm font-medium text-surface-700">{{ sportLabel }}</p>
      <p class="mb-3 text-sm text-surface-500">{{ t('match.scored.waiting_notice') }}</p>
      <Button
        v-if="canRecord"
        class="w-full !min-h-[3.5rem]"
        :label="t('match.scored.start')"
        icon="pi pi-pencil"
        @click="onStart"
      />
      <p v-else class="text-sm text-surface-400">{{ t('match.live.read_only_notice') }}</p>
    </div>

    <!-- 採点中（IN_PROGRESS）: 合計点入力 -->
    <template v-else-if="entryState === 'IN_PROGRESS'">
      <!-- 閲覧専用 -->
      <p v-if="!canRecord" class="text-sm text-surface-400">
        {{ t('match.live.read_only_notice') }}
      </p>

      <template v-else>
        <p class="mb-1 text-sm font-medium text-surface-700">{{ sportLabel }}</p>
        <p class="mb-3 text-xs text-surface-500">{{ totalScoreLabel }}</p>

        <!-- 自チームの合計点（必須） -->
        <div class="mb-3">
          <label class="mb-1 block text-sm text-surface-600">
            {{ ownScoreLabel }}
            <span class="ml-1 text-xs font-normal text-danger">{{ t('match.scored.required') }}</span>
          </label>
          <InputNumber
            :model-value="ownScore"
            :min="0"
            :max="999.999"
            :min-fraction-digits="0"
            :max-fraction-digits="3"
            :use-grouping="false"
            :placeholder="t('match.scored.score_placeholder')"
            :input-style="{ width: '8rem', textAlign: 'center' }"
            :aria-label="ownScoreLabel"
            @update:model-value="onOwnScoreChange"
          />
        </div>

        <!-- 相手の合計点（必須） -->
        <div class="mb-4">
          <label class="mb-1 block text-sm text-surface-600">
            {{ opponentScoreLabel }}
            <span class="ml-1 text-xs font-normal text-danger">{{ t('match.scored.required') }}</span>
          </label>
          <InputNumber
            :model-value="opponentScore"
            :min="0"
            :max="999.999"
            :min-fraction-digits="0"
            :max-fraction-digits="3"
            :use-grouping="false"
            :placeholder="t('match.scored.score_placeholder')"
            :input-style="{ width: '8rem', textAlign: 'center' }"
            :aria-label="opponentScoreLabel"
            @update:model-value="onOpponentScoreChange"
          />
        </div>

        <!-- 結果確定ボタン -->
        <Button
          class="w-full"
          :disabled="!canSubmit"
          :label="t('match.scored.complete')"
          icon="pi pi-check"
          severity="success"
          @click="onComplete"
        />
        <p v-if="!canSubmit" class="mt-1 text-center text-xs text-surface-400">
          {{ t('match.scored.complete_hint') }}
        </p>
      </template>
    </template>
  </div>
</template>
