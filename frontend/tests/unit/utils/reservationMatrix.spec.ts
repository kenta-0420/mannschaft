import { describe, it, expect } from 'vitest'
import {
  toMinutes,
  formatMinutes,
  buildTimeHeader,
  alignRowToHeader,
  computeStartableIndices,
  collectConsecutiveSlotIds,
  canExtend,
  isPastCell,
  cellUnavailableReason,
  unavailableReasonOfSlot,
  type MatrixCellInput,
} from '~/utils/reservationMatrix'

/**
 * reservationMatrix ユーティリティのユニットテスト（F03.4.4 §5.3 B4/B6/B8 の番人）。
 *
 * 観点:
 *   - B8: "HH:mm" / "HH:mm:ss" 表記揺れを分正規化後の数値比較で吸収する
 *   - B4: cells[]→固定30分ヘッダへの整列（startTime一致列配置・colspan跨ぎ・非被覆列=−）
 *   - §5.2: 連続空きの網掛け判定（長尺枠は起点・構成要素にしない）
 *   - §5.3: 延長ボタン disabled 条件3点
 *   - B6: 過去セルの disabled 判定
 */

describe('toMinutes / formatMinutes（B8: 表記揺れの正規化）', () => {
  it('"HH:mm" と "HH:mm:ss" の両表記を同じ分値に正規化する', () => {
    expect(toMinutes('10:00')).toBe(600)
    expect(toMinutes('10:00:00')).toBe(600)
    expect(toMinutes('09:30')).toBe(570)
  })

  it('不正/未指定はセンチネル -1 を返す（比較で必ず負ける）', () => {
    expect(toMinutes(undefined)).toBe(-1)
    expect(toMinutes(null)).toBe(-1)
    expect(toMinutes('')).toBe(-1)
    expect(toMinutes('bogus')).toBe(-1)
  })

  it('formatMinutes は HH:mm へゼロ埋め整形する', () => {
    expect(formatMinutes(600)).toBe('10:00')
    expect(formatMinutes(30)).toBe('00:30')
  })
})

