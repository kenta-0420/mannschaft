// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createI18n } from 'vue-i18n'
import OrganizationPage from './member-profiles.vue'
import TeamPage from '~/pages/teams/[slug]/member-profiles.vue'
import ja from '~/locales/ja/common.json'
import en from '~/locales/en/common.json'
import zh from '~/locales/zh/common.json'
import ko from '~/locales/ko/common.json'
import es from '~/locales/es/common.json'
import de from '~/locales/de/common.json'

const boundary = vi.hoisted(() => ({
  admin: true,
  listPages: vi.fn(), createPage: vi.fn(), success: vi.fn(), error: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'guidance-scope' } }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdmin: ref(boundary.admin), loadPermissions: vi.fn() }))
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization: vi.fn(async () => ({ data: { numericId: 12 } })) }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: vi.fn(async () => ({ data: { numericId: 12 } })) }))
mockNuxtImport('useMemberProfileApi', () => () => boundary)
mockNuxtImport('useNotification', () => () => ({ success: boundary.success, error: boundary.error }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

// 翻訳関数・対象ページ・辞書は実物。入力/ボタンstubは通常DOMイベントを中継する。
const Button = defineComponent({
  props: { label: String, disabled: Boolean }, emits: ['click'],
  setup: (props, { emit }) => () => h('button', {
    disabled: props.disabled, onClick: () => emit('click'),
  }, props.label),
})
const InputText = defineComponent({
  props: { modelValue: String }, emits: ['update:modelValue'],
  setup: (props, { emit }) => () => h('input', {
    value: props.modelValue,
    onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
  }),
})
const Dialog = defineComponent({
  props: { visible: Boolean },
  setup: (props, { slots }) => () => props.visible
    ? h('section', { role: 'dialog' }, [slots.default?.(), slots.footer?.()]) : null,
})
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const messages = { ja, en, zh, ko, es, de }
const hintKey = 'memberProfile.pages.slugHint'
const scopes = [
  { name: 'organization', component: OrganizationPage, scopeField: 'organizationId' },
  { name: 'team', component: TeamPage, scopeField: 'teamId' },
] as const

async function render(scope: typeof scopes[number]) {
  const i18n = createI18n({ legacy: false, locale: 'ja', fallbackLocale: false, messages })
  const wrapper = mount(scope.component, { global: {
    plugins: [i18n],
    stubs: {
      Button, InputText, Dialog, PageHeader: true, PageLoading: true,
      InputNumber: true, Select: true, Tag: true, MemberProfileList: true,
      MemberProfileFieldManager: true, MemberFieldsManager: true,
      Card: { template: '<article><slot name="content" /></article>' },
    },
  } })
  await flushPromises()
  return { wrapper, i18n }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-28T03:00:00Z'))
  vi.clearAllMocks()
  boundary.admin = true
  boundary.listPages.mockResolvedValue({ data: [] })
  boundary.createPage.mockResolvedValue(undefined)
})
afterEach(() => { vi.useRealTimers() })

describe.each(scopes)('$nameの紹介ページ作成案内（AC1/2/5）', scope => {
  it('実6辞書に案内があり、同じ実ページのja→de→jaで入力近傍に常時表示する', async () => {
    const { wrapper, i18n } = await render(scope)
    await wrapper.findAll('button').find(button => button.text() === i18n.global.t('memberProfile.pages.newPage'))!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    for (const locale of locales) expect(i18n.global.te(hintKey, locale), `${locale}本番辞書の案内`).toBe(true)
    for (const locale of ['ja', 'de', 'ja'] as const) {
      i18n.global.locale.value = locale
      await nextTick()
      const hint = dialog.get('[data-testid="member-page-slug-hint"]')
      expect(hint.isVisible()).toBe(true)
      expect(hint.text()).toBe(i18n.global.t(hintKey))
      expect(hint.text().trim()).not.toBe('')
      expect(hint.text()).not.toBe(hintKey)
      expect(dialog.get('input[placeholder="members-2026"]').element.parentElement?.contains(hint.element)).toBe(true)
    }
    expect(boundary.listPages).toHaveBeenCalledTimes(1)
    expect(boundary.createPage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('title空・slug空では保存できず、有効入力を既存payloadで1回だけ送る', async () => {
    const { wrapper, i18n } = await render(scope)
    await wrapper.findAll('button').find(button => button.text() === i18n.global.t('memberProfile.pages.newPage'))!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    const inputs = dialog.findAll('input')
    const title = inputs[0]!
    const slug = dialog.get('input[placeholder="members-2026"]')
    const save = () => dialog.findAll('button').find(button => button.text() === i18n.global.t('button.save'))!
    expect((save().element as HTMLButtonElement).disabled).toBe(true)
    await title.setValue('紹介ページ')
    expect((save().element as HTMLButtonElement).disabled).toBe(true)
    await save().trigger('click')
    expect(boundary.createPage).not.toHaveBeenCalled()
    await title.setValue('')
    await slug.setValue('members-2026')
    expect((save().element as HTMLButtonElement).disabled).toBe(true)
    await title.setValue('紹介ページ')
    expect((save().element as HTMLButtonElement).disabled).toBe(false)
    await save().trigger('click')
    await flushPromises()
    expect(boundary.createPage).toHaveBeenCalledExactlyOnceWith({
      [scope.scopeField]: 12, title: '紹介ページ', slug: 'members-2026',
      pageType: 'YEARLY', year: 2026, visibility: 'MEMBERS_ONLY',
    })
    expect(boundary.listPages).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('作成失敗では入力とdialogを維持し、再試行時だけもう1回送る', async () => {
    boundary.createPage.mockRejectedValueOnce(new Error('controlled-create-failure'))
    const { wrapper, i18n } = await render(scope)
    await wrapper.findAll('button').find(button => button.text() === i18n.global.t('memberProfile.pages.newPage'))!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    await dialog.findAll('input')[0]!.setValue('紹介ページ')
    await dialog.get('input[placeholder="members-2026"]').setValue('members-2026')
    const save = dialog.findAll('button').find(button => button.text() === i18n.global.t('button.save'))!
    await save.trigger('click')
    await flushPromises()
    expect(boundary.error).toHaveBeenCalledOnce()
    expect(boundary.success).not.toHaveBeenCalled()
    expect((dialog.findAll('input')[0]!.element as HTMLInputElement).value).toBe('紹介ページ')
    expect((dialog.get('input[placeholder="members-2026"]').element as HTMLInputElement).value).toBe('members-2026')
    expect(boundary.listPages).toHaveBeenCalledTimes(1)
    await save.trigger('click')
    await flushPromises()
    expect(boundary.createPage).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('非ADMINには新規ページ作成入口を表示しない（既存UI境界）', async () => {
    boundary.admin = false
    const { wrapper, i18n } = await render(scope)
    expect(wrapper.findAll('button').some(button => button.text() === i18n.global.t('memberProfile.pages.newPage'))).toBe(false)
    expect(boundary.createPage).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
