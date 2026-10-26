import { describe, it, expect } from 'vitest'
import { generateSlug, isSlugFormatValid, isValidSlug } from '~/utils/slug'

/**
 * slug ユーティリティのユニットテスト（村方式 slug 入力 UX）。
 *
 * - generateSlug: 名前 → slug 自動提案（小文字化・英数字以外をハイフン・前後ハイフン除去）
 * - isSlugFormatValid: BE #1538 制約（^[a-z0-9-]{3,30}$ + 先頭末尾/連続ハイフン不可）の厳密判定
 */
describe('generateSlug', () => {
  it('小文字化し、空白・記号をハイフンに変換する', () => {
    expect(generateSlug('My Team')).toBe('my-team')
    expect(generateSlug('みどり Team!! 2026')).toBe('team-2026')
  })

  it('英数字以外の連続は 1 つのハイフンに畳む（連続ハイフンを生まない）', () => {
    expect(generateSlug('a   b___c')).toBe('a-b-c')
    expect(generateSlug('a---b')).toBe('a-b')
  })

  it('先頭・末尾のハイフンを除去する', () => {
    expect(generateSlug('  Hello World  ')).toBe('hello-world')
    expect(generateSlug('--abc--')).toBe('abc')
  })

  it('30 文字に切り詰める', () => {
    const long = 'a'.repeat(50)
    expect(generateSlug(long)).toHaveLength(30)
  })

  it('生成結果が 3 文字未満なら "team" を返す', () => {
    expect(generateSlug('!!')).toBe('team')
    expect(generateSlug('あ')).toBe('team')
    expect(generateSlug('ab')).toBe('team')
  })
})

describe('isSlugFormatValid', () => {
  it('有効な slug を true と判定する', () => {
    expect(isSlugFormatValid('my-team')).toBe(true)
    expect(isSlugFormatValid('abc')).toBe(true)
    expect(isSlugFormatValid('team-2026')).toBe(true)
    expect(isSlugFormatValid('a'.repeat(30))).toBe(true)
  })

  it('3 文字未満 / 30 文字超は false', () => {
    expect(isSlugFormatValid('ab')).toBe(false)
    expect(isSlugFormatValid('a'.repeat(31))).toBe(false)
  })

  it('英小文字・数字・ハイフン以外を含むと false', () => {
    expect(isSlugFormatValid('My-Team')).toBe(false)
    expect(isSlugFormatValid('my_team')).toBe(false)
    expect(isSlugFormatValid('みどり')).toBe(false)
  })

  it('先頭・末尾ハイフンは false', () => {
    expect(isSlugFormatValid('-abc')).toBe(false)
    expect(isSlugFormatValid('abc-')).toBe(false)
  })

  it('連続ハイフンは false', () => {
    expect(isSlugFormatValid('a--b')).toBe(false)
    expect(isSlugFormatValid('my--team')).toBe(false)
  })
})

describe('isValidSlug（既存ユーティリティの回帰確認）', () => {
  it('3〜30 文字の英数字・ハイフンを許可する', () => {
    expect(isValidSlug('abc')).toBe(true)
    expect(isValidSlug('my-team')).toBe(true)
  })
})