describe('buildTimeHeader（B4手順1）', () => {
  it('min(startTime)〜max(endTime) を30分刻みの固定ヘッダにする', () => {
    const cells: MatrixCellInput[] = [
      { startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
      { startTime: '11:00', endTime: '11:30', state: 'BOOKED' },
    ]
    const header = buildTimeHeader(cells)
    expect(header.map(h => h.label)).toEqual(['10:00', '10:30', '11:00'])
  })

  it('セルが1件もない場合は空配列', () => {
    expect(buildTimeHeader([])).toEqual([])
  })
})

describe('alignRowToHeader（B4手順2・3: colspan跨ぎ・非被覆=empty）', () => {
  it('30分セル4枠を4ヘッダ列にそのまま配置する', () => {
    const header = buildTimeHeader([
      { startTime: '10:00', endTime: '10:30' },
      { startTime: '10:30', endTime: '11:00' },
      { startTime: '11:00', endTime: '11:30' },
      { startTime: '11:30', endTime: '12:00' },
    ])
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
      { slotId: 2, startTime: '10:30', endTime: '11:00', state: 'AVAILABLE' },
      { slotId: 3, startTime: '11:00', endTime: '11:30', state: 'BOOKED' },
      { slotId: 4, startTime: '11:30', endTime: '12:00', state: 'AVAILABLE' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(row).toHaveLength(4)
    expect(row.every(r => r.kind === 'cell')).toBe(true)
  })

  it('60分の長尺枠は colspan=2 で2ヘッダ列を1セルとして描画し、後続は covered になる', () => {
    const header = buildTimeHeader([
      { startTime: '10:00', endTime: '11:00' },
      { startTime: '11:00', endTime: '11:30' },
    ])
    const cells: MatrixCellInput[] = [
      { slotId: 99, startTime: '10:00', endTime: '11:00', state: 'AVAILABLE' },
      { slotId: 100, startTime: '11:00', endTime: '11:30', state: 'AVAILABLE' },
    ]
    const row = alignRowToHeader(cells, header)
    // ヘッダは 10:00, 10:30, 11:00 の3列（60分枠が10:00-11:00で10:30列も覆う）
    expect(header.map(h => h.label)).toEqual(['10:00', '10:30', '11:00'])
    expect(row[0]).toMatchObject({ kind: 'cell', span: 2, cell: { slotId: 99 } })
    expect(row[1]).toMatchObject({ kind: 'covered' })
    expect(row[2]).toMatchObject({ kind: 'cell', span: 1, cell: { slotId: 100 } })
  })

  it('どのセルにも覆われないヘッダ列は empty（−ダッシュ描画）になる', () => {
    const header = buildTimeHeader([
      { startTime: '10:00', endTime: '10:30' },
      { startTime: '11:00', endTime: '11:30' }, // 10:30 のヘッダ列に対応するセルは無い
    ])
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
      { slotId: 2, startTime: '11:00', endTime: '11:30', state: 'AVAILABLE' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(header.map(h => h.label)).toEqual(['10:00', '10:30', '11:00'])
    expect(row[1]).toEqual({ kind: 'empty' })
  })

  it('"HH:mm:ss" 表記のセルも "HH:mm" ヘッダへ正しく整列する（B8）', () => {
    const header = buildTimeHeader([{ startTime: '10:00:00', endTime: '10:30:00' }])
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '10:00:00', endTime: '10:30:00', state: 'AVAILABLE' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(row[0]).toMatchObject({ kind: 'cell', cell: { slotId: 1 } })
  })
})

describe('computeStartableIndices / collectConsecutiveSlotIds（§5.2: 連続空きの網掛け）', () => {
  const header = buildTimeHeader([
    { startTime: '10:00', endTime: '10:30' },
    { startTime: '10:30', endTime: '11:00' },
    { startTime: '11:00', endTime: '11:30' },
  ])

  it('H-7: requiredCellCount=2 のとき [○,○,●] は1セル目のみクリック可・2セル目は不可', () => {
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
      { slotId: 2, startTime: '10:30', endTime: '11:00', state: 'AVAILABLE' },
      { slotId: 3, startTime: '11:00', endTime: '11:30', state: 'BOOKED' },
    ]
    const row = alignRowToHeader(cells, header)
    const startable = computeStartableIndices(row, 2)
    expect(startable.has(0)).toBe(true)
    expect(startable.has(1)).toBe(false)
    expect(startable.has(2)).toBe(false)
  })

  it('長尺枠（span>1）は連続の起点にも構成要素にもならない', () => {
    const longHeader = buildTimeHeader([
      { startTime: '10:00', endTime: '11:00' },
      { startTime: '11:00', endTime: '11:30' },
      { startTime: '11:30', endTime: '12:00' },
    ])
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '10:00', endTime: '11:00', state: 'AVAILABLE' }, // span=2 (60分)
      { slotId: 2, startTime: '11:00', endTime: '11:30', state: 'AVAILABLE' },
      { slotId: 3, startTime: '11:30', endTime: '12:00', state: 'AVAILABLE' },
    ]
    const row = alignRowToHeader(cells, longHeader)
    // ヘッダは [10:00, 10:30, 11:00, 11:30] の4列
    const startable = computeStartableIndices(row, 2)
    // インデックス0（60分枠の開始列）は span!=1 のため起点になれない
    expect(startable.has(0)).toBe(false)
    // インデックス1は covered のため起点になれない
    expect(startable.has(1)).toBe(false)
    // インデックス2（11:00開始の30分枠）は次（11:30）も AVAILABLE のため起点になれる
    expect(startable.has(2)).toBe(true)
  })

  it('collectConsecutiveSlotIds は時間昇順で連続する slotId を返す', () => {
    const cells: MatrixCellInput[] = [
      { slotId: 10, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
      { slotId: 11, startTime: '10:30', endTime: '11:00', state: 'AVAILABLE' },
      { slotId: 12, startTime: '11:00', endTime: '11:30', state: 'AVAILABLE' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(collectConsecutiveSlotIds(row, 0, 2)).toEqual([10, 11])
    expect(collectConsecutiveSlotIds(row, 0, 3)).toEqual([10, 11, 12])
  })

  it('連続が取れない場合は null を返す', () => {
    const cells: MatrixCellInput[] = [
      { slotId: 10, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
      { slotId: 11, startTime: '10:30', endTime: '11:00', state: 'BOOKED' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(collectConsecutiveSlotIds(row, 0, 2)).toBeNull()
  })
})

describe('canExtend（§5.3: ＋30分延長ボタンの disabled 条件3点）', () => {
  const header = buildTimeHeader([
    { startTime: '10:00', endTime: '10:30' },
    { startTime: '10:30', endTime: '11:00' },
    { startTime: '11:00', endTime: '11:30' },
  ])

  it('直後セルが AVAILABLE な30分セルなら延長可', () => {
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
      { slotId: 2, startTime: '10:30', endTime: '11:00', state: 'AVAILABLE' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(canExtend(row, [0])).toBe(true)
  })

  it('(1) 直後セルが存在しない（終端）場合は延長不可', () => {
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '11:00', endTime: '11:30', state: 'AVAILABLE' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(canExtend(row, [2])).toBe(false)
  })

  it('(2) 直後セルが AVAILABLE でない（BOOKED）場合は延長不可', () => {
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
      { slotId: 2, startTime: '10:30', endTime: '11:00', state: 'BOOKED' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(canExtend(row, [0])).toBe(false)
  })

  it('(3) 選択枠数が既に16枠に達している場合は延長不可', () => {
    const bigHeader = Array.from({ length: 17 }, (_, i) => ({
      startTime: formatMinutes(600 + i * 30),
      endTime: formatMinutes(630 + i * 30),
    }))
    const header17 = buildTimeHeader(bigHeader)
    const cells: MatrixCellInput[] = bigHeader.map((h, i) => ({
      slotId: i + 1,
      startTime: h.startTime,
      endTime: h.endTime,
      state: 'AVAILABLE' as const,
    }))
    const row = alignRowToHeader(cells, header17)
    const selected16 = Array.from({ length: 16 }, (_, i) => i)
    expect(canExtend(row, selected16)).toBe(false)
  })
})

describe('isPastCell（B6: 過去セルの disabled 判定）', () => {
  it('過去日の行は常に disabled', () => {
    expect(isPastCell('2026-07-01', '10:00', '2026-07-08', 600)).toBe(true)
  })

  it('当日で現在時刻より前に開始する枠は disabled', () => {
    expect(isPastCell('2026-07-08', '09:00', '2026-07-08', 600)).toBe(true)
  })

  it('当日で現在時刻以降に開始する枠は disabled でない', () => {
    expect(isPastCell('2026-07-08', '10:30', '2026-07-08', 600)).toBe(false)
  })

  it('未来日は disabled でない', () => {
    expect(isPastCell('2026-07-09', '00:00', '2026-07-08', 600)).toBe(false)
  })
})

describe('cellUnavailableReason / unavailableReasonOfSlot（F03.4.5 §4.4: 定期予約不可枠の事由ラベル）', () => {
  it('state=UNAVAILABLE かつ unavailableReason ありなら値を返す（is_public=TRUE の定期ルール由来）', () => {
    const cell: MatrixCellInput = { state: 'UNAVAILABLE', unavailableReason: '研修' }
    expect(cellUnavailableReason(cell)).toBe('研修')
  })

  it('state=UNAVAILABLE だが unavailableReason が無ければ null（単発 blocked_times 由来・非公開ルール由来）', () => {
    const cell: MatrixCellInput = { state: 'UNAVAILABLE' }
    expect(cellUnavailableReason(cell)).toBeNull()
    expect(cellUnavailableReason({ state: 'UNAVAILABLE', unavailableReason: null })).toBeNull()
  })

  it('state が UNAVAILABLE 以外なら unavailableReason があっても無視する（BE契約上ありえないが FE は state を正とする）', () => {
    expect(cellUnavailableReason({ state: 'AVAILABLE', unavailableReason: '研修' })).toBeNull()
  })

  it('cell が undefined なら null', () => {
    expect(cellUnavailableReason(undefined)).toBeNull()
  })

  it('unavailableReasonOfSlot: RowSlot 経由でも同じ判定になる（alignRowToHeader が cell 参照をそのまま保持することの番人）', () => {
    const header = buildTimeHeader([{ startTime: '19:00', endTime: '19:30' }])
    const cells: MatrixCellInput[] = [
      { slotId: 1, startTime: '19:00', endTime: '19:30', state: 'UNAVAILABLE', unavailableReason: '研修' },
    ]
    const row = alignRowToHeader(cells, header)
    expect(unavailableReasonOfSlot(row[0])).toBe('研修')
  })

  it('unavailableReasonOfSlot: covered/empty マスは null', () => {
    expect(unavailableReasonOfSlot({ kind: 'empty' })).toBeNull()
    expect(unavailableReasonOfSlot({ kind: 'covered' })).toBeNull()
    expect(unavailableReasonOfSlot(undefined)).toBeNull()
  })
})
