// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { loadFlywayPermissions } from '../helpers/flywayPermissions'

/**
 * F01.2.1 ALIC-2 番人 — 権限名の表示訳が Flyway の権限カタログと6言語で完全一致すること。
 *
 *   AC-ALIC2-07: 実 Flyway の TEAM/ORGANIZATION 権限（49件以上）と、6言語の
 *   `teamAffiliations.permission_names` が完全一致（漏れ・余り不可）、値は空でない、
 *   キー変換（`.` → `_`）で衝突しない。49件未満なら赤（抽出の取りこぼし）。
 *
 * 権限を Flyway に追加したら、ここが赤になり FE の訳の追加を要求する（security README §4.3）。
 * キー変換は表示側 `utils/permissionLabel.ts` と同じ規約（vue-i18n の階層区切り `.` を避ける）。
 */
const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const MIN_EXPECTED = 49

/** 表示側の規約: 権限コードの `.` を `_` に置き換えてキーにする（jobs.manage → jobs_manage）。 */
const permissionKey = (code: string): string => code.replace(/\./g, '_')

function loadPermissionNames(locale: string): Record<string, unknown> | undefined {
  const path = resolve(__dirname, `../../../app/locales/${locale}/team_affiliations.json`)
  const json = JSON.parse(readFileSync(path, 'utf8')) as { teamAffiliations?: { permission_names?: Record<string, unknown> } }
  return json.teamAffiliations?.permission_names
}

const catalog = loadFlywayPermissions().filter(p => p.scope === 'TEAM' || p.scope === 'ORGANIZATION')
const codes = [...new Set(catalog.map(p => p.name))].sort()

describe('権限名 i18n 網羅番人（AC-ALIC2-07）', () => {
  it(`Flyway の TEAM/ORGANIZATION 権限を ${MIN_EXPECTED} 件以上抽出できる`, () => {
    expect(codes.length).toBeGreaterThanOrEqual(MIN_EXPECTED)
    expect(codes).toEqual(expect.arrayContaining(['MANAGE_SCHEDULES', 'MANAGE_ORG_AFFILIATION', 'jobs.manage', 'BUDGET_VIEW']))
  })

  it('キー変換（. → _）で権限コード同士が衝突しない', () => {
    const keys = codes.map(permissionKey)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it.each(LOCALES)('%s: permission_names が Flyway の権限と完全一致し、値が空でない', (locale) => {
    const names = loadPermissionNames(locale)
    expect(names, `${locale}/team_affiliations.json に teamAffiliations.permission_names が無い`).toBeTypeOf('object')
    const expected = codes.map(permissionKey).sort()
    expect(Object.keys(names ?? {}).sort()).toEqual(expected)
    for (const key of expected) {
      const value = names?.[key]
      expect(typeof value === 'string' && value.trim().length > 0, `${locale}: permission_names.${key} が空`).toBe(true)
    }
  })
})
