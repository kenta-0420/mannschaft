// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * AC-G118（FE 側）: F01.2.1 §14.1 の i18n キーが6言語すべてにあり、
 * team_affiliation.json が nuxt.config.ts に6言語とも登録されている。
 */
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

function flatten(value: unknown, prefix = ''): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return prefix ? [prefix] : []
  return Object.entries(value).flatMap(([key, child]) => flatten(child, prefix ? `${prefix}.${key}` : key))
}

function readLocale(locale: string, file: string): Record<string, unknown> {
  const path = resolve(process.cwd(), 'app', 'locales', locale, file)
  // admin_console.json 等は BOM 付きの場合があるため除去する
  const raw = readFileSync(path, 'utf8')
  return JSON.parse(raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw)
}

function keysOf(locale: string, file: string, root?: string): string[] {
  const json = readLocale(locale, file)
  const target = root ? (json[root] as unknown) : json
  return flatten(target, root ?? '').sort()
}

function has(locale: string, file: string, dotted: string): boolean {
  return keysOf(locale, file).includes(dotted)
}

const teamAffiliationKeys = [
  'teamAffiliation.settings.title',
  'teamAffiliation.settings.entry_button',
  'teamAffiliation.settings.application_enabled',
  'teamAffiliation.settings.application_enabled_hint',
  'teamAffiliation.settings.groups_enabled',
  'teamAffiliation.settings.groups_enabled_hint',
  'teamAffiliation.settings.group_mode',
  'teamAffiliation.settings.group_mode_off',
  'teamAffiliation.settings.group_mode_optional',
  'teamAffiliation.settings.group_mode_required',
  'teamAffiliation.settings.guidance',
  'teamAffiliation.settings.turn_off_pending_confirm',
  'teamAffiliation.settings.required_mode_degraded',
  'teamAffiliation.view.teams',
  'teamAffiliation.view.applications',
  'teamAffiliation.view.invites',
  'teamAffiliation.view.restrictions',
  'teamAffiliation.view.groups',
  'teamAffiliation.apply.button',
  'teamAffiliation.apply.dialog_title',
  'teamAffiliation.apply.select_team',
  'teamAffiliation.apply.select_group',
  'teamAffiliation.apply.group_none',
  'teamAffiliation.apply.message',
  'teamAffiliation.apply.submit',
  'teamAffiliation.apply.success',
  'teamAffiliation.apply.expires_hint',
  'teamAffiliation.apply.status.NONE',
  'teamAffiliation.apply.status.APPLYING',
  'teamAffiliation.apply.status.INVITED',
  'teamAffiliation.apply.status.ACTIVE',
  'teamAffiliation.apply.status.UNAVAILABLE',
  'teamAffiliation.apply.find_orgs',
  'teamAffiliation.review.approve',
  'teamAffiliation.review.reject',
  'teamAffiliation.review.approve_dialog_title',
  'teamAffiliation.review.approve_group',
  'teamAffiliation.review.requested_group',
  'teamAffiliation.review.unassigned',
  'teamAffiliation.review.reject_reason',
  'teamAffiliation.review.reject_block',
  'teamAffiliation.review.approved',
  'teamAffiliation.review.rejected',
  'teamAffiliation.invite.button',
  'teamAffiliation.invite.accept',
  'teamAffiliation.invite.decline',
  'teamAffiliation.invite.decline_block',
  'teamAffiliation.invite.cancel',
  'teamAffiliation.restriction.kind_COOLDOWN',
  'teamAffiliation.restriction.kind_BLOCK',
  'teamAffiliation.restriction.lift',
  'teamAffiliation.leave.button',
  'teamAffiliation.leave.confirm',
  'teamAffiliation.remove.button',
  'teamAffiliation.remove.confirm',
  'teamAffiliation.badge_accepting',
  'teamAffiliation.public_page_apply_hint',
  'teamAffiliation.filter_accepting',
  'teamGroup.title',
  'teamGroup.create',
  'teamGroup.name',
  'teamGroup.description',
  'teamGroup.team_count',
  'teamGroup.unassigned',
  'teamGroup.unassigned_count',
  'teamGroup.reorder_hint',
  'teamGroup.delete_confirm',
  'teamGroup.limit_reached',
  'teamGroup.assign',
  'teamGroup.bulk_assign',
  'teamGroup.filter_all',
  'teamGroup.disabled_cta',
  // 8-A: 加盟設定画面・申請ダイアログの追加キー
  'teamAffiliation.apply.load_error',
  'teamAffiliation.apply.submit_error',
  'teamAffiliation.settings.card_desc',
  'teamAffiliation.settings.load_error',
  'teamAffiliation.settings.manage_groups',
  'teamAffiliation.settings.save_error',
  'teamAffiliation.settings.saved',
  'teamAffiliation.settings.section_application',
  'teamAffiliation.settings.section_groups',
  'teamAffiliation.settings.turn_off_confirm',
  'teamAffiliation.settings.turn_off_confirm_ok',
  'teamAffiliation.settings.turn_off_confirm_title',
  // 使い方ガイド（TeamAffiliationGuideModal / TeamAffiliationGuideContent）
  'teamAffiliationGuide.accept.body',
  'teamAffiliationGuide.accept.steps.step1',
  'teamAffiliationGuide.accept.steps.step2',
  'teamAffiliationGuide.accept.steps.step3',
  'teamAffiliationGuide.accept.title',
  'teamAffiliationGuide.description',
  'teamAffiliationGuide.groups.body',
  'teamAffiliationGuide.groups.mode_off',
  'teamAffiliationGuide.groups.mode_optional',
  'teamAffiliationGuide.groups.mode_required',
  'teamAffiliationGuide.groups.title',
  'teamAffiliationGuide.stop.body',
  'teamAffiliationGuide.stop.title',
  'teamAffiliationGuide.title',
].sort()

