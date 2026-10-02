import { describe, expect, it } from 'vitest'
import { CALENDAR_LAYER_PALETTE, contrastRatio, relativeLuminance } from './calendarLayerPalette'

/**
 * AC-24: パレット12色それぞれについて、ライト時・ダーク時の指定文字色との
 * **コントラスト比を WCAG 2.1 の相対輝度から実際に計算して** 4.5:1 以上を検証する。
 * 設計書 §3.3 の表の値をハードコードして突き合わせる形は採らない
 * （それでは表そのものが誤っていたときに検出できない。実際、表の指定文字色では
 *  12色中6色が 4.5:1 を下回っていた）。
 */
describe('WCAG 2.1 コントラスト計算', () => {
  it('相対輝度が WCAG の定義値と一致する（白=1・黒=0）', () => {
    expect(relativeLuminance('#FFFFFF')).toBeCloseTo(1, 10)
    expect(relativeLuminance('#000000')).toBeCloseTo(0, 10)
    // 中間色の分岐（sRGB のガンマ補正・閾値 0.03928）を踏む値
    expect(relativeLuminance('#808080')).toBeCloseTo(0.21586, 4)
  })

  it('コントラスト比が WCAG の既知値と一致する（白/黒=21・同色=1）', () => {
    expect(contrastRatio('#FFFFFF', '#000000')).toBeCloseTo(21, 6)
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 6)
    expect(contrastRatio('#DC2626', '#DC2626')).toBeCloseTo(1, 10)
  })

  it('不正な色指定を黙って通さない', () => {
    expect(() => relativeLuminance('DC2626')).toThrow()
    expect(() => relativeLuminance('#FFF')).toThrow()
  })
})

describe('AC-24: レイヤーパレットのコントラスト実測', () => {
  it('12色ちょうどで、すべて #RRGGBB 大文字の一意な値である', () => {
    expect(CALENDAR_LAYER_PALETTE).toHaveLength(12)
    for (const entry of CALENDAR_LAYER_PALETTE) {
      expect(entry.hex).toMatch(/^#[0-9A-F]{6}$/)
    }
    expect(new Set(CALENDAR_LAYER_PALETTE.map(e => e.hex)).size).toBe(12)
  })

  it.each(CALENDAR_LAYER_PALETTE.map(e => [e.name, e] as const))(
    '%s: ライト時・ダーク時ともコントラスト比 4.5:1 以上',
    (_name, entry) => {
      expect(contrastRatio(entry.hex, entry.lightText)).toBeGreaterThanOrEqual(4.5)
      expect(contrastRatio(entry.hex, entry.darkText)).toBeGreaterThanOrEqual(4.5)
    },
  )

  it('意味を持つ固定色（個人予定・reflection・TODO）をパレットに含めない（§3.3）', () => {
    const reserved = ['#22C55E', '#F59E0B', '#6366F1', '#F97316', '#3B82F6']
    for (const hex of reserved) {
      expect(CALENDAR_LAYER_PALETTE.map(e => e.hex)).not.toContain(hex)
    }
  })
})
