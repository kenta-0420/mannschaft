<script setup lang="ts">
/**
 * F08.10 団体戦ボード進捗一覧コンポーネント（04_frontend_and_ux.md §G.16a・sports/05_shogi.md §4.3）。
 *
 * ## 役割
 * - 各ボードの確定状況を一覧表示（n/N）
 * - 未入力ボードを強調表示してとりこぼしを防ぐ（§G.16a）
 * - 記録権限があるユーザーに「記録する」ボタンを表示
 * - 確定済みボードは勝者・勝ち方を表示
 * - 全ボード確定時に親 match COMPLETED 可能を通知
 *
 * ## 入力
 * - boards: 各ボードの進捗情報（BE API レスポンス OR ローカル状態）
 * - canRecord: 記録権限フラグ（role 判定済み）
 */
import type { TurnWinnerSide } from '~/composables/match/sport/useMatchTurnTracker'

/** ボード 1 件の進捗情報。 */
export interface BoardProgressItem {
  /** ボード番号（1-N）。 */
  boardNumber: number
  /** 子 match ID（なければ null=未作成）。 */
  matchId: string | null
  /** 確定済みか（COMPLETED 遷移済み）。 */
  confirmed: boolean
  /** 勝者サイド（confirmed 時のみ非 null・引分け=null）。 */
  winnerSide: TurnWinnerSide
  /** 勝ち方（任意・confirmed 時）。 */
  winMethod: string | null
  /** 先手/黒（HOME side）の選手名（表示用）。 */
  homePlayerName: string | null
  /** 後手/白（AWAY side）の選手名（表示用）。 */
  awayPlayerName: string | null
  /** 記録担当者の名前（null=未割り当て）。 */
  recorderName: string | null
  /** 自分が記録担当か（権限チェック済み）。 */
  canRecordThisBoard: boolean
}

const props = defineProps<{
  /** ボード進捗の一覧。 */
  boards: ReadonlyArray<BoardProgressItem>
  /** 競技識別（将棋/囲碁でラベル変更）。 */
  sport: 'SHOGI' | 'GO' | string
  /** 全体の記録権限（親 match 作成者等）。 */
  canRecordAll: boolean
}>()

const emit = defineEmits<{
  /** 「記録する」ボタン押下（ボード番号 / 子 matchId）。 */
  recordBoard: [boardNumber: number, matchId: string | null]
}>()

const { t } = useI18n()

/** 確定済みボード数。 */
const confirmedCount = computed(() => props.boards.filter((b) => b.confirmed).length)

/** 総ボード数。 */
const totalCount = computed(() => props.boards.length)

/** 全ボード確定か（親 match COMPLETED 可能）。 */
const allConfirmed = computed(() => confirmedCount.value === totalCount.value && totalCount.value > 0)

/** 先手/黒のラベル（競技別）。 */
const homeSideLabel = computed(() =>
  props.sport === 'GO' ? t('match.turn.side.home_go') : t('match.turn.side.home_shogi'),
)
/** 後手/白のラベル（競技別）。 */
const awaySideLabel = computed(() =>
  props.sport === 'GO' ? t('match.turn.side.away_go') : t('match.turn.side.away_shogi'),
)

/** ボードの大将/副将等のラベル（board_number 基準）。 */
function boardLabel(boardNumber: number): string {
  return t('match.board.label', { n: boardNumber })
}

/** ボードの勝者ラベル（表示用）。 */
function winnerLabel(board: BoardProgressItem): string {
  if (!board.confirmed) return ''
  if (board.winnerSide === null) return t('match.turn.result.draw')
  const winnerName =
    board.winnerSide === 'HOME'
      ? (board.homePlayerName ?? homeSideLabel.value)
      : (board.awayPlayerName ?? awaySideLabel.value)
  return t('match.board.winner', { name: winnerName })
}

/** 勝ち方のラベル（i18n）。 */
function winMethodLabel(method: string | null): string | null {
  if (!method) return null
  return t(`match.win_method.${method}`, method)
}

/** ボードのステータスバッジクラス（色覚配慮）。 */
function statusBadgeClass(board: BoardProgressItem): string {
  if (board.confirmed) return 'bg-success-100 text-success-700'
  if (board.matchId) return 'bg-primary-100 text-primary-700' // 記録中
  return 'bg-warn-100 text-warn-700' // 未入力
}