const announcementKeys = [
  'target_mode_all',
  'target_mode_teams',
  'target_mode_groups',
  'target_groups_label',
  'target_range_label',
  'target_range_up_to',
  'target_range_from',
  'target_range_between',
  'target_range_kind_up_to',
  'target_range_kind_from',
  'target_range_kind_between',
  'target_include_unassigned',
  'target_direct_members_note',
  'target_resolved_count',
  'target_resolved_empty',
  'target_order_hint',
  'push_disabled_note',
  'dynamic_display_note',
  'template_deleted_groups_removed',
  'template_range_invalid',
  'audience_summary',
].map((k) => `announcement.${k}`)

describe('team_affiliation ロケール（AC-G118 FE）', () => {
  it.each(locales)('%s は §14.1 の team_affiliation キーをすべて持つ', (locale) => {
    const keys = keysOf(locale, 'team_affiliation.json')
    expect(keys).toEqual(teamAffiliationKeys)
  })

  it.each(locales)('%s の team_affiliation は日本語と同じキー集合', (locale) => {
    expect(keysOf(locale, 'team_affiliation.json')).toEqual(keysOf('ja', 'team_affiliation.json'))
  })

  it.each(locales)('%s の team_affiliation の値は空文字でなく、生の @ を含まない', (locale) => {
    assertValuesSafe(readLocale(locale, 'team_affiliation.json'), locale, 'team_affiliation.json', 'teamAffiliation')
    assertValuesSafe(readLocale(locale, 'team_affiliation.json'), locale, 'team_affiliation.json', 'teamGroup')
  })
})

/** 今回追加したキー（announcement / common / admin_console）の値が空でなく、生の @ を含まないこと */
const addedKeys: Array<[string, string[]]> = [
  ['announcement.json', announcementKeys],
  ['common.json', ['teamShell.tab.affiliations', 'teamShell.tab.permissionGroups']],
  ['admin_console.json', ['adminConsole.cards.teamAffiliation.title', 'adminConsole.cards.teamAffiliation.desc']],
]

