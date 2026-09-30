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

  it.each(locales)('%s の値は空文字でなく、生の @ を含まない', (locale) => {
    const json = readLocale(locale, 'team_affiliation.json')
    const walk = (v: unknown): string[] =>
      typeof v === 'string' ? [v] : v && typeof v === 'object' ? Object.values(v).flatMap(walk) : []
    for (const value of walk(json)) {
      expect(value.length).toBeGreaterThan(0)
      // Nuxt i18n は "@" をリンク記法として解釈しビルドを壊す。{'@'} でエスケープする
      expect(value.replace(/\{'@'\}/g, '')).not.toContain('@')
    }
  })
})

describe('nuxt.config.ts への team_affiliation.json 登録（AC-G118 FE）', () => {
  const config = readFileSync(resolve(process.cwd(), 'nuxt.config.ts'), 'utf8')
  it.each(locales)('%s/team_affiliation.json が登録されている', (locale) => {
    expect(config).toContain(`'${locale}/team_affiliation.json'`)
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

  it.each(locales)('%s の admin_console.json に adminConsole.card.teamAffiliation(Description) がある', (locale) => {
    expect(has(locale, 'admin_console.json', 'adminConsole.card.teamAffiliation')).toBe(true)
    expect(has(locale, 'admin_console.json', 'adminConsole.card.teamAffiliationDescription')).toBe(true)
  })
})
