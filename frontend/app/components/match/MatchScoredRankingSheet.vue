<script setup lang="ts">
/**
 * F08.10 採点競技（SCORED・フィギュア/体操）<b>多人数順位制の出場者エントリ入力シート</b>
 * （sports/07_scored.md §5/§5B/§8）。
 *
 * ## 設計方針（§5B / §8 の「一覧＋個別入力」UX）
 * - 1 つの match を「種目（イベント）」とし、N 人の出場者を行追加で入力する（出場者名＋合計点）。
 * - 全置換 PUT /score-entries で送ると、サーバーが合計点降順で順位を導出する（標準順位法・同点同順位）。
 * - <b>順位はサーバー算出</b>。FE は順位を送らず、受信した順位（rankPosition）を順位表として表示する。
 * - 合計点は小数（フィギュア 198.45・体操 85.332）。整数スケール×1000 への変換は composable が吸収（§4.1）。
 * - COMPLETED 後は編集不可（順位表の閲覧表示）。記録権限が無い場合は入力 UI を出さない。
 *
 * ## やらないこと
 * - 2 者対戦の合計直接入力（MatchEventSheetScored）・審判別内訳（MatchScoredComponentSheet）は別シート。
 */
import type {
  MatchScoreEntriesReturn,
  ScoreEntryDraft,
} from '~/composables/match/useMatchScoreEntries'
import { resolveCompetitorDisplayName } from '~/composables/match/useMatchScoreEntries'

const props = defineProps<{
  /** 多人数順位制トラッカー（scoredModule の createRankingEntry のもの）。 */
  tracker: MatchScoreEntriesReturn
  /** 記録権限があるか（false=閲覧専用・入力 UI を出さない）。 */
  canRecord: boolean
  /** 採点確定済み（COMPLETED）か（true=編集不可の順位表閲覧）。 */
  completed: boolean
}>()

const emit = defineEmits<{
  /** エントリ記録（全置換 PUT）＋ COMPLETED 配線を親へ要求。 */
  completeMatch: []
}>()

const { t } = useI18n()

const {
  drafts,
  ranked,
  isFigureSkating,
  canSubmit,
  saving,
  addEntry,
  updateEntry,
  removeEntry,
} = props.tracker

/** 競技名ラベル（フィギュア/体操で出し分け・§2）。 */
const sportLabel = computed(() =>
  isFigureSkating.value
    ? t('match.scored.sport.figure_skating')
    : t('match.scored.sport.gymnastics'),
)

/** 順位表の表示行（サーバー算出の rankPosition 昇順・表示名解決）。 */
const rankedRows = computed(() =>
  ranked.value.map((e) => ({
    id: e.id ?? `${e.competitorUserId ?? ''}-${e.competitorName ?? ''}`,
    rank: e.rankPosition ?? null,
    name: resolveCompetitorDisplayName(
      e,
      (uid) => t('match.scored.ranking.user_fallback', { id: uid }),
      (tid) => t('match.scored.ranking.team_fallback', { id: tid }),
    ),
    total: e.totalScaled !== undefined ? e.totalScaled / 1000 : null,
  })),
)

// ===== ハンドラ（不変更新・型安全） =====

function onAddEntry(): void {
  addEntry()
}

function onNameChange(line: ScoreEntryDraft, value: string): void {
  updateEntry(line.key, { competitorName: value === '' ? null : value })
}

function onTotalChange(line: ScoreEntryDraft, value: number | null): void {
  updateEntry(line.key, { total: value })
}

function onRemove(line: ScoreEntryDraft): void {
  removeEntry(line.key)
}

function onComplete(): void {
  if (!canSubmit.value) return
  emit('completeMatch')
}
</script>

<template>
  <div class="rounded-lg border border-surface-200 bg-white p-4">
    <p class="mb-1 text-sm font-medium text-surface-700">{{ sportLabel }}</p>
    <p class="mb-3 text-xs text-surface-500">{{ t('match.scored.ranking.intro') }}</p>

    <!-- 順位表（サーバー算出の rankPosition・記録/閲覧いずれも表示） -->
    <div v-if="rankedRows.length > 0" class="mb-4 rounded-md bg-primary-50 p-3">
      <p class="mb-2 text-xs font-medium text-surface-600">{{ t('match.scored.ranking.standings') }}</p>
      <ol class="flex flex-col gap-1">
        <li
          v-for="row in rankedRows"
          :key="row.id"
          class="flex items-center justify-between rounded bg-white px-2 py-1 text-sm"
        >
          <span class="flex items-center gap-2">
            <span class="inline-block min-w-[2rem] text-center font-bold text-primary">
              {{ row.rank ?? '-' }}
            </span>
            <span class="text-surface-700">{{ row.name || '-' }}</span>
          </span>
          <span class="font-mono text-surface-600">{{ row.total !== null ? row.total.toFixed(3) : '-' }}</span>
        </li>
      </ol>
    </div>

    <!-- 閲覧専用 -->
    <p v-if="!canRecord" class="text-sm text-surface-400">
      {{ t('match.live.read_only_notice') }}
    </p>

    <template v-else-if="!completed">
      <!-- 出場者行リスト（名前＋合計点） -->
      <ul class="mb-3 flex flex-col gap-3">
        <li
          v-for="line in drafts"
          :key="line.key"
          class="rounded-md border border-surface-100 bg-surface-50 p-3"
        >
          <div class="flex items-end justify-between gap-2">
            <!-- 出場者名 -->
            <div class="flex-1">
              <label class="mb-1 block text-xs text-surface-500">
                {{ t('match.scored.ranking.competitor_label') }}
              </label>
              <InputText
                :model-value="line.competitorName ?? ''"
                :maxlength="128"
                :placeholder="t('match.scored.ranking.competitor_placeholder')"
                class="w-full"
                :aria-label="t('match.scored.ranking.competitor_label')"
                @update:model-value="onNameChange(line, $event ?? '')"
              />
            </div>
            <!-- 合計点 -->
            <div>
              <label class="mb-1 block text-xs text-surface-500">
                {{ t('match.scored.ranking.total_label') }}
              </label>
              <InputNumber
                :model-value="line.total"
                :min="0"
                :max="999.999"
                :min-fraction-digits="0"
                :max-fraction-digits="3"
                :use-grouping="false"
                :placeholder="t('match.scored.score_placeholder')"
                :input-style="{ width: '7rem', textAlign: 'center' }"
                :aria-label="t('match.scored.ranking.total_label')"
                @update:model-value="onTotalChange(line, $event)"
              />
            </div>
            <Button
              icon="pi pi-trash"
              severity="danger"
              text
              :aria-label="t('match.scored.ranking.remove_entry')"
              @click="onRemove(line)"
            />
          </div>
        </li>
      </ul>

      <!-- 出場者追加 -->
      <Button
        class="mb-4 w-full"
        :label="t('match.scored.ranking.add_entry')"
        icon="pi pi-plus"
        severity="secondary"
        outlined
        @click="onAddEntry"
      />

      <!-- 結果確定（順位はサーバーが算出） -->
      <Button
        class="w-full"
        :disabled="!canSubmit"
        :loading="saving"
        :label="t('match.scored.ranking.complete')"
        icon="pi pi-check"
        severity="success"
        @click="onComplete"
      />
      <p v-if="!canSubmit" class="mt-1 text-center text-xs text-surface-400">
        {{ t('match.scored.ranking.complete_hint') }}
      </p>
    </template>
  </div>
</template>
