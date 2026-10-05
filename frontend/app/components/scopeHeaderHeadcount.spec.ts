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
  const wrapper = await mountSuspended(TeamPageHeader, {
    props: { ...commonProps, team, displayName: 'チームA', templateLabel: {} },
    global: { stubs },
  })
  // 試練修繕: jsdom は cookie 未設定のため detectBrowserLanguage が navigator.language
  // （既定 en-US）から初期ロケールを決める。かつ i18n は global composer（テストファイル内で
  // 共有）のため、別テストの AC-14 ロケール切替の残骸が後続テストの既定ロケールへ漏れる。
  // AC-13 系は「0/—/supporterEnabled のロジック」を見る試験であり言語に依存しないため、
  // 判定ロジックを緩めずに基準ロケールを nuxt.config の defaultLocale（ja）へ明示的に固定する
  // （実測の env 既定値に期待値を合わせる対処ではなく、既定ロケールへ確定させる根治）。
  await switchLocale(wrapper, 'ja')
  return wrapper
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
  const wrapper = await mountSuspended(OrgPageHeader, {
    props: { ...commonProps, org, orgId: 'org-a', ancestors: [] },
    global: { stubs },
  })
  // mountTeam と同様に基準ロケールを明示的に固定する（上のコメント参照）。
  await switchLocale(wrapper, 'ja')
  return wrapper
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
  // mountTeam/mountOrg が既定ロケールを 'ja'（nuxt.config の defaultLocale）に明示固定している。
  const DEFAULT_LOCALE: Locale = 'ja'

  it('AC-13: サポーター 0 は「0」で表示する', async () => {
    const wrapper = await mount({ memberCount: 3, supporterCount: 0 })
    expect(normalize(supporterEl(wrapper).text())).toBe(expected(DEFAULT_LOCALE, KEY_SUPPORTER, '0'))
  })

  it('AC-13: サポーター数が欠落していれば「—」で表示する', async () => {
    const wrapper = await mount({ memberCount: 3 })
    expect(normalize(supporterEl(wrapper).text())).toBe(expected(DEFAULT_LOCALE, KEY_SUPPORTER, MISSING))
  })

  it('AC-13: サポーター数が null なら「—」で表示する', async () => {
    const wrapper = await mount({ memberCount: 3, supporterCount: null })
    expect(normalize(supporterEl(wrapper).text())).toBe(expected(DEFAULT_LOCALE, KEY_SUPPORTER, MISSING))
  })

  it('AC-13: supporterEnabled=false ならサポーター欄を出さない（メンバー欄は出る）', async () => {
    const wrapper = await mount({ memberCount: 3, supporterCount: 2, supporterEnabled: false })
    expect(supporterEl(wrapper).exists()).toBe(false)
    expect(memberEl(wrapper).exists()).toBe(true)
  })

  it('AC-13: メンバー 0 は「0」で表示する', async () => {
    const wrapper = await mount({ memberCount: 0, supporterCount: 0 })
    expect(normalize(memberEl(wrapper).text())).toBe(expected(DEFAULT_LOCALE, KEY_MEMBER, '0'))
  })

  it('AC-13: メンバー数が欠落していれば「—」で表示する', async () => {
    const wrapper = await mount({ supporterCount: 0 })
    expect(normalize(memberEl(wrapper).text())).toBe(expected(DEFAULT_LOCALE, KEY_MEMBER, MISSING))
  })

  // 試練修繕: 6言語分のロケール JSON を初回切替ごとに lazy import するため、この vitest(nuxt
  // 環境) ではコールド状態で 60000ms を超えうる（実測: 環境負荷により 'zh'/'ko' 付近で変動）。
  // 60000ms で打ち切られると finally のロケール復帰が間に合わず、後続（OrgPageHeader）の
  // describe.each へ途中ロケールが漏れて無関係な AC-13 を落とす。これはロジックの緩和ではなく
  // 同一ロケール切替処理の実測コストに合わせた待ち時間の是正のため、ここで延長する。
  it('AC-14: 6言語それぞれ実際の辞書の文言で人数を描画し、en では日本語が出ない', async () => {
    const wrapper = await mount({ memberCount: 3, supporterCount: 7 })
    try {
      for (const locale of LOCALES) {
        await switchLocale(wrapper, locale)
        await vi.waitFor(() => {
          expect(normalize(memberEl(wrapper).text())).toBe(expected(locale, KEY_MEMBER, '3'))
          expect(normalize(supporterEl(wrapper).text())).toBe(expected(locale, KEY_SUPPORTER, '7'))
        }, { timeout: 15000 })
        if (locale === 'en') {
          const text = memberEl(wrapper).text() + supporterEl(wrapper).text()
          expect(text).not.toMatch(/メンバー|サポーター|人/)
        }
      }
    }
    finally {
      await switchLocale(wrapper, 'ja')
    }
  }, 300000)
})
