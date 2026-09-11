import { describe, it, expect, vi } from 'vitest'

/**
 * 時給設定まわりの composable ユニットテスト（CMP-260910-1555 / 検分指摘の是正）。
 *
 * 検証観点:
 *   HR-VAL-001: 0 と負数と null を弾く（BE の @Positive と同じ条件）
 *   HR-VAL-002: 正の値は通す
 */

vi.mock('~/composables/useApi', () => ({
  useApi: () => vi.fn(),
}))

// eslint-disable-next-line import/first
import { isValidHourlyRate } from '~/composables/shift/useShiftHourlyRateApi'

describe('isValidHourlyRate', () => {
  it('HR-VAL-001: 0・負数・null・NaN は送信させない（BE は @Positive で必ず 400）', () => {
    expect(isValidHourlyRate(0)).toBe(false)
    expect(isValidHourlyRate(-1)).toBe(false)
    expect(isValidHourlyRate(null)).toBe(false)
    expect(isValidHourlyRate(Number.NaN)).toBe(false)
  })

  it('HR-VAL-002: 正の値は送信できる', () => {
    expect(isValidHourlyRate(1)).toBe(true)
    expect(isValidHourlyRate(1200)).toBe(true)
  })
})
