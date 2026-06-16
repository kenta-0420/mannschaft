<script setup lang="ts">
/**
 * F08.10 採点競技（SCORED・フィギュア/体操）採点結果入力シート（sports/07_scored.md §8 / §9 / §11）。
 *
 * ## 設計方針
 * - **タイマー無し・セット無し・ターントラッカー無し・タイムラインなし**（採点競技は進行概念が薄い・§3）
 * - **2 つの入力モードを両立**（§8 の両立 UX）:
 *   - **直接入力（MVP）**: 自/相手の合計点（Total Segment Score / 個人総合）を 1 値ずつ入力。
 *     PUT /scored-result。入力摩擦最小（ADHD 配慮・§11）。
 *   - **内訳入力（後段・§4B）**: 審判別/種目別の項目別点数（フィギュア=TES/PCS/DEDUCTION・
 *     体操=D_SCORE/E_SCORE）を入力。PUT /scored-components（全置換）でサーバーが合計を再導出（二層正本）。
 * - **stale 整合（§4B.2 / §8）**: 内訳が 1 件でもあるときは内訳が正本。内訳が正本の間は直接入力を抑止し、
 *   「直接入力へ戻すには内訳をクリア」する導線を出す（直接入力 → 内訳の上書き混在を防ぐ）。
 *   逆に内訳入力へ切り替えると、内訳の集計（全置換）がサーバー合計を上書きする。
 * - 合計点は小数（フィギュア 198.45・体操 85.332）。整数スケール×1000 への変換は composable が吸収（§4.1）。
 * - 勝敗は合計点の大小で **BE が導出**する（FE は合計/内訳を送るだけ・§4.2）。同点は引分（BE 判定）。
 * - 結果確定後（COMPLETED）は編集不可（閲覧表示）。
 *
 * ## やらないこと（後段 Phase）
 * - 多人数順位制（match_score_entries・§5B）は BE 別経路ゆえ本シートでは扱わない（別波）。
 */
import type { MatchScoreEntryReturn } from '~/composables/match/useMatchScoreEntry'
import type { MatchScoredComponentsReturn } from '~/composables/match/useMatchScoredComponents'

const props = defineProps<{
  /** 合計点直接入力トラッカー（SCORED モジュールの createScoreEntry のもの）。 */
  tracker: MatchScoreEntryReturn
  /** 審判別/種目別採点内訳トラッカー（createComponentEntry のもの）。 */
  componentTracker: MatchScoredComponentsReturn
  /** 自チームのサイド（ホーム/アウェイ判定に使う）。 */
  ownTeamSide: 'HOME' | 'AWAY'
  /** 相手チーム名（表示用・nullable）。 */
  opponentName: string | null
  /** 記録権限があるか（false=閲覧専用・入力 UI を出さない）。 */
  canRecord: boolean
}>()

