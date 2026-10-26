<script setup lang="ts">
/**
 * F08.10 採点競技（SCORED・フィギュア/体操）審判別/種目別<b>採点内訳入力シート</b>
 * （sports/07_scored.md §4B / §8 / §9）。
 *
 * ## 設計方針
 * - フィギュア（TES + PCS − DEDUCTION・SP/FS セグメント別）・体操（D_SCORE + E_SCORE・種目別）の
 *   項目別点数を home/away の side 別に入力する（§4B）。
 * - 全置換 PUT /scored-components で送り、サーバーが side 別に符号付き集計して合計点を再導出する
 *   （二層正本・§4B.2）。本シートは入力中の合計プレビューを表示する（正本はサーバー）。
 * - 項目（component_type）・種目（apparatus）は当該競技のカタログ列挙のみ選べる（BE 検証のミラー・§10）。
 * - DEDUCTION（減点）は絶対値（非負）で入力し、合計プレビューでは減算する（BE と同じ符号規約・§4B）。
 * - COMPLETED 後は編集不可（閲覧表示）。記録権限が無い場合は入力 UI を出さない。
 *
 * ## やらないこと（後段 Phase）
 * - 多人数順位制（match_score_entries・§5B）は本シートでは扱わない（別波・BE 別経路）。
 */
import type {
  MatchScoredComponentsReturn,
  ScoredComponentDraft,
  ScoredComponentSide,
} from '~/composables/match/useMatchScoredComponents'

const props = defineProps<{
  /** 採点内訳トラッカー（scoredModule の createComponentEntry のもの）。 */
  tracker: MatchScoredComponentsReturn
  /** 自チームのサイド（own/opponent 表示の割り当てに使う）。 */
  ownTeamSide: ScoredComponentSide
  /** 相手チーム名（表示用・nullable）。 */
  opponentName: string | null
  /** 記録権限があるか（false=閲覧専用・入力 UI を出さない）。 */
  canRecord: boolean
  /** 採点確定済み（COMPLETED）か（true=編集不可の閲覧表示）。 */
  completed: boolean
}>()

const emit = defineEmits<{
  /** 内訳記録（全置換 PUT）＋ COMPLETED 配線を親へ要求。 */
  completeMatch: []
}>()

const { t } = useI18n()

const {
  allowedComponentTypes,
  allowedApparatuses,
  drafts,
  isFigureSkating,
  homeTotal,
  awayTotal,
  canSubmit,
  saving,
  addLine,
  updateLine,
  removeLine,
} = props.tracker

/** 競技名ラベル（フィギュア/体操で出し分け・§2）。 */
const sportLabel = computed(() =>
  isFigureSkating.value
    ? t('match.scored.sport.figure_skating')
    : t('match.scored.sport.gymnastics'),
)

/** side 選択肢（HOME=自/相手は ownTeamSide に応じて表示）。 */
const sideOptions = computed<{ value: ScoredComponentSide; label: string }[]>(() => [
  {
    value: 'HOME',
    label:
      props.ownTeamSide === 'HOME'
        ? t('match.scored.own_score')
        : (props.opponentName ?? t('match.scored.opponent_score')),
  },
  {
    value: 'AWAY',
    label:
      props.ownTeamSide === 'AWAY'
        ? t('match.scored.own_score')
        : (props.opponentName ?? t('match.scored.opponent_score')),
  },
])

/** 項目（component_type）選択肢（i18n ラベル付き）。 */
const componentTypeOptions = computed(() =>
  allowedComponentTypes.value.map((value) => ({
    value,
    label: t(`match.scored.components.component_type.${value}`),
  })),
)

/** 種目/セグメント（apparatus）選択肢（任意・i18n ラベル付き）。 */
const apparatusOptions = computed(() =>
  allowedApparatuses.value.map((value) => ({
    value,
    label: t(`match.scored.components.apparatus.${value}`),
  })),
)

/** 自チーム合計（表示用・ownTeamSide に応じた値）。 */
const ownTotal = computed(() => (props.ownTeamSide === 'HOME' ? homeTotal.value : awayTotal.value))
/** 相手合計（表示用）。 */
const opponentTotal = computed(() =>
  props.ownTeamSide === 'HOME' ? awayTotal.value : homeTotal.value,
)
/** 自チーム合計ラベル。 */
const ownLabel = computed(() => t('match.scored.own_score'))
/** 相手合計ラベル。 */
const opponentLabel = computed(() => props.opponentName ?? t('match.scored.opponent_score'))

// ===== ハンドラ（不変更新・型安全） =====

function onAddLine(): void {
  addLine(props.ownTeamSide)
}

function onSideChange(line: ScoredComponentDraft, value: ScoredComponentSide): void {
  updateLine(line.key, { side: value })
}

function onComponentTypeChange(
  line: ScoredComponentDraft,
  value: ScoredComponentDraft['componentType'],
): void {
  updateLine(line.key, { componentType: value })
}

function onApparatusChange(
  line: ScoredComponentDraft,
  value: ScoredComponentDraft['apparatus'],
): void {
  updateLine(line.key, { apparatus: value ?? null })
}

function onJudgeLabelChange(line: ScoredComponentDraft, value: string): void {
  updateLine(line.key, { judgeLabel: value === '' ? null : value })
}

function onPointsChange(line: ScoredComponentDraft, value: number | null): void {
  updateLine(line.key, { points: value })
}

function onRemove(line: ScoredComponentDraft): void {
  removeLine(line.key)
}

