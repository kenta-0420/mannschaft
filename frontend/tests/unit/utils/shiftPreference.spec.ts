import { describe, it, expect } from 'vitest'
import {
  preferenceToI18nKey,
  preferenceToColor,
  preferenceToScore,
} from '~/utils/shiftPreference'
import type { ShiftPreference } from '~/types/shift'

/**
 * F03.5 Phase 1 参: shiftPreference ユーティリティのユニットテスト。
 *
 * 5値（PREFERRED / AVAILABLE / WEAK_REST / STRONG_REST / ABSOLUTE_REST）を網羅する。
 */

const ALL_PREFERENCES: ShiftPreference[] = [
  'PREFERRED',
  'AVAILABLE',
  'WEAK_REST',
  'STRONG_REST',
  'ABSOLUTE_REST',
]

describe('preferenceToI18nKey', () => {
  it('PREFERRED → shift.preference.preferred', () => {
    expect(preferenceToI18nKey('PREFERRED')).toBe('shift.preference.preferred')
  })

  it('AVAILABLE → shift.preference.available', () => {
    expect(preferenceToI18nKey('AVAILABLE')).toBe('shift.preference.available')
  })

  it('WEAK_REST → shift.preference.weakRest', () => {
    expect(preferenceToI18nKey('WEAK_REST')).toBe('shift.preference.weakRest')
  })

  it('STRONG_REST → shift.preference.strongRest', () => {
    expect(preferenceToI18nKey('STRONG_REST')).toBe('shift.preference.strongRest')
  })

  it('ABSOLUTE_REST → shift.preference.absoluteRest', () => {
    expect(preferenceToI18nKey('ABSOLUTE_REST')).toBe('shift.preference.absoluteRest')
  })

  it('全値に対して定義済みの i18n キーを返す', () => {
    for (const p of ALL_PREFERENCES) {
      const key = preferenceToI18nKey(p)
      expect(key).toMatch(/^shift\.preference\./)
      expect(key.length).toBeGreaterThan('shift.preference.'.length)
    }
  })
})

describe('preferenceToColor', () => {
  it('PREFERRED → 緑系クラスを含む', () => {
    expect(preferenceToColor('PREFERRED')).toContain('green')
  })

  it('AVAILABLE → 灰系クラスを含む', () => {
    expect(preferenceToColor('AVAILABLE')).toContain('gray')
  })

  it('WEAK_REST → 黄系クラスを含む', () => {
    expect(preferenceToColor('WEAK_REST')).toContain('yellow')
  })

  it('STRONG_REST → 橙系クラスを含む', () => {
    expect(preferenceToColor('STRONG_REST')).toContain('orange')
  })

  it('ABSOLUTE_REST → 赤系クラスを含む', () => {
    expect(preferenceToColor('ABSOLUTE_REST')).toContain('red')
  })

  it('全値に対して空でない文字列を返す', () => {
    for (const p of ALL_PREFERENCES) {
      expect(preferenceToColor(p)).toBeTruthy()
    }
  })
})

describe('preferenceToScore', () => {
  it('PREFERRED は正のスコア', () => {
    expect(preferenceToScore('PREFERRED')).toBeGreaterThan(0)
  })

  it('AVAILABLE は 0', () => {
    expect(preferenceToScore('AVAILABLE')).toBe(0)
  })

  it('WEAK_REST は負のスコア', () => {
    expect(preferenceToScore('WEAK_REST')).toBeLessThan(0)
  })

  it('STRONG_REST は WEAK_REST より小さいスコア', () => {
    expect(preferenceToScore('STRONG_REST')).toBeLessThan(preferenceToScore('WEAK_REST'))
  })

  it('ABSOLUTE_REST は Number.NEGATIVE_INFINITY', () => {
    expect(preferenceToScore('ABSOLUTE_REST')).toBe(Number.NEGATIVE_INFINITY)
  })

  it('スコア順序: PREFERRED > AVAILABLE > WEAK_REST > STRONG_REST > ABSOLUTE_REST', () => {
    const scores = ALL_PREFERENCES.map(preferenceToScore)
    expect(scores[0]).toBeGreaterThan(scores[1]!)
    expect(scores[1]).toBeGreaterThan(scores[2]!)
    expect(scores[2]).toBeGreaterThan(scores[3]!)
    expect(scores[3]).toBeGreaterThan(scores[4]!)
  })
})
