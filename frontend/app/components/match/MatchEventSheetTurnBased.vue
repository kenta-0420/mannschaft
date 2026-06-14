<script setup lang="ts">
/**
 * F08.10 ターン制（将棋/囲碁）対局結果入力シート（sports/05_shogi.md §8.1 / sports/06_go.md §8.1）。
 *
 * ## 設計方針
 * - **タイマー無し・タイムラインなし**（球技 UI の流用なし）
 * - **必須は勝者選択のみ**（勝ち方・手数・写真・コメントは任意）
 * - **ADHD 配慮（入力摩擦ゼロ）**: 最小ステップで記録開始可能
 * - 勝者: HOME（先手/黒）/ AWAY（後手/白）/ 引分け（千日手/持将棋/持碁）
 * - 勝ち方: 競技別カタログ（将棋 7 種 / 囲碁 5 種）から選択（任意）
 * - 総手数: 任意入力
 * - 目数差（囲碁のみ）: POINTS_WIN 時に任意入力
 * - 局面写真: presign 方式（既存添付基盤流用）
 * - 結果確定後（COMPLETED）は編集不可（閲覧表示）
 */
import type {
  MatchTurnTrackerReturn,
  ShogiWinMethod,
  GoWinMethod,
} from '~/composables/match/sport/useMatchTurnTracker'

const props = defineProps<{
  /** 親（live.vue）から渡されたターン制トラッカー（TURN_BASED モジュールのもの）。 */
  tracker: MatchTurnTrackerReturn
  /** 自チームのサイド（ホーム/アウェイ判定に使う）。 */
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
  winnerSide,
  winMethod,
  totalMoves,
  margin,
  comment,
  drawSelected,
  isCompleted,
  isDraw,
  canComplete,
  isGo,
  start,
  complete,
  selectHomeWin,
  selectAwayWin,
  selectDraw,
  setWinMethod,
  setTotalMoves,
  setMargin,
  setComment,
} = props.tracker

/** 先手/後手（HOME=先手・黒、AWAY=後手・白）のラベル。 */
const homeSideLabel = computed(() =>
  props.tracker.sport === 'GO'
    ? t('match.turn.side.home_go') // 黒
    : t('match.turn.side.home_shogi'), // 先手
)
const awaySideLabel = computed(() =>
  props.tracker.sport === 'GO'
    ? t('match.turn.side.away_go') // 白
    : t('match.turn.side.away_shogi'), // 後手
)

/** 自チームのサイドラベル（ownTeamSide に基づく）。 */
const ownSideLabel = computed(() =>
  props.ownTeamSide === 'HOME' ? homeSideLabel.value : awaySideLabel.value,
)
/** 相手チームのサイドラベル。 */
const opponentSideLabel = computed(() =>
  props.ownTeamSide === 'HOME' ? awaySideLabel.value : homeSideLabel.value,
)

// ===== 勝ち方オプション（競技別カタログ） =====

/** 将棋の勝ち方オプション（§4.1）。 */
const SHOGI_WIN_METHODS: ReadonlyArray<{ value: ShogiWinMethod; labelKey: string }> = [
  { value: 'RESIGNATION', labelKey: 'match.win_method.RESIGNATION' },
  { value: 'CHECKMATE', labelKey: 'match.win_method.CHECKMATE' },
  { value: 'TIMEOUT', labelKey: 'match.win_method.TIMEOUT' },
  { value: 'FOUL_WIN', labelKey: 'match.win_method.FOUL_WIN' },
  { value: 'REPETITION', labelKey: 'match.win_method.REPETITION' },
  { value: 'IMPASSE', labelKey: 'match.win_method.IMPASSE' },
  { value: 'DEFAULT_WIN', labelKey: 'match.win_method.DEFAULT_WIN' },
]

/** 囲碁の勝ち方オプション（§4.1）。 */
const GO_WIN_METHODS: ReadonlyArray<{ value: GoWinMethod; labelKey: string }> = [
  { value: 'RESIGNATION', labelKey: 'match.win_method.RESIGNATION' },
  { value: 'POINTS_WIN', labelKey: 'match.win_method.POINTS_WIN' },
  { value: 'TIMEOUT', labelKey: 'match.win_method.TIMEOUT' },
  { value: 'FOUL_WIN', labelKey: 'match.win_method.FOUL_WIN' },
  { value: 'DEFAULT_WIN', labelKey: 'match.win_method.DEFAULT_WIN' },
]

const winMethodOptions = computed<Array<{ value: ShogiWinMethod | GoWinMethod; labelKey: string }>>(() => {
  const opts = isGo.value ? GO_WIN_METHODS : SHOGI_WIN_METHODS
  return [...opts] as Array<{ value: ShogiWinMethod | GoWinMethod; labelKey: string }>
})

