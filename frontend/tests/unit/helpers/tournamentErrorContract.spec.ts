// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { tokenizeJava } from './beAbsenceReasons'
import { flattenStrings } from './friendI18nContract'
import { tournamentErrorCases, tournamentErrorTranslations } from './tournamentErrorContract'

const read = (path: string) =>
  readFileSync(resolve(process.cwd(), path), 'utf8').replace(/^\uFEFF/, '')
const enumPath = 'backend/src/main/java/com/mannschaft/app/tournament/TournamentErrorCode.java'
const parseCodes = (source: string): Record<string, string> => {
  const tokens = tokenizeJava(source)
  const codes: Record<string, string> = {}
  for (let index = 0; index < tokens.length - 2; index++) {
    const token = tokens[index]!
    if (
      token.kind === 'id' &&
      tokens[index + 1]?.text === '(' &&
      tokens[index + 2]?.kind === 'str'
    ) {
      codes[token.text] = JSON.parse(tokens[index + 2]!.text) as string
    }
  }
  if (Object.keys(codes).length === 0) throw new Error('BE enumのコード抽出が空です')
  return codes
}

describe('CMP-261002-0743 大会エラーのBE契約', () => {
  it('T1 コメントの偽宣言を無視し、空抽出を失敗にする', () => {
    expect(parseCodes('enum E { REAL("TOUR_019"); /* FAKE("TOUR_999") */ }')).toEqual({
      REAL: 'TOUR_019',
    })
    expect(() => parseCodes('enum E { /* FAKE("TOUR_999") */ }')).toThrow('抽出が空')
  })

  it('T1 BEの定数名と全10コードの意味が一致する', () => {
    const codes = parseCodes(read('../' + enumPath))
    for (const [name, code] of tournamentErrorCases) expect(codes[name], name).toBe(code)
    expect(
      Object.values(codes)
        .filter((code) => /^TOUR_0(19|2[0-8])$/.test(code))
        .sort(),
    ).toEqual(tournamentErrorCases.map(([, code]) => code))
  })

  it.each(
    Object.keys(tournamentErrorTranslations) as Array<keyof typeof tournamentErrorTranslations>,
  )('T1 %sの全10文言がBEの意味と1対1で対応する', (locale) => {
    const messages = flattenStrings(JSON.parse(read(`app/locales/${locale}/tournament.json`)))
    tournamentErrorCases.forEach(([, , key], index) => {
      expect(messages[key], key).toBe(tournamentErrorTranslations[locale][index])
    })
  })

  it.each(['pull_request', 'push'])('T6 %sでBE enum単独変更もFE CIを起動する', (event) => {
    const workflow = read('../.github/workflows/frontend-ci.yml')
    const start = workflow.indexOf(`  ${event}:`)
    const rest = workflow.slice(start + 2)
    const end = rest.search(/\n(?: {2}[a-z_]+:|[a-z_]+:)/)
    expect(start).toBeGreaterThanOrEqual(0)
    expect(end < 0 ? rest : rest.slice(0, end)).toContain(enumPath)
  })
})
