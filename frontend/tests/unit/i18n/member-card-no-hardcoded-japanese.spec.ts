import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * member-card コンポーネントの日本語直書き禁止番人（CMP-261112-2225）。
 *
 * components/member-card/*.vue に UI 文字列の日本語リテラルを直書きすると、
 * 全言語で日本語が表示される。コメント行を除き、かな・カナ・漢字を含む行を検出する。
 * あわせて新設キー memberCardDisplay が 6 言語すべてに存在し、ja 以外に日本語が無いことを保証する。
 */
const here = dirname(fileURLToPath(import.meta.url))
const dir = resolve(here, '../../../app/components/member-card')
const localesDir = resolve(here, '../../../app/locales')
const JA = /[぀-ヿ一-鿿]/
const KANA = /[぀-ヿ]/ // 中国語は漢字を正当に使うため、zh はかなのみ検査する
const COMMENT = /^\s*(\/\/|\*|\/\*|<!--)/

describe('member-card 日本語直書き番人', () => {
  const files = readdirSync(dir).filter((f) => f.endsWith('.vue'))

  it('対象 .vue が存在する', () => {
    expect(files.length).toBeGreaterThan(0)
  })

  for (const f of files) {
    it(`${f} に日本語の直書きが無い`, () => {
      const hits = readFileSync(resolve(dir, f), 'utf-8')
        .split(/\r?\n/)
        .map((l, i) => ({ l, n: i + 1 }))
        .filter(({ l }) => JA.test(l) && !COMMENT.test(l))
        .map(({ l, n }) => `${f}:${n}: ${l.trim()}`)
      expect(hits).toEqual([])
    })
  }
})

describe('memberCardDisplay ロケール', () => {
  const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
  const load = (lang: string) =>
    JSON.parse(readFileSync(resolve(localesDir, lang, 'member-card.json'), 'utf-8')).memberCardDisplay as Record<string, unknown>
  const flat = (o: unknown, p = ''): string[] =>
    o && typeof o === 'object' ? Object.entries(o).flatMap(([k, v]) => flat(v, p ? `${p}.${k}` : k)) : [p]
  const leaves = (o: unknown): string[] =>
    o && typeof o === 'object' ? Object.values(o).flatMap(leaves) : [String(o)]

  for (const lang of LANGS) {
    it(`${lang} は ja とキー集合が一致し空でない`, () => {
      expect(load('ja')).toBeTypeOf('object')
      expect(load(lang)).toBeTypeOf('object')
      expect(flat(load(lang)).sort()).toEqual(flat(load('ja')).sort())
    })
    if (lang !== 'ja') {
      it(`${lang} に日本語が残っていない`, () => {
        expect(leaves(load(lang)).filter((s) => (lang === 'zh' ? KANA : JA).test(s))).toEqual([])
      })
    }
  }
})
