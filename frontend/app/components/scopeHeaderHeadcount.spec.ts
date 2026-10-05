import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import type { TeamResponse } from '~/types/team'
import type { OrgDetail } from '~/composables/useOrgDetail'
import TeamPageHeader from '~/components/team/TeamPageHeader.vue'
import OrgPageHeader from '~/components/organization/OrgPageHeader.vue'

/**
 * チーム・組織ヘッダの人数表示（メンバー／サポーター）の試練（CMP-261004-1943）。
 *
 * - AC-13: 0 は「0」、値が欠落/null は「—」、supporterEnabled=false ならサポーター欄を出さない。
 *   メンバー数も同じ規則（0→「0」、欠落→「—」）で固定する。
 * - AC-14: 「メンバー」「サポーター」「人」の直書きをやめ、実際のロケール辞書
 *   （app/locales/{ja,en,zh,ko,es,de}/common.json）のキーで6言語描画する。
 *
 * キー（推奨・本試練で確定）: `common.scopeShell.memberCount` / `common.scopeShell.supporterCount`。
 * 値は `{count}` を含む単一形の文（複数形の `|` は使わない）。ヘッダは人数部分を
 * `{count}` に差し込んで描画する（`<strong>` 等で囲むのは自由。比較は空白正規化した textContent）。
 *
 * 欄の特定: メンバー欄 `data-testid="scope-header-member-count"`、
 * サポーター欄 `data-testid="scope-header-supporter-count"`（どちらも新設）。
 */

const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
type Locale = typeof LOCALES[number]
const KEY_MEMBER = 'memberCount'
const KEY_SUPPORTER = 'supporterCount'
const MISSING = '—'

type Dict = Record<string, unknown>

function loadScopeShell(locale: Locale): Dict {
  const file = resolve(process.cwd(), `app/locales/${locale}/common.json`)
  const json = JSON.parse(readFileSync(file, 'utf8')) as { common?: { scopeShell?: Dict } }
  return json.common?.scopeShell ?? {}
}

function messageOf(locale: Locale, key: string): string {
  const value = loadScopeShell(locale)[key]
  expect(typeof value, `${locale}/common.json に common.scopeShell.${key} が無い`).toBe('string')
  return value as string
}

function expected(locale: Locale, key: string, count: string): string {
  return normalize(messageOf(locale, key).replace(/\{\s*count\s*\}/g, count))
}