/** 囲碁の目数差入力が有効か（GO かつ POINTS_WIN 選択時）。 */
const marginInputVisible = computed(() => isGo.value && winMethod.value === 'POINTS_WIN')

// ===== ハンドラ =====

function onStart(): void {
  start()
}

function onSelectHomeWin(): void {
  selectHomeWin()
}

function onSelectAwayWin(): void {
  selectAwayWin()
}

function onSelectDraw(): void {
  selectDraw()
}

function onComplete(): void {
  if (!canComplete.value) return
  complete()
  emit('completeMatch')
}

function onWinMethodChange(value: string | null): void {
  if (value === null) {
    setWinMethod(null)
    return
  }
  // 前向きユニオン境界: win_method は TurnWinMethod の文字列値
  // BE openapi 再生成後に型を厳密化する
  setWinMethod(value as ShogiWinMethod | GoWinMethod)
}

function onTotalMovesChange(value: number | null): void {
  setTotalMoves(value)
}

function onMarginChange(value: number | null): void {
  setMargin(value)
}

function onCommentChange(value: string): void {
  setComment(value)
}

/** 勝者ボタンの選択状態（ボタン強調表示）。 */
const homeSelected = computed(() => winnerSide.value === 'HOME')
const awaySelected = computed(() => winnerSide.value === 'AWAY')
const drawStateSelected = computed(() => drawSelected.value)

/** 勝者の表示ラベル（COMPLETED 後の表示用）。 */
const winnerLabel = computed(() => {
  if (isDraw.value) return t('match.turn.result.draw')
  if (winnerSide.value === 'HOME') return homeSideLabel.value
  if (winnerSide.value === 'AWAY') return awaySideLabel.value
  return ''
})

/** 勝ち方の表示ラベル（COMPLETED 後の表示用）。 */
const winMethodLabel = computed(() => {
  if (!winMethod.value) return null
  return t(`match.win_method.${winMethod.value}`)
})
</script>

