import type { ReservationDayOfWeekCode } from '~/composables/useReservationApi'
import { RESERVATION_DAY_OPTIONS, toHm, hmToMinutes } from '~/composables/useReservationDayOptions'

/**
 * 週グリッドのドラッグ範囲選択ロジック（F03.4.5 §3.2 拡張・管理者の枠作成の手数削減）。
 *
 * 「月曜の10:00から12:00まで」をセルのなぞりだけで指定するための**純粋関数**を集約する。
 * DOM・Vue に依存させない（ユニットテストで座標→時刻変換を直接固定するため）。
 *
 * グリッドの座標系:
 * - 列 = 曜日。`RESERVATION_DAY_OPTIONS` の並び（SUN..SAT）の添字を `dayIndex` とする。
 * - 行 = 30分刻みの時刻。`GRID_START_HOUR` を row=0 とし、row が1増えるごとに +30分。
 *   row の「終了時刻」は次の行の開始時刻（半開区間）。ドラッグ末尾セルは**そのセルを含む**ため
 *   終了時刻は `maxRow + 1` の開始時刻になる（10:00-10:30 のセル1つだけをなぞったら 10:00-10:30）。
 */

/** グリッドの表示開始時刻（時）。深夜帯まで全部出すと縦に長すぎて逆に操作しにくいので実運用帯に絞る。 */
export const GRID_START_HOUR = 6
/** グリッドの表示終了時刻（時・この時刻の開始行は含まない＝23:00 が最終行の終了時刻）。 */
export const GRID_END_HOUR = 23
/** 1行あたりの分数（BE テンプレの最小粒度＝30分に一致させる）。 */
export const GRID_ROW_MINUTES = 30

/** グリッド上の1セル（曜日列 × 時刻行）。 */
export interface SlotGridCell {
  dayIndex: number
  row: number
}

/** ドラッグ確定後の作成範囲（曜日は複数・時刻は 'HH:mm'）。 */
export interface SlotDragRange {
  days: ReservationDayOfWeekCode[]
  startTime: string
  endTime: string
}

/** グリッドの行数（GRID_START_HOUR 〜 GRID_END_HOUR を GRID_ROW_MINUTES で割った数）。 */
export function slotGridRowCount(): number {
  return ((GRID_END_HOUR - GRID_START_HOUR) * 60) / GRID_ROW_MINUTES
}

/** 行番号 → 'HH:mm'（row=0 が GRID_START_HOUR:00）。範囲外の行も算術的に解決する。 */
export function rowToHm(row: number): string {
  const total = GRID_START_HOUR * 60 + row * GRID_ROW_MINUTES
  const h = Math.floor(total / 60)
  const m = total % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

/** 'HH:mm'（または 'HH:mm:ss'） → 行番号。グリッド外は負値／行数以上になり得る（呼び側でクランプする）。 */
export function hmToRow(value: string): number {
  return Math.floor((hmToMinutes(toHm(value)) - GRID_START_HOUR * 60) / GRID_ROW_MINUTES)
}

/** セルの一意キー（占有判定の Set に使う）。 */
export function slotCellKey(dayIndex: number, row: number): string {
  return `${dayIndex}:${row}`
}

/**
 * ドラッグの始点・終点から作成範囲を解決する。
 *
 * - 逆方向（右下→左上）のドラッグも正規化して同じ範囲になる。
 * - 終端セルは範囲に**含む**ため、終了時刻は「終端行の次の行の開始時刻」になる。
 * - 曜日は始点列〜終点列の連続範囲（月〜水の一括作成）。順序は `RESERVATION_DAY_OPTIONS` の並び。
 */
export function resolveDragRange(anchor: SlotGridCell, cursor: SlotGridCell): SlotDragRange {
  const minDay = Math.min(anchor.dayIndex, cursor.dayIndex)
  const maxDay = Math.max(anchor.dayIndex, cursor.dayIndex)
  const minRow = Math.min(anchor.row, cursor.row)
  const maxRow = Math.max(anchor.row, cursor.row)

  const days: ReservationDayOfWeekCode[] = []
  for (let i = minDay; i <= maxDay; i++) {
    const opt = RESERVATION_DAY_OPTIONS[i]
    if (opt) days.push(opt.value)
  }

  return {
    days,
    startTime: rowToHm(minRow),
    endTime: rowToHm(maxRow + 1),
  }
}

/** ドラッグ範囲に含まれる全セルのキー一覧（ハイライト・占有判定の双方で使う）。 */
export function cellsInDragRange(anchor: SlotGridCell, cursor: SlotGridCell): string[] {
  const minDay = Math.min(anchor.dayIndex, cursor.dayIndex)
  const maxDay = Math.max(anchor.dayIndex, cursor.dayIndex)
  const minRow = Math.min(anchor.row, cursor.row)
  const maxRow = Math.max(anchor.row, cursor.row)
  const keys: string[] = []
  for (let d = minDay; d <= maxDay; d++) {
    for (let r = minRow; r <= maxRow; r++) keys.push(slotCellKey(d, r))
  }
  return keys
}

/** 占有セルの元データ（テンプレ・定期予約不可の双方が満たす最小形）。 */
export interface OccupyingEntry {
  dayOfWeek?: string | null
  startTime?: string | null
  endTime?: string | null
}

/**
 * 既存の枠テンプレ／定期予約不可から「埋まっているセル」のキー集合を作る。
 * グリッド表示範囲外にはみ出す部分はクランプして無視する（範囲外セルは存在しないため）。
 */
export function collectOccupiedCells(entries: ReadonlyArray<OccupyingEntry>): Set<string> {
  const occupied = new Set<string>()
  const rowCount = slotGridRowCount()
  for (const entry of entries) {
    const dayIndex = RESERVATION_DAY_OPTIONS.findIndex(d => d.value === entry.dayOfWeek)
    if (dayIndex < 0 || !entry.startTime || !entry.endTime) continue
    const from = Math.max(0, hmToRow(entry.startTime))
    // 終了時刻は半開区間の上端。ちょうど行境界に一致する場合は直前の行までが占有。
    const to = Math.min(rowCount - 1, hmToRow(entry.endTime) - 1)
    for (let r = from; r <= to; r++) occupied.add(slotCellKey(dayIndex, r))
  }
  return occupied
}

/** ドラッグ範囲が既存枠と1セルでも重なるか（重なるなら作成させず警告する）。 */
export function isDragRangeBlocked(
  anchor: SlotGridCell,
  cursor: SlotGridCell,
  occupied: ReadonlySet<string>,
): boolean {
  return cellsInDragRange(anchor, cursor).some(key => occupied.has(key))
}