function onComplete(): void {
  if (!canSubmit.value) return
  emit('completeMatch')
}
</script>

<template>
  <div class="rounded-lg border border-surface-200 bg-white p-4">
    <p class="mb-1 text-sm font-medium text-surface-700">{{ sportLabel }}</p>
    <p class="mb-3 text-xs text-surface-500">{{ t('match.scored.components.intro') }}</p>

    <!-- 閲覧専用 -->
    <p v-if="!canRecord" class="text-sm text-surface-400">
      {{ t('match.live.read_only_notice') }}
    </p>

    <template v-else>
      <!-- 内訳行リスト -->
      <ul class="mb-3 flex flex-col gap-3">
        <li
          v-for="line in drafts"
          :key="line.key"
          class="rounded-md border border-surface-100 bg-surface-50 p-3"
        >
          <div class="mb-2 grid grid-cols-2 gap-2">
            <!-- side -->
            <div>
              <label class="mb-1 block text-xs text-surface-500">
                {{ t('match.scored.components.side_label') }}
              </label>
              <Select
                :model-value="line.side"
                :options="sideOptions"
                option-label="label"
                option-value="value"
                :disabled="completed"
                class="w-full"
                :aria-label="t('match.scored.components.side_label')"
                @update:model-value="onSideChange(line, $event)"
              />
            </div>
            <!-- component_type -->
            <div>
              <label class="mb-1 block text-xs text-surface-500">
                {{ t('match.scored.components.component_type_label') }}
              </label>
              <Select
                :model-value="line.componentType"
                :options="componentTypeOptions"
                option-label="label"
                option-value="value"
                :disabled="completed"
                class="w-full"
                :aria-label="t('match.scored.components.component_type_label')"
                @update:model-value="onComponentTypeChange(line, $event)"
              />
            </div>
          </div>

          <div class="mb-2 grid grid-cols-2 gap-2">
            <!-- apparatus（任意） -->
            <div>
              <label class="mb-1 block text-xs text-surface-500">
                {{ t('match.scored.components.apparatus_label') }}
                <span class="text-surface-400">{{ t('match.scored.components.optional') }}</span>
              </label>
              <Select
                :model-value="line.apparatus"
                :options="apparatusOptions"
                option-label="label"
                option-value="value"
                show-clear
                :disabled="completed"
                :placeholder="t('match.scored.components.apparatus_placeholder')"
                class="w-full"
                :aria-label="t('match.scored.components.apparatus_label')"
                @update:model-value="onApparatusChange(line, $event)"
              />
            </div>
            <!-- judge_label（任意） -->
            <div>
              <label class="mb-1 block text-xs text-surface-500">
                {{ t('match.scored.components.judge_label') }}
                <span class="text-surface-400">{{ t('match.scored.components.optional') }}</span>
              </label>
              <InputText
                :model-value="line.judgeLabel ?? ''"
                :disabled="completed"
                :maxlength="32"
                :placeholder="t('match.scored.components.judge_placeholder')"
                class="w-full"
                :aria-label="t('match.scored.components.judge_label')"
                @update:model-value="onJudgeLabelChange(line, $event ?? '')"
              />
            </div>
          </div>

          <div class="flex items-end justify-between gap-2">
            <!-- points -->
            <div>
              <label class="mb-1 block text-xs text-surface-500">
                {{ t('match.scored.components.points_label') }}
              </label>
              <InputNumber
                :model-value="line.points"
                :min="0"
                :max="999.999"
                :min-fraction-digits="0"
                :max-fraction-digits="3"
                :use-grouping="false"
                :disabled="completed"
                :placeholder="t('match.scored.components.points_placeholder')"
                :input-style="{ width: '7rem', textAlign: 'center' }"
                :aria-label="t('match.scored.components.points_label')"
                @update:model-value="onPointsChange(line, $event)"
              />
            </div>
            <Button
              v-if="!completed"
              icon="pi pi-trash"
              severity="danger"
              text
              :aria-label="t('match.scored.components.remove_line')"
              @click="onRemove(line)"
            />
          </div>
        </li>
      </ul>

      <!-- 行追加 -->
      <Button
        v-if="!completed"
        class="mb-4 w-full"
        :label="t('match.scored.components.add_line')"
        icon="pi pi-plus"
        severity="secondary"
        outlined
        @click="onAddLine"
      />

      <!-- 合計プレビュー（サーバーが正本・FE は即時フィードバック） -->
      <div class="mb-4 rounded-md bg-primary-50 p-3 text-center">
        <p class="mb-1 text-xs text-surface-500">{{ t('match.scored.components.total_preview') }}</p>
        <p class="text-lg font-bold text-primary">
          <span>{{ ownLabel }}: {{ ownTotal.toFixed(3) }}</span>
          <span class="mx-2 text-surface-400">/</span>
          <span>{{ opponentLabel }}: {{ opponentTotal.toFixed(3) }}</span>
        </p>
      </div>

      <!-- 結果確定 -->
      <Button
        v-if="!completed"
        class="w-full"
        :disabled="!canSubmit"
        :loading="saving"
        :label="t('match.scored.components.complete')"
        icon="pi pi-check"
        severity="success"
        @click="onComplete"
      />
      <p v-if="!completed && !canSubmit" class="mt-1 text-center text-xs text-surface-400">
        {{ t('match.scored.components.complete_hint') }}
      </p>
    </template>
  </div>
</template>
