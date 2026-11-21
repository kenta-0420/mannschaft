import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * CMP-260909-2152 PR-α 試練108 AC31α（6言語キーの欠落0）。
 * 締め日設定（shiftBudget.closingDay.*）・締め期間（shiftBudget.closePeriod.*）・
 * 月次締めの期間化改訂（shiftBudget.monthlyClose.*）・導線キーが 6 言語すべてにあること。
 * ja 以外に日本語（かな）を置かない。実装前はキーが無いため red。
 */

const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

const REQUIRED_KEYS = [
  'shiftBudget.closingDay.title',
  'shiftBudget.closingDay.description',
  'shiftBudget.closingDay.current',
  'shiftBudget.closingDay.endOfMonth',
  'shiftBudget.closingDay.dayOption',
  'shiftBudget.closingDay.dayLabel',
  'shiftBudget.closingDay.effectivePeriod',
  'shiftBudget.closingDay.reason',
  'shiftBudget.closingDay.reasonRequired',
  'shiftBudget.closingDay.previewTitle',
  'shiftBudget.closingDay.previewBefore',
  'shiftBudget.closingDay.previewAfter',
  'shiftBudget.closingDay.submit',
  'shiftBudget.closingDay.success',
  'shiftBudget.closingDay.historyTitle',
  'shiftBudget.closingDay.historyEmpty',
  'shiftBudget.closingDay.readOnly',
  'shiftBudget.closingDay.loadError',
  'shiftBudget.closingDay.conflict',
  'shiftBudget.closePeriod.label',
  'shiftBudget.closePeriod.select',
  'shiftBudget.closePeriod.empty',
  'shiftBudget.closePeriod.notEnded',
  'shiftBudget.monthlyClose.period',
  'shiftBudget.monthlyClose.periodHint',
  'shiftBudget.monthlyClose.confirm',
  'shiftBudget.monthlyClose.success',
  'shiftBudget.nav.closingSettings',
  'shiftBudget.dashboard.viewClosingSettings',
] as const

/** 期間化で廃止する自由入力の月キー。 */
const REMOVED_KEYS = ['shiftBudget.monthlyClose.yearMonth', 'shiftBudget.monthlyClose.yearMonthHint'] as const

function load(locale: string): Record<string, unknown> {
  const file = resolve(process.cwd(), 'app', 'locales', locale, 'shiftBudget.json')
  return JSON.parse(readFileSync(file, 'utf8')) as Record<string, unknown>
}

function flatten(value: unknown, prefix = ''): Record<string, string> {
  if (typeof value === 'string') return { [prefix]: value }
  if (!value || typeof value !== 'object') return {}
  return Object.entries(value).reduce<Record<string, string>>(
    (acc, [k, v]) => ({ ...acc, ...flatten(v, prefix ? `${prefix}.${k}` : k) }),
    {},
  )
}

const KANA = /[぀-ヿ]/

describe.each(LOCALES)('試練108 AC31α 締め日・締め期間の6言語キー (%s)', (locale) => {
  const flat = flatten(load(locale))

  it('必要キーがすべて存在し空でない', () => {
    const missing = REQUIRED_KEYS.filter(k => !flat[k] || flat[k].trim() === '')
    expect(missing).toEqual([])
  })

  it('期間化で廃止した自由入力キーが残っていない', () => {
    expect(REMOVED_KEYS.filter(k => k in flat)).toEqual([])
  })

  it('補間変数が揃っている（dayOption={day}、closePeriod.label={start}/{end}、monthlyClose は {period}・{month} は使わない）', () => {
    expect(flat['shiftBudget.closingDay.dayOption']).toContain('{day}')
    expect(flat['shiftBudget.closePeriod.label']).toContain('{start}')
    expect(flat['shiftBudget.closePeriod.label']).toContain('{end}')
    expect(flat['shiftBudget.monthlyClose.confirm']).toContain('{period}')
    expect(flat['shiftBudget.monthlyClose.confirm']).not.toContain('{month}')
    expect(flat['shiftBudget.monthlyClose.success']).toContain('{period}')
    expect(flat['shiftBudget.monthlyClose.success']).not.toContain('{month}')
  })

  if (locale !== 'ja') {
    it('ja 以外に日本語（かな）を置かない', () => {
      const offenders = REQUIRED_KEYS.filter(k => KANA.test(flat[k] ?? ''))
      expect(offenders).toEqual([])
    })
  }
})

describe('試練108 AC31α 締め日・締め期間キーの言語間パリティ', () => {
  const jaKeys = Object.keys(flatten(load('ja'))).sort()
  it.each(LOCALES.filter(l => l !== 'ja'))('%s は ja と shiftBudget のキー集合が一致する', (locale) => {
    const keys = Object.keys(flatten(load(locale))).sort()
    expect({ missing: jaKeys.filter(k => !keys.includes(k)), extra: keys.filter(k => !jaKeys.includes(k)) })
      .toEqual({ missing: [], extra: [] })
  })
})