function valueAt(json: Record<string, unknown>, dotted: string): unknown {
  return dotted.split('.').reduce<unknown>((cur, k) => (cur && typeof cur === 'object' ? (cur as Record<string, unknown>)[k] : undefined), json)
}

function assertValuesSafe(json: Record<string, unknown>, locale: string, file: string, root: string): void {
  const walk = (v: unknown): string[] =>
    typeof v === 'string' ? [v] : v && typeof v === 'object' ? Object.values(v).flatMap(walk) : []
  const values = walk(json[root])
  expect(values.length, `${locale}/${file}:${root}`).toBeGreaterThan(0)
  for (const value of values) {
    expect(value.length).toBeGreaterThan(0)
    // Nuxt i18n は "@" をリンク記法として解釈しビルドを壊す。{'@'} でエスケープする
    expect(value.replace(/\{'@'\}/g, '')).not.toContain('@')
  }
}

describe('追加キーの値の健全性（AC-G118 FE）', () => {
  it.each(locales)('%s の追加キーは空文字でなく、生の @ を含まない', (locale) => {
    for (const [file, keys] of addedKeys) {
      const json = readLocale(locale, file)
      for (const key of keys) {
        const value = valueAt(json, key)
        expect(typeof value, `${locale}/${file}:${key}`).toBe('string')
        expect((value as string).length, `${locale}/${file}:${key}`).toBeGreaterThan(0)
        expect((value as string).replace(/\{'@'\}/g, ''), `${locale}/${file}:${key}`).not.toContain('@')
      }
    }
  })
})

describe('nuxt.config.ts への team_affiliation.json 登録（AC-G118 FE）', () => {
  const config = readFileSync(resolve(process.cwd(), 'nuxt.config.ts'), 'utf8')

  /** `code: '<locale>'` のロケール定義に属する files 配列の中身を返す */
  function filesOf(locale: string): string[] {
    const start = config.indexOf(`code: '${locale}'`)
    expect(start, `${locale} のロケール定義`).toBeGreaterThanOrEqual(0)
    const filesStart = config.indexOf('files: [', start)
    const filesEnd = config.indexOf(']', filesStart)
    const nextCode = config.indexOf('code: ', start + 1)
    // files 配列は当該ロケール定義の内側（次の code: より前）になければならない
    if (nextCode >= 0) expect(filesStart).toBeLessThan(nextCode)
    return [...config.slice(filesStart, filesEnd).matchAll(/'([^']+)'/g)].map((m) => m[1] as string)
  }

  it.each(locales)('%s の files に自言語の team_affiliation.json がある', (locale) => {
    const files = filesOf(locale)
    expect(files).toContain(`${locale}/team_affiliation.json`)
    // 他言語のファイルが紛れ込んでいない
    expect(files.filter((f) => f.endsWith('/team_affiliation.json'))).toEqual([`${locale}/team_affiliation.json`])
  })
})

describe('announcement.json のグループ宛てキー（AC-G118 FE）', () => {
  it.each(locales)('%s に §14.1 の announcement キーがすべてある', (locale) => {
    for (const key of announcementKeys) {
      expect(has(locale, 'announcement.json', key), `${locale}: ${key}`).toBe(true)
    }
  })
})

describe('チームシェルのタブと管理コンソールのカード（AC-G118 FE）', () => {
  it.each(locales)('%s の common.json に teamShell.tab.affiliations / permissionGroups がある', (locale) => {
    expect(has(locale, 'common.json', 'teamShell.tab.affiliations')).toBe(true)
    expect(has(locale, 'common.json', 'teamShell.tab.permissionGroups')).toBe(true)
  })

  it.each(locales)('%s の admin_console.json に adminConsole.cards.teamAffiliation.title/desc がある', (locale) => {
    expect(has(locale, 'admin_console.json', 'adminConsole.cards.teamAffiliation.title')).toBe(true)
    expect(has(locale, 'admin_console.json', 'adminConsole.cards.teamAffiliation.desc')).toBe(true)
  })
})
