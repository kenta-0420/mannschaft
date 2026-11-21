// @vitest-environment node
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * 広告料金カード管理・通知設定ページの i18n 番人（CMP-260925-0902）。
 *
 * - ページに日本語の直書きが無いこと（コメント行を除く）
 * - ページが t()/$t() で参照するリテラルキーが 6 言語すべてに在ること
 * - ja 以外のロケールにかなが混入していないこと
 */
const here = dirname(fileURLToPath(import.meta.url))
const app = resolve(here, '../../../app')
const languages = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const JA = /[぀-ヿ一-鿿]/
const KANA = /[぀-ヿ]/
const COMMENT = /^\s*(\/\/|\*|\/\*|<!--)/

const pages = ['pages/admin/ad-rate-cards.vue', 'pages/settings/notifications.vue']
const localeFiles = ['advertising.json', 'common.json']

type Tree = { [key: string]: Tree | string }

function loadMessages(language: string): Tree {
  const merged: Tree = {}
  for (const file of localeFiles) {
    Object.assign(merged, JSON.parse(readFileSync(resolve(app, 'locales', language, file), 'utf-8')) as Tree)
  }
  return merged
}

function lookup(tree: Tree, key: string): string | undefined {
  let node: Tree | string | undefined = tree
  for (const part of key.split('.')) {
    if (typeof node !== 'object' || node === null) return undefined
    node = node[part]
  }
  return typeof node === 'string' ? node : undefined
}

function referencedKeys(source: string): string[] {
  return [...source.matchAll(/\$?\bt\(\s*'([^']+)'/g)].map(match => match[1]!)
}

describe.each(pages)('%s', (page) => {
  const source = readFileSync(resolve(app, page), 'utf-8')

  it('日本語の直書きが無い', () => {
    const hits = source.split(/\r?\n/)
      .map((line, index) => ({ line, n: index + 1 }))
      .filter(({ line }) => JA.test(line) && !COMMENT.test(line))
      .map(({ line, n }) => `${page}:${n}: ${line.trim()}`)
    expect(hits).toEqual([])
  })

  it.each(languages)('参照キーが %s に定義されている', (language) => {
    const messages = loadMessages(language)
    const keys = referencedKeys(source)
    expect(keys.length).toBeGreaterThan(0)
    expect(keys.filter(key => lookup(messages, key) === undefined)).toEqual([])
  })

  it.each(languages.filter(language => language !== 'ja'))('参照キーの %s 訳にかなが無い', (language) => {
    const messages = loadMessages(language)
    const residue = referencedKeys(source).filter(key => KANA.test(lookup(messages, key) ?? ''))
    expect(residue).toEqual([])
  })
})
