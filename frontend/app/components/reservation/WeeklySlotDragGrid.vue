<script setup lang="ts">
/**
 * 週グリッドのドラッグ範囲選択（F03.4.5 §3.2 拡張・管理者の枠作成の手数削減）
 *
 * 縦=時刻（30分刻み）・横=曜日のグリッド。なぞった範囲をその場でハイライトし、
 * 離した時点で「曜日・開始時刻・終了時刻」を確定して親へ emit する（親は定員だけを聞く）。
 *
 * 【入力デバイスごとの作法（タッチのパン衝突の解き方）】
 * - マウス/ペン: pointerdown → pointermove → pointerup の連続ドラッグ。
 *   `touch-action` は触らないため、タッチでのページスクロールは一切殺さない。
 * - タッチ: **ドラッグを乗っ取らない**。1回目のタップで始点、2回目のタップで終点を確定する
 *   2タップ方式にする（`touch-action: none` でスクロールを奪う実装は「グリッド上で画面が
 *   スクロールできない」という致命的な副作用を生むため採らない）。pointerType='touch' の
 *   pointerdown では move を購読せず、既定のパン動作をブラウザに残す。
 * - キーボード: セルは `<button>`。Enter/Space はタッチと同じ2ステップ方式で範囲を確定できる。
 * - ESC: 選択中の範囲を取り消す。`pointercancel`（タッチのパン開始・電話着信など）も取り消し扱い。
 *
 * 座標→セル特定は `document.elementFromPoint` + `closest('[data-slot-cell]')` で行う。
 * getBoundingClientRect からの算術では、スクロール領域内・sticky ヘッダー配下でずれるため使わない。
 */
import type { ReservationDayOfWeekCode } from '~/composables/useReservationApi'
import { RESERVATION_DAY_OPTIONS } from '~/composables/useReservationDayOptions'
import {
  slotGridRowCount,
  rowToHm,
  slotCellKey,
  cellsInDragRange,
  resolveDragRange,
  isDragRangeBlocked,
  type SlotGridCell,
  type SlotDragRange,
} from '~/composables/useSlotDragSelection'

const props = defineProps<{
  /** 既に埋まっているセルのキー集合（`slotCellKey` 形式）。親が templates/rules から算出して渡す。 */
  occupied: ReadonlySet<string>
}>()

const emit = defineEmits<{
  /** ドラッグ確定（曜日・時刻まで確定済み。親は定員だけを聞く）。 */
  select: [range: SlotDragRange]
  /** 既存枠を含む範囲が選択された（親が警告トーストを出す）。 */
  blocked: []
}>()

const { t } = useI18n()

const rowCount = slotGridRowCount()
const rows = computed(() => Array.from({ length: rowCount }, (_, i) => i))

const anchor = ref<SlotGridCell | null>(null)
const cursor = ref<SlotGridCell | null>(null)
/** マウス/ペンでボタンを押したままの連続ドラッグ中か（タッチ2タップ方式と区別する）。 */
const dragging = ref(false)

/** 現在ハイライトすべきセルキー集合（ドラッグ中も追従する）。 */
const highlighted = computed(() => {
  if (!anchor.value || !cursor.value) return new Set<string>()
  return new Set(cellsInDragRange(anchor.value, cursor.value))
})

/** 選択範囲に既存枠が含まれるか（ハイライト色を警告色へ切り替えるため描画中も判定する）。 */
const highlightBlocked = computed(() => {
  if (!anchor.value || !cursor.value) return false
  return isDragRangeBlocked(anchor.value, cursor.value, props.occupied)
})

/** 1時間の頭（:00）の行だけ時刻ラベルを出す（30分ごとに出すと読みにくい）。 */
function hourLabel(row: number): string {
  return row % 2 === 0 ? rowToHm(row) : ''
}

function isOccupied(dayIndex: number, row: number): boolean {
  return props.occupied.has(slotCellKey(dayIndex, row))
}

function isHighlighted(dayIndex: number, row: number): boolean {
  return highlighted.value.has(slotCellKey(dayIndex, row))
}

function cancelSelection() {
  anchor.value = null
  cursor.value = null
  dragging.value = false
  detachDragListeners()
}

/** 始点・終点が揃った時点の確定処理（ドラッグ終了・2タップ目・キーボード確定で共用）。 */
function commitSelection() {
  const a = anchor.value
  const c = cursor.value
  if (!a || !c) return
  if (isDragRangeBlocked(a, c, props.occupied)) {
    emit('blocked')
    cancelSelection()
    return
  }
  emit('select', resolveDragRange(a, c))
  cancelSelection()
}

/** 座標からセルを特定する（スクロール・sticky ヘッダーに強い elementFromPoint 方式）。 */
function cellFromPoint(x: number, y: number): SlotGridCell | null {
  const el = document.elementFromPoint(x, y)
  const cell = el?.closest<HTMLElement>('[data-slot-cell]')
  if (!cell) return null
  const dayIndex = Number(cell.dataset.dayIndex)
  const row = Number(cell.dataset.row)
  if (!Number.isFinite(dayIndex) || !Number.isFinite(row)) return null
  return { dayIndex, row }
}

function onPointerMove(e: PointerEvent) {
  if (!dragging.value) return
  const cell = cellFromPoint(e.clientX, e.clientY)
  if (cell) cursor.value = cell
}

