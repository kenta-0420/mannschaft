// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { budgetFiscalYearSchema } from './budgetFiscalYearForm'

const schema = budgetFiscalYearSchema((key) => key)
const valid = { name: '2026年度', startDate: new Date(2026, 3, 1), endDate: new Date(2027, 2, 31) }
describe('会計年度入力', () => {
  it('年度名の前後空白を除去し100文字まで受け付ける', () => {
    expect(schema.parse({ ...valid, name: '  年度  ' }).name).toBe('年度')
    expect(schema.safeParse({ ...valid, name: 'あ'.repeat(100) }).success).toBe(true)
  })
  it.each(['', '   ', 'あ'.repeat(101)])('空白または上限超過の年度名を拒否する', (name) => {
    expect(schema.safeParse({ ...valid, name }).success).toBe(false)
  })
  it.each(['startDate', 'endDate'] as const)('必須日付%sの欠落を拒否する', (key) => {
    expect(schema.safeParse({ ...valid, [key]: null }).success).toBe(false)
    expect(schema.safeParse({ ...valid, [key]: undefined }).success).toBe(false)
    expect(schema.safeParse({ ...valid, [key]: new Date('invalid') }).success).toBe(false)
  })
  it.each([new Date(2026, 3, 1), new Date(2026, 2, 31)])(
    '終了日が同日または前日なら拒否する',
    (endDate) => {
      expect(schema.safeParse({ ...valid, endDate }).success).toBe(false)
    },
  )
})
