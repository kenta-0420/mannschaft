/**
 * F03.4.4 マトリックスUI（SlotMatrixPicker）の純ロジック。
 *
 * BE の cells[] を固定30分ヘッダへ整列する（B4）・連続空きの網掛け判定（§5.2）・
 * 延長ボタンの disabled 条件（§5.3）を Vue から切り離した純関数として実装する。
 * 時刻文字列は Jackson LocalTime の既定シリアライズ（"HH:mm"・秒なし）だが
 * 表記揺れ（"HH:mm:ss"）にも耐えるよう、必ず「0:00からの経過分」へ正規化してから比較する（B8）。
 */

export type MatrixCellState = 'AVAILABLE' | 'BOOKED' | 'CLOSED' | 'UNAVAILABLE'

/** BE GridCellDto 相当の最小構造（生成型に依存させず純関数を独立させる）。 */
export interface MatrixCellInput {
  slotId?: number
  /** 枠の業務日（通常は行の日付）。 */
  slotDate?: string
  /** 日跨ぎ枠の終了業務日。 */
  endDate?: string
  startTime?: string
  endTime?: string
  state?: MatrixCellState
  price?: number
  /**
   * F03.4.5 §4.4: state=UNAVAILABLE かつ判定元が is_public=TRUE の定期予約不可ルールのときのみ
   * BE が値を詰める（それ以外＝単発 blocked_times 由来・is_public=FALSE は null/undefined）。
   * FE は「値があれば出す・無ければ従来表示」だけを守り、公開判定を再実装しない。
   */
  unavailableReason?: string | null
}

/** 固定30分ヘッダの1列。 */
export interface HeaderSlot {
  /** 0:00 からの経過分（正規化済み）。 */
  minutes: number
  /** "HH:mm" 表示ラベル。 */
  label: string
}

/** ヘッダ列に整列した行の1マス。 */
export type RowSlot =
  | { kind: 'cell'; span: number; cell: MatrixCellInput }
  | { kind: 'covered' }
  | { kind: 'empty' }

const GRANULARITY_MINUTES = 30

/**
 * "HH:mm" / "HH:mm:ss" いずれの表記でも 0:00 からの経過分に正規化する。
 * 不正/未指定は -1（比較で必ず負けるセンチネル）を返す。
 */
export function toMinutes(time: string | undefined | null): number {
  if (!time) return -1
  const parts = time.split(':')
  if (parts.length < 2) return -1
  const h = Number(parts[0])
  const m = Number(parts[1])
  if (!Number.isFinite(h) || !Number.isFinite(m)) return -1
  return h * 60 + m
}

