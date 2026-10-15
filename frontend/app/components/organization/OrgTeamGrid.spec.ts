// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createI18n } from 'vue-i18n'
import OrgTeamGrid from './OrgTeamGrid.vue'
import ja from '~/locales/ja/org_member_teams.json'
import en from '~/locales/en/org_member_teams.json'
import zh from '~/locales/zh/org_member_teams.json'
import ko from '~/locales/ko/org_member_teams.json'
import es from '~/locales/es/org_member_teams.json'
import de from '~/locales/de/org_member_teams.json'

mockNuxtImport('useScopeLabels', () => () => ({ templateLabel: {} }))

describe('OrgTeamGridの空状態（グループoffのフォールバック）', () => {
  it.each([
    { locale: 'ja', expected: '該当する加盟チームはありません' },
    { locale: 'en', expected: 'No affiliated teams match' },
    { locale: 'zh', expected: '没有符合条件的加盟团队' },
    { locale: 'ko', expected: '해당하는 가맹 팀이 없습니다' },
    { locale: 'es', expected: 'No hay equipos afiliados que coincidan' },
    { locale: 'de', expected: 'Keine passenden beigetretenen Teams' },
  ] as const)('$localeの実メッセージを描画する', ({ locale, expected }) => {
    const i18n = createI18n({ legacy: false, locale, messages: { ja, en, zh, ko, es, de } })
    const wrapper = mount(OrgTeamGrid, { props: { teams: [] }, global: { plugins: [i18n] } })

    expect(wrapper.find('p').text()).toBe(expected)
    expect(wrapper.text()).not.toContain('所属チームはありません')
    wrapper.unmount()
  })
})