function normalize(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

const stubs = {
  ProfileHeader: { template: '<div><slot /></div>' },
  FavoriteToggleButton: true,
  RoleBadge: true,
  Tag: true,
  Menu: { props: ['model'], template: '<div />' },
  BroadcastWizard: true,
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

interface Counts {
  memberCount?: number | null
  supporterCount?: number | null
  supporterEnabled?: boolean
}

const commonProps = {
  roleName: null,
  isAdmin: false,
  isAdminOrDeputy: false,
  followStatus: 'NONE',
  followLoading: false,
  followPermissionSyncError: false,
  joinRequestStatus: 'UNKNOWN',
  joinRequestLoading: false,
}

async function mountTeam(c: Counts) {
  const team = {
    id: 1,
    numericId: 1,
    slug: 'team-a',
    visibility: { visibility: 'PUBLIC', supporterEnabled: c.supporterEnabled ?? true },
    metadata: 'memberCount' in c ? { memberCount: c.memberCount } : {},
    social: 'supporterCount' in c ? { supporterCount: c.supporterCount } : {},
    location: { template: 'default' },
  } as unknown as TeamResponse
  return mountSuspended(TeamPageHeader, {
    props: { ...commonProps, team, displayName: 'チームA', templateLabel: {} },
    global: { stubs },
  })
}

async function mountOrg(c: Counts) {
  const org = {
    id: 'org-a',
    numericId: 1,
    basicInfo: { name: '組織A' },
    visibility: { visibility: 'PUBLIC', supporterEnabled: c.supporterEnabled ?? true },
    metadata: 'memberCount' in c ? { memberCount: c.memberCount } : {},
    social: 'supporterCount' in c ? { supporterCount: c.supporterCount } : {},
  } as unknown as OrgDetail
  return mountSuspended(OrgPageHeader, {
    props: { ...commonProps, org, orgId: 'org-a', ancestors: [] },
    global: { stubs },
  })
}

const HEADERS = [
  ['TeamPageHeader', mountTeam],
  ['OrgPageHeader', mountOrg],
] as const

function memberEl(wrapper: VueWrapper) {
  return wrapper.find('[data-testid="scope-header-member-count"]')
}
function supporterEl(wrapper: VueWrapper) {
  return wrapper.find('[data-testid="scope-header-supporter-count"]')
}

async function switchLocale(wrapper: VueWrapper, locale: Locale) {
  const i18n = wrapper.vm.$i18n as { locale: string, setLocale?: (l: string) => Promise<void> }
  if (i18n.setLocale) await i18n.setLocale(locale)
  else i18n.locale = locale
  for (let i = 0; i < 5; i++) await wrapper.vm.$nextTick()
}

describe('ロケール辞書に人数表示のキーがある（AC-14・CMP-261004-1943）', () => {
  it.each(LOCALES)('%s: common.scopeShell.memberCount / supporterCount が {count} を含む文字列である', (locale) => {
    for (const key of [KEY_MEMBER, KEY_SUPPORTER]) {
      const msg = messageOf(locale, key)
      expect(msg).toMatch(/\{\s*count\s*\}/)
      expect(msg).not.toContain('|')
    }
  })

  it('en の人数表示キーに日本語（メンバー・サポーター・人）が含まれない', () => {
    for (const key of [KEY_MEMBER, KEY_SUPPORTER]) {
      expect(messageOf('en', key)).not.toMatch(/メンバー|サポーター|人/)
    }
  })
})

describe.each(HEADERS)('%s の人数表示（AC-13・CMP-261004-1943）', (_name, mount) => {
  it('AC-13: サポーター 0 は「0」で表示する', async () => {
    const wrapper = await mount({ memberCount: 3, supporterCount: 0 })
    expect(normalize(supporterEl(wrapper).text())).toBe(expected('ja', KEY_SUPPORTER, '0'))
  })

  it('AC-13: サポーター数が欠落していれば「—」で表示する', async () => {
    const wrapper = await mount({ memberCount: 3 })
    expect(normalize(supporterEl(wrapper).text())).toBe(expected('ja', KEY_SUPPORTER, MISSING))
  })

  it('AC-13: サポーター数が null なら「—」で表示する', async () => {
    const wrapper = await mount({ memberCount: 3, supporterCount: null })
    expect(normalize(supporterEl(wrapper).text())).toBe(expected('ja', KEY_SUPPORTER, MISSING))
  })

  it('AC-13: supporterEnabled=false ならサポーター欄を出さない（メンバー欄は出る）', async () => {
    const wrapper = await mount({ memberCount: 3, supporterCount: 2, supporterEnabled: false })
    expect(supporterEl(wrapper).exists()).toBe(false)
    expect(memberEl(wrapper).exists()).toBe(true)
  })

  it('AC-13: メンバー 0 は「0」で表示する', async () => {
    const wrapper = await mount({ memberCount: 0, supporterCount: 0 })
    expect(normalize(memberEl(wrapper).text())).toBe(expected('ja', KEY_MEMBER, '0'))
  })

  it('AC-13: メンバー数が欠落していれば「—」で表示する', async () => {
    const wrapper = await mount({ supporterCount: 0 })
    expect(normalize(memberEl(wrapper).text())).toBe(expected('ja', KEY_MEMBER, MISSING))
  })

  it('AC-14: 6言語それぞれ実際の辞書の文言で人数を描画し、en では日本語が出ない', async () => {
    const wrapper = await mount({ memberCount: 3, supporterCount: 7 })
    try {
      for (const locale of LOCALES) {
        await switchLocale(wrapper, locale)
        await vi.waitFor(() => {
          expect(normalize(memberEl(wrapper).text())).toBe(expected(locale, KEY_MEMBER, '3'))
          expect(normalize(supporterEl(wrapper).text())).toBe(expected(locale, KEY_SUPPORTER, '7'))
        }, { timeout: 3000 })
        if (locale === 'en') {
          const text = memberEl(wrapper).text() + supporterEl(wrapper).text()
          expect(text).not.toMatch(/メンバー|サポーター|人/)
        }
      }
    }
    finally {
      await switchLocale(wrapper, 'ja')
    }
  }, 60000)
})
