import { describe, it, expect } from 'vitest'
import {
  GRID_START_HOUR,
  GRID_END_HOUR,
  slotGridRowCount,
  rowToHm,
  hmToRow,
  slotCellKey,
  resolveDragRange,
  cellsInDragRange,
  collectOccupiedCells,
  isDragRangeBlocked,
} from '~/composables/useSlotDragSelection'

/**
 * 週グリッドのドラッグ範囲選択ロジック — 番人
 *
 * 最重要観点: **ドラッグ範囲 → 開始/終了時刻の変換**。
 * 終端セルは範囲に含む（半開区間の上端は「終端行の次の行」）ため、10:00 の行から 11:30 の行まで
 * なぞったら 10:00-12:00 になる。ここがずれると管理者が意図した1コマ短い/長い枠が量産される。
 *
 * 列（曜日）の並びは RESERVATION_DAY_OPTIONS（SUN, MON, TUE, ...）。
 */

/** RESERVATION_DAY_OPTIONS の添字（SUN=0, MON=1, ...） */
const SUN = 0
const MON = 1
const TUE = 2
const WED = 3

/** グリッド行番号（row=0 が GRID_START_HOUR:00・30分刻み） */
function rowOf(hm: string): number {
  return hmToRow(hm)
}

describe('useSlotDragSelection — グリッド座標系', () => {
  it('行数は表示時間帯を30分で割った数', () => {
    expect(slotGridRowCount()).toBe((GRID_END_HOUR - GRID_START_HOUR) * 2)
  })

  it('row=0 は表示開始時刻・行が1増えると30分進む', () => {
    expect(rowToHm(0)).toBe(`${String(GRID_START_HOUR).padStart(2, '0')}:00`)
    expect(rowToHm(1)).toBe(`${String(GRID_START_HOUR).padStart(2, '0')}:30`)
    expect(rowToHm(2)).toBe(`${String(GRID_START_HOUR + 1).padStart(2, '0')}:00`)
  })

  it('hmToRow は rowToHm の逆変換', () => {
    for (const row of [0, 1, 5, 12, slotGridRowCount() - 1]) {
      expect(hmToRow(rowToHm(row))).toBe(row)
    }
  })

  it('hmToRow は BE の HH:mm:ss 表現も受け付ける', () => {
    expect(hmToRow('10:00:00')).toBe(rowOf('10:00'))
  })
})

describe('resolveDragRange — ドラッグ範囲が正しい開始/終了時刻に変換される', () => {
  it('月曜 10:00 の行から 11:30 の行までなぞると 10:00-12:00（終端セルを含む）', () => {
    const range = resolveDragRange(
      { dayIndex: MON, row: rowOf('10:00') },
      { dayIndex: MON, row: rowOf('11:30') },
    )
    expect(range.days).toEqual(['MON'])
    expect(range.startTime).toBe('10:00')
    expect(range.endTime).toBe('12:00')
  })

  it('セル1つだけの選択は30分枠になる', () => {
    const range = resolveDragRange(
      { dayIndex: MON, row: rowOf('10:00') },
      { dayIndex: MON, row: rowOf('10:00') },
    )
    expect(range.startTime).toBe('10:00')
    expect(range.endTime).toBe('10:30')
  })

  it('逆方向（下から上・右から左）のドラッグも同じ範囲に正規化される', () => {
    const forward = resolveDragRange(
      { dayIndex: MON, row: rowOf('10:00') },
      { dayIndex: WED, row: rowOf('11:30') },
    )
    const backward = resolveDragRange(
      { dayIndex: WED, row: rowOf('11:30') },
      { dayIndex: MON, row: rowOf('10:00') },
    )
    expect(backward).toEqual(forward)
  })

  it('複数曜日にまたがるドラッグは連続する曜日コードを列挙する（月〜水）', () => {
    const range = resolveDragRange(
      { dayIndex: MON, row: rowOf('10:00') },
      { dayIndex: WED, row: rowOf('11:30') },
    )
    expect(range.days).toEqual(['MON', 'TUE', 'WED'])
    expect(range.startTime).toBe('10:00')
    expect(range.endTime).toBe('12:00')
  })

  it('曜日コードは BE 正準の3文字大文字（フルネームは 400 になる）', () => {
    const range = resolveDragRange(
      { dayIndex: SUN, row: 0 },
      { dayIndex: 6, row: 0 },
    )
    expect(range.days).toEqual(['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'])
  })
})

