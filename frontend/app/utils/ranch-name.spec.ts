// @vitest-environment node
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { graphemeSegments } from 'unicode-segmenter/grapheme'
import { isRanchNameValid, normalizeRanchName, ranchNameLength } from './ranch-name'

const rawFixture = readFileSync(new URL('../../../test-fixtures/unicode/17.0.0/GraphemeBreakTest.txt', import.meta.url))
const rawCases = rawFixture.toString('utf8').split('\n').flatMap((line, index) => {
  const body = (line.split('#')[0] ?? '').trim()
  if (!body) return []
  const tokens = body.split(/\s+/u)
  if (tokens[0] !== '÷' || tokens.at(-1) !== '÷') throw new Error(`Invalid raw boundary row ${index + 1}`)
  const codepoints: number[] = []
  const segments: number[][] = []
  let segment: number[] = []
  for (const token of tokens) {
    if (token === '÷') {
      if (segment.length) segments.push(segment)
      segment = []
    } else if (token !== '×') {
      if (!/^[0-9A-F]+$/u.test(token)) throw new Error(`Invalid raw codepoint row ${index + 1}`)
      const codepoint = Number.parseInt(token, 16)
      codepoints.push(codepoint)
      segment.push(codepoint)
    }
  }
  return [{ line: index + 1, codepoints, segments }]
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Unicode 17.0.0 / UAX29 rev47 公式未正規化境界', () => {
  it('COREと同じraw SHAを使用し全766境界行を読む', () => {
    expect(createHash('sha256').update(rawFixture).digest('hex')).toBe('e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec')
    expect(rawCases).toHaveLength(766)
  })
  it.each(rawCases)('公式raw row $line の境界配列を一致させる', ({ codepoints, segments }) => {
    const input = String.fromCodePoint(...codepoints)
    const actual = Array.from(graphemeSegments(input), ({ segment }) => Array.from(segment, character => character.codePointAt(0)))
    expect(actual).toEqual(segments)
  })
  it('Gurmukhi ViramaをGB9c Linkerへ誤分類しない', () => {
    const input = String.fromCodePoint(0x0a15, 0x0a4d, 0x0a15)
    const actual = Array.from(graphemeSegments(input), ({ segment }) => Array.from(segment, character => character.codePointAt(0)))
    expect(actual).toEqual([[0x0a15, 0x0a4d], [0x0a15]])
  })
})

describe('AC61 製品名の正規化と保存上限', () => {
  it('Unicode White_Spaceを端から除きNFCへ正規化する', () => {
    expect(normalizeRanchName(' \u00a0e\u0301\u3000 ')).toBe('é')
    expect(normalizeRanchName('\u1100\u1161\u11a8')).toBe('각')
    expect(normalizeRanchName('\ufeff恐竜')).toBe('\ufeff恐竜')
  })

  it('native Intlが異版でも命名に使わない', () => {
    const nativeSegmenter = vi.spyOn(Intl, 'Segmenter').mockImplementation(function () {
      throw new Error('native Unicode version must not determine the name boundary')
    })
    expect(ranchNameLength('क्ष')).toBe(1)
    expect(isRanchNameValid('क्ष'.repeat(10))).toBe(true)
    expect(nativeSegmenter).not.toHaveBeenCalled()
  })

  it('native Intl.Segmenterのないブラウザでも固定境界を使う', () => {
    vi.stubGlobal('Intl', new Proxy(Intl, {
      get(target, key) { return key === 'Segmenter' ? undefined : Reflect.get(target, key) },
    }))
    expect(ranchNameLength('👨‍👩‍👧‍👦')).toBe(1)
    expect(isRanchNameValid('क्ष'.repeat(10))).toBe(true)
  })

  const productCases = [
    { id: 'empty', value: '', valid: false },
    { id: 'whitespace-only', value: ' \u00a0\u3000 ', valid: false },
    { id: 'control-before-trim', value: '\tひかり', valid: false },
    { id: 'newline-before-trim', value: '\n恐竜', valid: false },
    { id: 'line-separator', value: '恐\u2028竜', valid: false },
    { id: 'nul', value: '恐\u0000竜', valid: false },
    { id: 'unpaired-high-surrogate', value: '\ud800', valid: false },
    { id: 'unpaired-low-surrogate', value: '恐\udc00竜', valid: false },
    { id: 'zwsp-only', value: '\u200b', valid: false },
    { id: 'zwj-only', value: '\u200d', valid: false },
    { id: 'variation-selector-only', value: '\ufe0f', valid: false },
    { id: 'default-ignorable-only', value: '\u034f', valid: false },
    { id: 'bidi-rlo-visible', value: 'ひ\u202eかり', valid: false },
    { id: 'bidi-lrm-visible', value: '恐\u200e竜', valid: false },
    { id: 'bidi-alm-visible', value: '恐\u061c竜', valid: false },
    { id: '10-emoji', value: '🦕'.repeat(10), valid: true },
    { id: '11-emoji', value: '🦕'.repeat(11), valid: false },
    { id: '10-indic', value: 'क्ष'.repeat(10), valid: true },
    { id: '11-indic', value: 'क्ष'.repeat(11), valid: false },
    { id: 'family-zwj', value: '👨‍👩‍👧‍👦', valid: true },
    { id: 'emoji-vs', value: '☀️', valid: true },
    { id: 'regional-indicator-pair', value: '🇯🇵', valid: true },
    { id: 'tag-flag', value: String.fromCodePoint(0x1f3f4, 0xe0067, 0xe0062, 0xe0065, 0xe006e, 0xe0067, 0xe007f), valid: true },
    { id: 'format-with-visible', value: '恐\u200c竜', valid: true },
    { id: '512-byte-boundary', value: '\u{1f468}' + '\u{1d165}'.repeat(127), valid: true },
    { id: '516-byte-overflow', value: '\u{1f468}' + '\u{1d165}'.repeat(128), valid: false },
    { id: '160-codepoint-boundary', value: 'a' + '\u0338'.repeat(159), valid: true },
    { id: '161-codepoint-overflow', value: 'a' + '\u0338'.repeat(160), valid: false },
  ]
  it.each(productCases)('$id は切り詰めず期待通り許可/拒否する', ({ value, valid }) => {
    expect(isRanchNameValid(value)).toBe(valid)
  })
})
