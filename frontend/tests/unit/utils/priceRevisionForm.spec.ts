import { describe, expect, it } from 'vitest'
import {
  basisPointsToPercent,
  buildProductKeyOptions,
  buildTaxCodeOptions,
  isSelectableOption,
  percentToBasisPoints,
} from '~/utils/priceRevisionForm'

describe('percentToBasisPoints（税率％入力 → basis points）', () => {
  it.each([
    [10, 1000],
    [8, 800],
    [7.5, 750],
    [0.01, 1],
    [0.07, 7], // 0.07 * 100 = 7.000000000000001 でも丸め誤差で弾かない
    [1.15, 115], // 1.15 * 100 = 114.99999999999999
    [0, 0],
    [100, 10000],
    [12.34, 1234],
  ])('%s%% は %s bp になる', (percent, bp) => {
    expect(percentToBasisPoints(percent)).toBe(bp)
  })

  it.each([
    [-0.01],
    [-5],
    [100.01],
    [101],
    [7.505], // 0.01% 未満の端数は黙って丸めず拒否する
    [0.001],
    [Number.NaN],
    [Number.POSITIVE_INFINITY],
  ])('%s%% は範囲外・端数・非有限として null を返す', (percent) => {
    expect(percentToBasisPoints(percent)).toBeNull()
  })

  it('null / undefined（未入力）は null', () => {
    expect(percentToBasisPoints(null)).toBeNull()
    expect(percentToBasisPoints(undefined)).toBeNull()
  })
})

describe('basisPointsToPercent（表示用）', () => {
  it.each([[1000, 10], [750, 7.5], [1, 0.01], [0, 0], [10000, 100]])('%s bp は %s%%', (bp, percent) => {
    expect(basisPointsToPercent(bp)).toBe(percent)
  })

  it('往復しても値が変わらない（0〜10000 の全 bp）', () => {
    for (let bp = 0; bp <= 10000; bp += 1) {
      expect(percentToBasisPoints(basisPointsToPercent(bp))).toBe(bp)
    }
  })
})

describe('buildProductKeyOptions（商品キーの選択肢）', () => {
  const plans = [
    { planKey: 'FREE', enabled: true },
    { planKey: 'FULL', enabled: true },
    { planKey: 'OLD', enabled: false },
  ]
  const features = [
    { featureKey: 'A', enabled: true, addonAvailable: true },
    { featureKey: 'B', enabled: true, addonAvailable: false },
    { featureKey: 'C', enabled: false, addonAvailable: true },
    { featureKey: 'D', enabled: true },
  ]

  it('PLAN は有効なプランキーだけ', () => {
    expect(buildProductKeyOptions('PLAN', plans, features)).toEqual(['FREE', 'FULL'])
  })

  it('ADDON は有効かつ単体契約可の機能キーだけ（BE の ADDON_NOT_AVAILABLE と同じ条件）', () => {
    expect(buildProductKeyOptions('ADDON', plans, features)).toEqual(['A'])
  })

  it('キー欠落の行は無視する', () => {
    expect(buildProductKeyOptions('PLAN', [{ enabled: true }], [])).toEqual([])
  })
})

describe('buildTaxCodeOptions（税コードの選択肢）', () => {
  const at = Date.parse('2026-10-15T00:00:00Z')
  const base = { displayName: 'x', stripeTaxCode: null, enabled: true }
  const codes = [
    { ...base, id: '1', code: 'STD', rateBasisPoints: 1000, validFrom: '2026-01-01T00:00:00Z', validUntil: null },
    { ...base, id: '2', code: 'FUTURE', rateBasisPoints: 800, validFrom: '2026-11-01T00:00:00Z', validUntil: null },
    { ...base, id: '3', code: 'EXPIRED', rateBasisPoints: 500, validFrom: '2025-01-01T00:00:00Z', validUntil: '2026-10-15T00:00:00Z' },
    { ...base, id: '4', code: 'OFF', rateBasisPoints: 300, validFrom: '2026-01-01T00:00:00Z', validUntil: null, enabled: false },
    { ...base, id: '5', code: 'REDUCED', rateBasisPoints: 800, validFrom: '2026-01-01T00:00:00Z', validUntil: '2026-12-01T00:00:00Z' },
  ]

  it('適用開始日時に有効（半開区間 [validFrom, validUntil)・enabled）な税コードだけを返す', () => {
    expect(buildTaxCodeOptions(codes, at).map((c) => c.code)).toEqual(['STD', 'REDUCED'])
  })

  it('validFrom ちょうどは有効、validUntil ちょうどは無効', () => {
    expect(buildTaxCodeOptions(codes, Date.parse('2026-11-01T00:00:00Z')).map((c) => c.code)).toEqual(['STD', 'FUTURE', 'REDUCED'])
  })

  it('適用開始日時が未確定（null）なら選択肢は空', () => {
    expect(buildTaxCodeOptions(codes, null)).toEqual([])
  })

  it('同じ code の複数版は、その時点で有効な1件に絞る', () => {
    const versions = [
      { ...base, id: 'a', code: 'STD', rateBasisPoints: 800, validFrom: '2025-01-01T00:00:00Z', validUntil: '2026-10-01T00:00:00Z' },
      { ...base, id: 'b', code: 'STD', rateBasisPoints: 1000, validFrom: '2026-10-01T00:00:00Z', validUntil: null },
    ]
    const result = buildTaxCodeOptions(versions, at)
    expect(result).toHaveLength(1)
    expect(result[0]!.rateBasisPoints).toBe(1000)
  })
})

describe('isSelectableOption（送信前の存在検証）', () => {
  it('選択肢に含まれる値だけ true。空・前後空白・大文字小文字違いは false', () => {
    expect(isSelectableOption('FULL', ['FREE', 'FULL'])).toBe(true)
    expect(isSelectableOption('ADVANCED', ['FREE', 'FULL'])).toBe(false)
    expect(isSelectableOption('', ['FREE'])).toBe(false)
    expect(isSelectableOption(' FULL', ['FULL'])).toBe(false)
    expect(isSelectableOption('full', ['FULL'])).toBe(false)
    expect(isSelectableOption(null, ['FULL'])).toBe(false)
  })
})