/** ボードのステータスラベル。 */
function statusLabel(board: BoardProgressItem): string {
  if (board.confirmed) return t('match.board.status.confirmed')
  if (board.matchId) return t('match.board.status.in_progress')
  return t('match.board.status.pending')
}

function onRecordBoard(board: BoardProgressItem): void {
  emit('recordBoard', board.boardNumber, board.matchId)
}
</script>

<template>
  <div class="rounded-lg border border-surface-200 bg-white p-4">
    <!-- ヘッダー: 進捗サマリ（n/N） -->
    <div class="mb-3 flex items-center justify-between">
      <h3 class="font-semibold text-surface-700">{{ t('match.board.title') }}</h3>
      <div class="flex items-center gap-2">
        <span
          class="text-lg font-bold"
          :class="allConfirmed ? 'text-success-600' : 'text-primary'"
        >
          {{ confirmedCount }} / {{ totalCount }}
        </span>
        <span class="text-xs text-surface-500">{{ t('match.board.progress_label') }}</span>
        <i v-if="allConfirmed" class="pi pi-check-circle text-success-500" />
      </div>
    </div>

    <!-- 全ボード確定通知 -->
    <div v-if="allConfirmed" class="mb-3 rounded-lg bg-success-50 p-2 text-center text-sm text-success-700">
      <i class="pi pi-trophy mr-1" />
      {{ t('match.board.all_confirmed') }}
    </div>

    <!-- ボード一覧 -->
    <div class="flex flex-col gap-2">
      <div
        v-for="board in boards"
        :key="board.boardNumber"
        class="rounded-lg border p-3"
        :class="
          board.confirmed
            ? 'border-surface-200 bg-surface-50'
            : !board.matchId
              ? 'border-warn-200 bg-warn-50'
              : 'border-primary-200 bg-primary-50'
        "
      >
        <div class="flex items-start justify-between gap-2">
          <!-- ボード情報 -->
          <div class="min-w-0 flex-1">
            <!-- ボード番号 + ステータスバッジ -->
            <div class="mb-1 flex items-center gap-2">
              <span class="font-semibold text-surface-700">{{ boardLabel(board.boardNumber) }}</span>
              <span
                class="rounded-full px-2 py-0.5 text-xs font-medium"
                :class="statusBadgeClass(board)"
              >
                {{ statusLabel(board) }}
              </span>
            </div>

            <!-- 対戦者 -->
            <div class="mb-1 text-xs text-surface-600">
              <span class="font-medium">{{ homeSideLabel }}</span>
              <span class="mx-1">{{ board.homePlayerName ?? t('match.board.player_tbd') }}</span>
              <span class="text-surface-400">vs</span>
              <span class="mx-1">{{ board.awayPlayerName ?? t('match.board.player_tbd') }}</span>
              <span class="font-medium">{{ awaySideLabel }}</span>
            </div>

            <!-- 確定済み: 結果表示 -->
            <div v-if="board.confirmed" class="text-xs">
              <span class="font-medium text-success-700">{{ winnerLabel(board) }}</span>
              <span v-if="winMethodLabel(board.winMethod)" class="ml-2 text-surface-500">
                {{ winMethodLabel(board.winMethod) }}
              </span>
            </div>

            <!-- 記録担当者 -->
            <div v-if="board.recorderName" class="mt-1 text-xs text-surface-400">
              <i class="pi pi-user mr-1" />
              {{ t('match.board.recorder', { name: board.recorderName }) }}
            </div>
          </div>

          <!-- 記録ボタン（権限がある場合のみ） -->
          <div v-if="!board.confirmed && (board.canRecordThisBoard || canRecordAll)" class="shrink-0">
            <Button
              size="small"
              :label="t('match.board.record_button')"
              icon="pi pi-pencil"
              severity="primary"
              @click="onRecordBoard(board)"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 空状態 -->
    <p v-if="boards.length === 0" class="py-4 text-center text-sm text-surface-400">
      {{ t('match.board.empty') }}
    </p>
  </div>
</template>