function onPointerUp() {
  if (!dragging.value) return
  dragging.value = false
  commitSelection()
}

function onPointerCancel() {
  // タッチのパン開始・電話着信などで pointer が失われた場合は選択を捨てる（中途半端な範囲を確定させない）
  cancelSelection()
}

function attachDragListeners() {
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerCancel)
}

function detachDragListeners() {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerCancel)
}

/**
 * タッチ／キーボード共通の2ステップ選択。
 * 1回目=始点、2回目=終点（確定）。同じセルを2回叩けば30分枠1つになる。
 */
function stepSelect(cell: SlotGridCell) {
  if (!anchor.value) {
    anchor.value = cell
    cursor.value = cell
    return
  }
  cursor.value = cell
  commitSelection()
}

function onCellPointerDown(e: PointerEvent, dayIndex: number, row: number) {
  // タッチはドラッグを乗っ取らない（既定のパンを残す）。2タップ方式へ回す。
  if (e.pointerType === 'touch') {
    stepSelect({ dayIndex, row })
    return
  }
  // マウス/ペンは左ボタンのみ
  if (e.button !== 0) return
  e.preventDefault()
  anchor.value = { dayIndex, row }
  cursor.value = { dayIndex, row }
  dragging.value = true
  attachDragListeners()
}

/**
 * キーボード操作（Enter/Space）。`<button>` の既定 click は pointerdown 経路と二重発火し得るため、
 * click ではなく keydown を直接拾い、pointer 由来の click は握り潰さずに済ませる。
 */
function onCellKeydown(e: KeyboardEvent, dayIndex: number, row: number) {
  if (e.key !== 'Enter' && e.key !== ' ') return
  e.preventDefault()
  stepSelect({ dayIndex, row })
}

function onWindowKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && anchor.value) {
    e.preventDefault()
    cancelSelection()
  }
}

onMounted(() => window.addEventListener('keydown', onWindowKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  detachDragListeners()
})

/** セルの読み上げラベル（曜日＋開始時刻）。 */
function cellAriaLabel(dayLabelKey: string, row: number): string {
  return `${t(dayLabelKey)} ${rowToHm(row)}`
}

defineExpose({ cancelSelection })
</script>

<template>
  <div class="rounded-lg border border-surface-200 dark:border-surface-700" data-testid="slot-drag-grid">
    <div class="flex flex-wrap items-center justify-between gap-2 border-b border-surface-200 p-2 text-xs dark:border-surface-700">
      <span class="font-semibold">{{ t('reservation.template.grid.title') }}</span>
      <span class="flex flex-wrap items-center gap-x-3 gap-y-1 text-surface-500">
        <span class="flex items-center gap-1">
          <span class="inline-block size-2.5 rounded-sm bg-blue-300 dark:bg-blue-800" />
          {{ t('reservation.template.grid.legend_existing') }}
        </span>
        <span class="flex items-center gap-1">
          <span class="inline-block size-2.5 rounded-sm bg-primary" />
          {{ t('reservation.template.grid.legend_selected') }}
        </span>
      </span>
    </div>

    <p class="px-2 pt-2 text-xs text-surface-500">{{ t('reservation.template.grid.hint') }}</p>

    <!-- スクロール領域。座標→セル特定は elementFromPoint なのでスクロール位置の補正は不要。 -->
    <div class="max-h-96 overflow-auto p-2">
      <div class="min-w-[28rem]">
        <!-- 曜日ヘッダー（sticky） -->
        <div class="sticky top-0 z-10 flex bg-surface-0 dark:bg-surface-900">
          <div class="w-12 shrink-0" />
          <div
            v-for="day in RESERVATION_DAY_OPTIONS"
            :key="day.value"
            class="flex-1 pb-1 text-center text-xs font-semibold"
          >
            {{ t(day.labelKey) }}
          </div>
        </div>

        <div
          v-for="row in rows"
          :key="row"
          class="flex"
        >
          <div class="w-12 shrink-0 pr-1 text-right text-[10px] leading-4 text-surface-400">
            {{ hourLabel(row) }}
          </div>
          <button
            v-for="(day, dayIndex) in RESERVATION_DAY_OPTIONS"
            :key="day.value"
            type="button"
            data-slot-cell
            :data-day-index="dayIndex"
            :data-row="row"
            :data-testid="`slot-cell-${day.value}-${row}`"
            :aria-label="cellAriaLabel(day.labelKey, row)"
            :aria-pressed="isHighlighted(dayIndex, row)"
            :disabled="isOccupied(dayIndex, row)"
            class="h-4 flex-1 border-b border-r border-surface-100 transition-colors first:border-l dark:border-surface-800"
            :class="[
              row % 2 === 1 ? 'border-b-surface-200 dark:border-b-surface-700' : '',
              isOccupied(dayIndex, row)
                ? 'cursor-not-allowed bg-blue-300 dark:bg-blue-800'
                : isHighlighted(dayIndex, row)
                  ? (highlightBlocked ? 'bg-red-400' : 'bg-primary')
                  : 'hover:bg-surface-100 dark:hover:bg-surface-800',
            ]"
            @pointerdown="onCellPointerDown($event, dayIndex, row)"
            @keydown="onCellKeydown($event, dayIndex, row)"
          />
        </div>
      </div>
    </div>
  </div>
</template>
