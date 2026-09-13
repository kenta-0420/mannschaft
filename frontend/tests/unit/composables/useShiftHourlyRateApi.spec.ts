// @vitest-environment node
// このテストは純粋な関数と、モックした useApi しか触らないため Nuxt ランタイムを必要としない。
// 既定の environment: 'nuxt' は 1 ファイルごとに Nuxt 環境を構築するため、
// 同時に重いビルドが走っている環境では setup フックが 120 秒でタイムアウトし
// テスト本体が 1 件も実行されない。不要な環境を要求しないことで確実に実行させる。
import { describe, it, expect, vi } from 'vitest'

/**
 * 時給設定まわりの composable ユニットテスト（CMP-260910-1555 / 検分指摘の是正）。
 *
 * 検証観点:
 *   HR-VAL-001: 0 と負数と null を弾く（BE の @Positive と同じ条件）
 *   HR-VAL-002: 正の値は通す
 *   HR-VAL-003: 0 は「1円以上で」の理由キーを返す（0 が ¥1 へ化けないこと）
 *   HR-VAL-004: 未入力は「入力してください」の理由キーを返す
 *   HR-VAL-005: 正の値は理由キーを返さない（保存できる）
 *   HR-UI-001: 時給入力欄がクランプ（:min）を持たない（0 が黙って ¥1 に化けない）
 *   HR-UI-002: 検証メッセージを画面に表示する経路がある（デッドコードでない）
 */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

vi.mock('~/composables/useApi', () => ({
  useApi: () => vi.fn(),
}))

// eslint-disable-next-line import/first
import { isValidHourlyRate, validateHourlyRate } from '~/composables/shift/useShiftHourlyRateApi'

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

describe('validateHourlyRate', () => {
  it('HR-VAL-003: 0 は ratePositive の理由を返す（¥1 に化けず弾かれる）', () => {
    expect(validateHourlyRate(0)).toBe('ratePositive')
    expect(validateHourlyRate(-500)).toBe('ratePositive')
  })

  it('HR-VAL-004: 未入力・NaN は rateRequired の理由を返す', () => {
    expect(validateHourlyRate(null)).toBe('rateRequired')
    expect(validateHourlyRate(Number.NaN)).toBe('rateRequired')
  })

  it('HR-VAL-005: 正の値は理由を返さない（保存できる）', () => {
    expect(validateHourlyRate(1)).toBeNull()
    expect(validateHourlyRate(1200)).toBeNull()
  })
})

/**
 * 時給設定画面のソース不変条件。
 * 実機で「0 を入れるとフォーカスアウトで ¥1 に化け、そのまま保存されてしまう」欠陥が出た原因は
 * `InputNumber :min="1"` のクランプだった。クランプが戻ってきたらここで落ちる。
 */
describe('hourly-rate.vue のソース不変条件', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../../../app/pages/teams/[slug]/settings/hourly-rate.vue', import.meta.url)),
    'utf-8',
  )
  const inputBlock = source.slice(
    source.indexOf('<InputNumber'),
    source.indexOf('/>', source.indexOf('<InputNumber')),
  )

  it('HR-UI-001: 時給入力欄は値をクランプしない（:min を持たない）', () => {
    expect(inputBlock).toContain('data-testid="hourly-rate-input"')
    expect(inputBlock).not.toMatch(/:?min=/)
  })

  it('HR-UI-002: 検証メッセージを欄の下に表示する経路がある', () => {
    expect(source).toContain('data-testid="hourly-rate-error"')
    expect(source).toContain('shift.hourlyRate.validation.')
    expect(source).toContain('validateHourlyRate')
  })
})