const emit = defineEmits<{
  /** 直接入力モードの採点確定（PUT /scored-result + COMPLETED）。 */
  completeMatch: []
  /** 内訳入力モードの採点確定（PUT /scored-components 全置換 + COMPLETED）。 */
  completeComponents: []
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

/** 内訳が 1 件以上あるか（内訳が正本の目印・stale 整合・§4B.2）。 */
const { hasComponents, clearLines } = props.componentTracker

/** 入力モード（'DIRECT'=合計直接入力 / 'COMPONENTS'=審判別内訳入力・§8）。 */
type ScoredInputMode = 'DIRECT' | 'COMPONENTS'
const inputMode = ref<ScoredInputMode>('DIRECT')

/** 内訳が正本のとき（hasComponents）は内訳モードを強制し、直接入力を抑止する（stale 整合・§4B.2）。 */
const isComponentsCanonical = computed<boolean>(() => hasComponents.value)

/** 内訳入力モードか（明示切替 or 内訳が正本）。 */
const isComponentsMode = computed<boolean>(
  () => inputMode.value === 'COMPONENTS' || isComponentsCanonical.value,
)

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

/** 自チーム側の合計点ラベル。 */
const ownScoreLabel = computed(() => t('match.scored.own_score'))
/** 相手側の合計点ラベル。 */
const opponentScoreLabel = computed(
  () => props.opponentName ?? t('match.scored.opponent_score'),
)

// ===== ハンドラ =====

function onStart(): void {
  start()
}

/** 入力モードを切り替える（内訳が正本のときは内訳固定で切替不可）。 */
function switchToComponents(): void {
  inputMode.value = 'COMPONENTS'
}
function switchToDirect(): void {
  // 内訳が正本（hasComponents）の間は直接入力に戻せない。内訳クリアの導線を別途出す。
  if (isComponentsCanonical.value) return
  inputMode.value = 'DIRECT'
}

/** 内訳をクリアして直接入力へ戻す（stale 整合の導線・§4B.2）。 */
function onClearComponents(): void {
  clearLines()
  inputMode.value = 'DIRECT'
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

function onCompleteComponents(): void {
  emit('completeComponents')
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
    <!-- 採点結果表示（COMPLETED・直接入力モードのスコア表示） -->
    <div v-if="isCompleted && !isComponentsMode" class="rounded-lg bg-primary-50 p-4 text-center">
      <i class="pi pi-star mb-2 text-2xl text-primary" />
      <p class="mb-1 text-sm text-surface-600">{{ sportLabel }}</p>
      <p class="mb-1 text-lg font-bold text-primary">
        {{ ownScore ?? '-' }} - {{ opponentScore ?? '-' }}
      </p>
      <p class="text-sm text-surface-600">{{ resultLabel }}</p>
    </div>

    <!-- 採点前（WAITING・直接入力モードのみ・内訳は開始ボタン不要で直接行追加） -->
    <div v-else-if="entryState === 'WAITING' && !isComponentsMode" class="text-center">
      <p class="mb-1 text-sm font-medium text-surface-700">{{ sportLabel }}</p>
      <p class="mb-3 text-sm text-surface-500">{{ t('match.scored.waiting_notice') }}</p>
      <template v-if="canRecord">
        <Button
          class="mb-2 w-full !min-h-[3.5rem]"
          :label="t('match.scored.start')"
          icon="pi pi-pencil"
          @click="onStart"
        />
        <!-- 内訳入力モードへの切替（審判別/種目別を残したいとき・§8） -->
        <Button
          class="w-full"
          :label="t('match.scored.components.switch_to_components')"
          icon="pi pi-list"
          severity="secondary"
          text
          @click="switchToComponents"
        />
      </template>
      <p v-else class="text-sm text-surface-400">{{ t('match.live.read_only_notice') }}</p>
    </div>

    <!-- 内訳入力モード（§4B・MatchScoredComponentSheet を内包） -->
    <template v-else-if="isComponentsMode">
      <!-- モード説明＋直接入力へ戻す導線 -->
      <div class="mb-3 flex items-center justify-between">
        <p class="text-sm font-medium text-surface-700">
          {{ t('match.scored.components.mode_label') }}
        </p>
        <Button
          v-if="canRecord && !isComponentsCanonical && !isCompleted"
          :label="t('match.scored.components.switch_to_direct')"
          icon="pi pi-arrow-left"
          severity="secondary"
          text
          size="small"
          @click="switchToDirect"
        />
      </div>

      <!-- 内訳が正本のときの直接入力抑止＋クリア導線（stale 整合・§4B.2） -->
      <div
        v-if="canRecord && isComponentsCanonical && !isCompleted"
        class="mb-3 rounded-md bg-surface-50 p-2 text-center"
      >
        <p class="mb-1 text-xs text-surface-500">{{ t('match.scored.components.canonical_notice') }}</p>
        <Button
          :label="t('match.scored.components.clear_to_direct')"
          icon="pi pi-times"
          severity="secondary"
          text
          size="small"
          @click="onClearComponents"
        />
      </div>

      <MatchScoredComponentSheet
        :tracker="componentTracker"
        :own-team-side="ownTeamSide"
        :opponent-name="opponentName"
        :can-record="canRecord"
        :completed="isCompleted"
        @complete-match="onCompleteComponents"
      />
    </template>

    <!-- 採点中（IN_PROGRESS・直接入力モード）: 合計点入力 -->
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

        <!-- 内訳入力モードへの切替 -->
        <Button
          class="mt-2 w-full"
          :label="t('match.scored.components.switch_to_components')"
          icon="pi pi-list"
          severity="secondary"
          text
          @click="switchToComponents"
        />
      </template>
    </template>
  </div>
</template>
