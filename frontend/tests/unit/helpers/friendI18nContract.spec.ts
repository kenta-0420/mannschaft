// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { DE_BEFRIENDED_ALLOWLIST, flattenStrings, forbiddenBefriended, parseFriendErrorCodes, parseFriendI18nCodes } from './friendI18nContract'

const read = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8').replace(/^\uFEFF/, '')
const BE_PATH = '../backend/src/main/java/com/mannschaft/app/social/SocialErrorCode.java'
const beCodes = parseFriendErrorCodes(read(BE_PATH))
const fixtureCodes = [...Array.from({ length: 13 }, (_, n) => 'SOCIAL_' + (101 + n)), ...Array.from({ length: 6 }, (_, n) => 'SOCIAL_' + (120 + n)), ...Array.from({ length: 5 }, (_, n) => 'SOCIAL_' + (130 + n))].sort()
const javaFixture = (codes: string[]) => 'enum SocialErrorCode {' + codes.map((code, i) => 'FRIEND_CASE_' + i + '("' + code + '", "日本語", Severity.WARN)').join(',') + ';}'

describe('CMP-261014-2035 フレンドi18n契約', () => {
  it('R6 パーサ検体の24件を抽出しコメント中の偽宣言を無視する', () => {
    expect(parseFriendErrorCodes(javaFixture(fixtureCodes) + '/* FRIEND_FAKE("SOCIAL_999") */')).toEqual(fixtureCodes)
  })
  it('R6 パーサ検体へ1件追加すると同一集合ではなくなる', () => {
    const added = parseFriendErrorCodes(javaFixture([...fixtureCodes, 'SOCIAL_999']))
    expect(added).toHaveLength(25)
    expect(added).not.toEqual(fixtureCodes)
  })
  it('R6 BEのFRIEND定数とFEの明示集合が完全一致する', () => {
    expect(beCodes.length, 'BEパス誤り・空抽出を合格にしない').toBeGreaterThan(0)
    const feCodes = parseFriendI18nCodes(read('app/composables/useErrorHandler.ts'))
    expect({ missing: beCodes.filter(code => !feCodes.includes(code)), extra: feCodes.filter(code => !beCodes.includes(code)) }).toEqual({ missing: [], extra: [] })
    expect(feCodes).toHaveLength(new Set(feCodes).size)
  })
  it.each(['ja', 'en', 'zh', 'ko', 'es', 'de'])('R1 %sはBE由来の全FRIENDコードに空でない翻訳を持つ', (locale) => {
    expect(beCodes.length).toBeGreaterThan(0)
    const all: Record<string, string> = {}
    for (const file of readdirSync(resolve(process.cwd(), 'app/locales/' + locale)).filter(file => /\.(json|ts)$/.test(file))) Object.assign(all, flattenStrings(JSON.parse(read('app/locales/' + locale + '/' + file).replace(/^\s*export\s+default\s*/, ''))))
    const missing = beCodes.filter(code => !all['error.' + code]?.trim())
    expect(missing, locale + 'の翻訳欠落').toEqual([])
  })
  it.each(['pull_request', 'push'])('R7 %sのpathsにSocialErrorCodeを含める', (event) => {
    const workflow = read('../.github/workflows/frontend-ci.yml')
    const start = workflow.indexOf('  ' + event + ':')
    const rest = workflow.slice(start + 2)
    const end = rest.search(/\n(?: {2}[a-z_]+:|[a-z_]+:)/)
    const block = end < 0 ? rest : rest.slice(0, end)
    expect(start).toBeGreaterThanOrEqual(0)
    expect(block).toContain('backend/src/main/java/com/mannschaft/app/social/SocialErrorCode.java')
    expect(block).toContain('paths:')
  })

  it.each(['Befreundete Teams', 'befreundeten Teams', 'befreundeter Teams'])('B2 禁止検体%sを検出する', (value) => {
    expect(forbiddenBefriended('friends.json', { friends: { title: value } })).toEqual(['friends.json:friends.title'])
  })
  it.each(Object.entries(DE_BEFRIENDED_ALLOWLIST))('B2 許可キー%sは期待文言だけを許す', (key, value) => {
    const separator = key.indexOf(':')
    const file = key.slice(0, separator)
    const messageKey = key.slice(separator + 1)
    expect(forbiddenBefriended(file, { [messageKey]: value })).toEqual([])
    expect(forbiddenBefriended(file, { [messageKey]: value + ' befreundete Teams' })).toEqual([key])
  })
  it('B2 de全localeのbefreundet派生語はキーと期待文言が一致する2件だけを許す', () => {
    const violations: string[] = []
    let count = 0
    for (const file of readdirSync(resolve(process.cwd(), 'app/locales/de')).filter(file => /\.(json|ts)$/.test(file))) {
      count += 1
      violations.push(...forbiddenBefriended(file, JSON.parse(read('app/locales/de/' + file).replace(/^\s*export\s+default\s*/, ''))))
    }
    expect(count).toBeGreaterThan(0)
    expect(violations).toEqual([])
  })
  it('B1 guideの相互成立引用文は実際の成功メッセージと一字一句一致する', () => {
    const messages = flattenStrings(JSON.parse(read('app/locales/de/friends.json')))
    expect(messages['friends.guide.follow.mutual']).toContain('„' + messages['friends.messages.follow_mutual_success'] + '“')
  })
})