<template>
  <div class="rounded-lg border border-surface-200 bg-white p-4">
    <!-- 対局結果表示（COMPLETED） -->
    <div v-if="isCompleted" class="rounded-lg bg-primary-50 p-4 text-center">
      <i class="pi pi-trophy mb-2 text-2xl text-primary" />
      <p class="mb-1 text-lg font-bold text-primary">{{ winnerLabel }}</p>
      <p v-if="winMethodLabel" class="mb-1 text-sm text-surface-600">
        {{ winMethodLabel }}
      </p>
      <p v-if="totalMoves" class="text-xs text-surface-500">
        {{ t('match.turn.total_moves', { n: totalMoves }) }}
      </p>
      <p v-if="isGo && margin != null" class="text-xs text-surface-500">
        {{ t('match.turn.margin', { n: margin }) }}
      </p>
      <p v-if="comment" class="mt-2 text-xs text-surface-500 italic">{{ comment }}</p>
    </div>

    <!-- 対局前（WAITING） -->
    <div v-else-if="trackerState === 'WAITING'" class="text-center">
      <p class="mb-3 text-sm text-surface-500">{{ t('match.turn.waiting_notice') }}</p>
      <Button
        v-if="canRecord"
        class="w-full !min-h-[3.5rem]"
        :label="t('match.turn.start_game')"
        icon="pi pi-play"
        @click="onStart"
      />
      <p v-else class="text-sm text-surface-400">{{ t('match.live.read_only_notice') }}</p>
    </div>

    <!-- 対局中（IN_PROGRESS）: 結果入力 -->
    <template v-else-if="trackerState === 'IN_PROGRESS'">
      <!-- 閲覧専用 -->
      <p v-if="!canRecord" class="text-sm text-surface-400">
        {{ t('match.live.read_only_notice') }}
      </p>

      <template v-else>
        <!-- 勝者選択（必須）-->
        <div class="mb-4">
          <p class="mb-2 text-sm font-medium text-surface-700">
            {{ t('match.turn.select_winner') }}
            <span class="ml-1 text-xs font-normal text-danger">{{ t('match.turn.required') }}</span>
          </p>
          <div class="grid grid-cols-3 gap-2">
            <!-- HOME 勝ち（先手/黒） -->
            <Button
              :severity="homeSelected ? 'primary' : 'secondary'"
              :outlined="!homeSelected"
              class="flex-col !min-h-[3.5rem] text-xs"
              :aria-pressed="homeSelected"
              @click="onSelectHomeWin"
            >
              <span class="text-sm font-bold">{{ ownTeamSide === 'HOME' ? ownSideLabel : opponentSideLabel }}</span>
              <span class="text-xs opacity-70">{{ t('match.turn.win') }}</span>
            </Button>

            <!-- AWAY 勝ち（後手/白） -->
            <Button
              :severity="awaySelected ? 'primary' : 'secondary'"
              :outlined="!awaySelected"
              class="flex-col !min-h-[3.5rem] text-xs"
              :aria-pressed="awaySelected"
              @click="onSelectAwayWin"
            >
              <span class="text-sm font-bold">{{ ownTeamSide === 'AWAY' ? ownSideLabel : opponentSideLabel }}</span>
              <span class="text-xs opacity-70">{{ t('match.turn.win') }}</span>
            </Button>

            <!-- 引分け -->
            <Button
              :severity="drawStateSelected ? 'warn' : 'secondary'"
              :outlined="!drawStateSelected"
              class="flex-col !min-h-[3.5rem] text-xs"
              :aria-pressed="drawStateSelected"
              @click="onSelectDraw"
            >
              <span class="text-sm font-bold">{{ t('match.turn.result.draw') }}</span>
              <span class="text-xs opacity-70">
                {{ isGo ? t('match.turn.draw_type_go') : t('match.turn.draw_type_shogi') }}
              </span>
            </Button>
          </div>
        </div>

        <!-- 勝ち方（任意） -->
        <div class="mb-3">
          <p class="mb-1 text-sm text-surface-600">
            {{ t('match.turn.win_method_label') }}
            <span class="ml-1 text-xs text-surface-400">{{ t('match.turn.optional') }}</span>
          </p>
          <Select
            :model-value="winMethod"
            :options="winMethodOptions"
            option-label="labelKey"
            option-value="value"
            :placeholder="t('match.turn.win_method_placeholder')"
            show-clear
            class="w-full"
            @update:model-value="onWinMethodChange"
          >
            <template #option="{ option }">
              {{ t(option.labelKey) }}
            </template>
            <template #value="{ value }">
              <span v-if="value">{{ t(`match.win_method.${value}`) }}</span>
            </template>
          </Select>
        </div>

        <!-- 目数差（囲碁 + POINTS_WIN 選択時のみ） -->
        <div v-if="marginInputVisible" class="mb-3">
          <p class="mb-1 text-sm text-surface-600">
            {{ t('match.turn.margin_label') }}
            <span class="ml-1 text-xs text-surface-400">{{ t('match.turn.optional') }}</span>
          </p>
          <InputNumber
            :model-value="margin"
            show-buttons
            :min="0.5"
            :max="999"
            :step="0.5"
            :use-grouping="false"
            :input-style="{ width: '6rem', textAlign: 'center' }"
            :aria-label="t('match.turn.margin_label')"
            @update:model-value="onMarginChange"
          />
          <span class="ml-2 text-xs text-surface-500">{{ t('match.turn.margin_unit') }}</span>
        </div>

        <!-- 総手数（任意） -->
        <div class="mb-3">
          <p class="mb-1 text-sm text-surface-600">
            {{ t('match.turn.total_moves_label') }}
            <span class="ml-1 text-xs text-surface-400">{{ t('match.turn.optional') }}</span>
          </p>
          <InputNumber
            :model-value="totalMoves"
            show-buttons
            :min="1"
            :max="9999"
            :input-style="{ width: '6rem', textAlign: 'center' }"
            :aria-label="t('match.turn.total_moves_label')"
            @update:model-value="onTotalMovesChange"
          />
          <span class="ml-2 text-xs text-surface-500">{{ t('match.turn.moves_unit') }}</span>
        </div>

        <!-- コメント（任意） -->
        <div class="mb-4">
          <p class="mb-1 text-sm text-surface-600">
            {{ t('match.turn.comment_label') }}
            <span class="ml-1 text-xs text-surface-400">{{ t('match.turn.optional') }}</span>
          </p>
          <Textarea
            :model-value="comment"
            :placeholder="t('match.turn.comment_placeholder')"
            rows="2"
            class="w-full"
            auto-resize
            @update:model-value="onCommentChange"
          />
        </div>

        <!-- 結果確定ボタン -->
        <Button
          class="w-full"
          :disabled="!canComplete"
          :label="t('match.turn.complete')"
          icon="pi pi-check"
          severity="success"
          @click="onComplete"
        />
        <p v-if="!canComplete" class="mt-1 text-center text-xs text-surface-400">
          {{ t('match.turn.complete_hint') }}
        </p>
      </template>
    </template>
  </div>
</template>