describe('cellsInDragRange — ハイライト対象セル', () => {
  it('曜日×行の矩形すべてを列挙する', () => {
    const keys = cellsInDragRange(
      { dayIndex: MON, row: 2 },
      { dayIndex: TUE, row: 3 },
    )
    expect(keys.sort()).toEqual([
      slotCellKey(MON, 2), slotCellKey(MON, 3),
      slotCellKey(TUE, 2), slotCellKey(TUE, 3),
    ].sort())
  })
})

describe('collectOccupiedCells / isDragRangeBlocked — 既存枠を含む範囲は弾かれる', () => {
  const existing = [{ dayOfWeek: 'MON', startTime: '10:00:00', endTime: '11:00:00' }]

  it('既存枠の占有は半開区間（終了時刻の行そのものは埋まらない）', () => {
    const occupied = collectOccupiedCells(existing)
    expect(occupied.has(slotCellKey(MON, rowOf('10:00')))).toBe(true)
    expect(occupied.has(slotCellKey(MON, rowOf('10:30')))).toBe(true)
    expect(occupied.has(slotCellKey(MON, rowOf('11:00')))).toBe(false)
  })

  it('別曜日の同時刻は占有しない', () => {
    const occupied = collectOccupiedCells(existing)
    expect(occupied.has(slotCellKey(TUE, rowOf('10:00')))).toBe(false)
  })

  it('既存枠と1セルでも重なる範囲は blocked', () => {
    const occupied = collectOccupiedCells(existing)
    expect(isDragRangeBlocked(
      { dayIndex: MON, row: rowOf('09:00') },
      { dayIndex: MON, row: rowOf('10:00') },
      occupied,
    )).toBe(true)
  })

  it('複数曜日ドラッグは1曜日でも既存枠に当たれば blocked', () => {
    const occupied = collectOccupiedCells(existing)
    expect(isDragRangeBlocked(
      { dayIndex: SUN, row: rowOf('10:00') },
      { dayIndex: WED, row: rowOf('10:30') },
      occupied,
    )).toBe(true)
  })

  it('既存枠に触れない範囲は blocked にならない', () => {
    const occupied = collectOccupiedCells(existing)
    expect(isDragRangeBlocked(
      { dayIndex: MON, row: rowOf('11:00') },
      { dayIndex: MON, row: rowOf('12:00') },
      occupied,
    )).toBe(false)
  })

  it('定期予約不可も「埋まっている」として扱う（同一 API 形状）', () => {
    const occupied = collectOccupiedCells([
      { dayOfWeek: 'TUE', startTime: '19:00:00', endTime: '20:00:00' },
    ])
    expect(occupied.has(slotCellKey(TUE, rowOf('19:00')))).toBe(true)
  })

  it('グリッド表示範囲外へはみ出す既存枠はクランプされる（例外を投げない）', () => {
    const occupied = collectOccupiedCells([
      { dayOfWeek: 'MON', startTime: '00:00:00', endTime: '23:59:00' },
    ])
    expect(occupied.has(slotCellKey(MON, 0))).toBe(true)
    expect(occupied.has(slotCellKey(MON, slotGridRowCount() - 1))).toBe(true)
    expect(occupied.has(slotCellKey(MON, slotGridRowCount()))).toBe(false)
  })

  it('曜日不明・時刻欠落のエントリは無視する', () => {
    const occupied = collectOccupiedCells([
      { dayOfWeek: null, startTime: '10:00:00', endTime: '11:00:00' },
      { dayOfWeek: 'MON', startTime: null, endTime: '11:00:00' },
    ])
    expect(occupied.size).toBe(0)
  })
})