/** 経過分を "HH:mm" 表示へ整形する。 */
export function formatMinutes(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

/** endDate が翌日の実 slot は、壁時計の endTime を翌日分として扱う。 */
function endMinutesForCell(cell: MatrixCellInput): number {
  const end = toMinutes(cell.endTime)
  if (end < 0) return end
  if (cell.slotDate && cell.endDate && cell.endDate !== cell.slotDate) return end + 24 * 60
  return end
}

/**
 * 表示対象の全セルから min(startTime)〜max(endTime) を取り、30分刻みの固定ヘッダ列を構築する（B4手順1）。
 * セルが1件もない場合は空配列。
 */
export function buildTimeHeader(cells: MatrixCellInput[]): HeaderSlot[] {
  let minStart = Number.POSITIVE_INFINITY
  let maxEnd = Number.NEGATIVE_INFINITY
  for (const c of cells) {
    const s = toMinutes(c.startTime)
    const e = endMinutesForCell(c)
    if (s < 0 || e < 0) continue
    if (s < minStart) minStart = s
    if (e > maxEnd) maxEnd = e
  }
  if (!Number.isFinite(minStart) || !Number.isFinite(maxEnd) || maxEnd <= minStart) return []

  const header: HeaderSlot[] = []
  for (let m = minStart; m < maxEnd; m += GRANULARITY_MINUTES) {
    header.push({ minutes: m, label: formatMinutes(m) })
  }
  return header
}

/**
 * 1行（日付×予約対象）のセル配列を固定ヘッダへ整列する（B4手順2・3）。
 * - startTime が一致するヘッダ列に配置し、span=(end-start)/30 の colspan で跨がせる
 * - どのセルにも覆われないヘッダ列は 'empty'（− 描画）
 * - colspan に含まれる後続ヘッダ列は 'covered'（個別描画しない）
 */
export function alignRowToHeader(cells: MatrixCellInput[], header: HeaderSlot[]): RowSlot[] {
  const row: RowSlot[] = header.map(() => ({ kind: 'empty' }))
  const indexByMinutes = new Map<number, number>()
  header.forEach((h, i) => indexByMinutes.set(h.minutes, i))

  for (const c of cells) {
    const s = toMinutes(c.startTime)
    const e = endMinutesForCell(c)
    if (s < 0 || e < 0 || e <= s) continue
    const startIndex = indexByMinutes.get(s)
    if (startIndex === undefined) continue
    const span = Math.max(1, Math.round((e - s) / GRANULARITY_MINUTES))
    row[startIndex] = { kind: 'cell', span, cell: c }
    for (let i = 1; i < span && startIndex + i < row.length; i++) {
      row[startIndex + i] = { kind: 'covered' }
    }
  }
  return row
}

/**
 * 連続確保の起点になり得るヘッダ列インデックスの集合を返す（§5.2）。
 *
 * 30分正規化後のヘッダ列インデックス基準で判定する。長尺枠（span>1）は
 * グループ連続確保の構成要素にしない（B4手順4）ため、起点セル・後続セルとも
 * span===1 かつ state===AVAILABLE のセルのみが連続としてカウントされる。
 */
export function computeStartableIndices(row: RowSlot[], requiredCellCount: number): Set<number> {
  const startable = new Set<number>()
  if (requiredCellCount <= 0) return startable
  for (let i = 0; i < row.length; i++) {
    if (isConsecutiveAvailable(row, i, requiredCellCount)) startable.add(i)
  }
  return startable
}

/** row[start..start+count-1] が全て span===1 の AVAILABLE セルで連続しているか判定する。 */
function isConsecutiveAvailable(row: RowSlot[], start: number, count: number): boolean {
  if (start + count > row.length) return false
  for (let i = start; i < start + count; i++) {
    const slot = row[i]
    if (!slot || slot.kind !== 'cell' || slot.span !== 1 || slot.cell.state !== 'AVAILABLE') {
      return false
    }
  }
  return true
}

/**
 * 起点ヘッダ列から count 個の連続 AVAILABLE 30分セルの slotId を時間昇順で収集する。
 * 連続が取れない場合は null（呼び出し側は「この起点からは確保できない」として扱う）。
 */
export function collectConsecutiveSlotIds(row: RowSlot[], startIndex: number, count: number): number[] | null {
  if (!isConsecutiveAvailable(row, startIndex, count)) return null
  const ids: number[] = []
  for (let i = startIndex; i < startIndex + count; i++) {
    const slot = row[i]
    if (!slot || slot.kind !== 'cell' || slot.cell.slotId == null) return null
    ids.push(slot.cell.slotId)
  }
  return ids
}

/** グループ最大枠数（F03.4.3 GROUP_SIZE_EXCEEDED の上限）。 */
export const GROUP_MAX_SIZE = 16

/**
 * 「＋30分延長」ボタンの disabled 条件（§5.3・確定仕様）。
 * 以下いずれかで延長不可:
 *   (1) 選択末尾の直後セルが存在しない（枠の隙間/終端）
 *   (2) 直後セルが AVAILABLE でない（span>1 の長尺枠を含む＝連続構成要素にならないため）
 *   (3) 選択枠数が既に上限（16）
 */
export function canExtend(row: RowSlot[], selectedHeaderIndices: number[]): boolean {
  if (selectedHeaderIndices.length === 0) return false
  if (selectedHeaderIndices.length >= GROUP_MAX_SIZE) return false
  const last = Math.max(...selectedHeaderIndices)
  const nextIndex = last + 1
  if (nextIndex >= row.length) return false
  const next = row[nextIndex]
  return !!next && next.kind === 'cell' && next.span === 1 && next.cell.state === 'AVAILABLE'
}

/**
 * ドラッグ複数選択で「連続確保の構成要素になれる」マスか判定する。
 *
 * `isConsecutiveAvailable` と同一条件（span===1 の AVAILABLE セル）。長尺枠（span>1）は
 * グループ連続確保の構成要素にしない（B4手順4）という既存方針に揃えるため、AVAILABLE でも
 * 対象外にする。`covered`（長尺枠に覆われた後続列）・`empty` も当然対象外。
 */
function isDraggableCell(slot: RowSlot | undefined): boolean {
  return !!slot && slot.kind === 'cell' && slot.span === 1 && slot.cell.state === 'AVAILABLE'
}

/**
 * ドラッグ複数選択の範囲を解決する（機能H・会員のドラッグ複数選択）。
 *
 * `anchorIndex`（pointerdown したマス）から `focusIndex`（現在ポインタが乗っているマス）へ
 * **アンカー側から順に**走査し、連続確保の構成要素になれないマスに当たった時点で打ち切る。
 * 「打ち切る」であって「弾く」ではないため、BOOKED/CLOSED/UNAVAILABLE や長尺枠を跨いだ
 * ドラッグをしても、跨ぐ手前までの連続範囲が選ばれる（選択が突然全消えしない）。
 *
 * 重要（長尺枠の扱い）: 引数は**ヘッダ列インデックス**であり、長尺枠が跨ぐ列は `covered` として
 * `row` に実在する。よって素朴な連番走査のままで跨ぎ枠を正しく境界として扱える（呼び出し側は
 * DOM から必ず「そのマスのヘッダ列インデックス」を渡すこと。可視セルの通し番号ではない）。
 *
 * 左方向ドラッグにも対応する（戻り値は常に昇順）。選択数は GROUP_MAX_SIZE で頭打ちにする
 * （BE の GROUP_SIZE_EXCEEDED=041 を UI 側で先回りして防ぐ）。
 * アンカー自体が対象外なら空配列（＝選択不成立）。
 *
 * `isSelectable` は「セルの形（state/span）以外の理由で選べない」条件を呼び出し側から注入する
 * ための任意述語。SlotMatrixPicker は過去セル判定（B6）を渡す。左方向ドラッグで当日の
 * 現在時刻より前のマスへ戻ったとき、そこで打ち切るために要る。
 */
export function resolveDragSelection(
  row: RowSlot[],
  anchorIndex: number,
  focusIndex: number,
  isSelectable?: (index: number) => boolean,
): number[] {
  const eligible = (i: number) => isDraggableCell(row[i]) && (isSelectable?.(i) ?? true)
  if (!eligible(anchorIndex)) return []
  const indices = [anchorIndex]
  const step = focusIndex >= anchorIndex ? 1 : -1
  for (let i = anchorIndex + step; step > 0 ? i <= focusIndex : i >= focusIndex; i += step) {
    if (i < 0 || i >= row.length) break
    if (!eligible(i)) break
    if (indices.length >= GROUP_MAX_SIZE) break
    indices.push(i)
  }
  return indices.sort((a, b) => a - b)
}

/**
 * 過去セル判定（B6）。当日で現在時刻より前に開始する枠 or 過去日そのものを disabled にする。
 * `todayStr`/`nowMinutes` を明示引数にして Date.now() 直書きを避ける（Clock 注入の規約に準拠）。
 */
export function isPastCell(date: string, cellStartTime: string | undefined, todayStr: string, nowMinutes: number): boolean {
  if (date < todayStr) return true
  if (date > todayStr) return false
  return toMinutes(cellStartTime) < nowMinutes
}

/** 月曜起点の週の月曜日付（YYYY-MM-DD）を返す。dow は dayjs の day()（0=日〜6=土）。 */
export function mondayOffsetDays(dow: number): number {
  // 月曜=0, 火=1, ..., 日=6 になるようシフトする
  return (dow + 6) % 7
}

/**
 * UNAVAILABLE セルの事由ラベル（F03.4.5 §4.4）。
 *
 * `unavailableReason` は「is_public=TRUE の定期予約不可ルール」由来のときのみ BE が値を詰める
 * （単発 blocked_times 由来・非公開ルールは null/undefined）。FE は「値があれば出す・無ければ
 * 従来表示のまま」だけを守り、公開可否を FE 側で再判定しない
 * （`feedback_type_lie_undefined_fallback_silent_death` の型の嘘対策・純関数化してテスト可能にする）。
 */
export function cellUnavailableReason(cell: MatrixCellInput | undefined): string | null {
  if (!cell || cell.state !== 'UNAVAILABLE') return null
  return cell.unavailableReason ?? null
}

/** RowSlot（ヘッダ整列後のマス）から事由ラベルを取り出す（SlotMatrixPicker 用）。 */
export function unavailableReasonOfSlot(slot: RowSlot | undefined): string | null {
  if (!slot || slot.kind !== 'cell') return null
  return cellUnavailableReason(slot.cell)
}
