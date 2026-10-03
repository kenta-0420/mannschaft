// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { normalizeRanchName, isRanchNameValid } from './ranch-name'
describe('AC61 命名の書記素・正規化', () => {
  it('前後空白を除きNFCへ正規化する', () => expect(normalizeRanchName('  e\u0301  ')).toBe('é'))
  it('10書記素を許可し11書記素を拒否する', () => { expect(isRanchNameValid('🦕'.repeat(10))).toBe(true); expect(isRanchNameValid('🦕'.repeat(11))).toBe(false) })
  it('保存上限512byte・160codepointを超えた書記素を切り詰めず拒否する', () => {
    expect(isRanchNameValid('a' + '\u0338'.repeat(159))).toBe(true)
    expect(isRanchNameValid('a' + '\u0338'.repeat(160))).toBe(false)
    expect(isRanchNameValid('a' + '\u{1D165}'.repeat(128))).toBe(false)
  })
  it.each(['', '  ', '\n恐竜', '\u200b', '恐\u0000竜'])('空・不可視・制御を拒否 %j', value => expect(isRanchNameValid(value)).toBe(false))
})
