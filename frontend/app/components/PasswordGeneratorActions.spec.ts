// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, createSSRApp, defineComponent, nextTick, ref } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createI18n, useI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import Button from 'primevue/button'
import Password from 'primevue/password'
import Actions from './PasswordGeneratorActions.vue'
import SettingsPasswordSection from './settings/SettingsPasswordSection.vue'
import SettingsPasswordChange from './settings/SettingsPasswordChange.vue'
import { useHydrated } from '~/composables/useHydrated'
import { countCharTypes, meetsPasswordPolicy } from '~/utils/passwordPolicy'
import ja from '~/locales/ja/settings.json'
import en from '~/locales/en/settings.json'
import zh from '~/locales/zh/settings.json'
import ko from '~/locales/ko/settings.json'
import es from '~/locales/es/settings.json'
import de from '~/locales/de/settings.json'

const mocks = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  changePassword: vi.fn(),
  redirect: vi.fn(),
}))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => mocks }))
vi.mock('~/composables/useUserSettingsApi', () => ({ useUserSettingsApi: () => mocks }))
vi.mock('~/composables/usePasswordChangeRedirect', () => ({
  usePasswordChangeRedirect: () => ({ redirecting: ref(false), triggerRedirect: mocks.redirect }),
}))

const messages = { ja, en, zh, ko, es, de }
const labels = {
  ja: ['パスワードを生成', 'コピー'],
  en: ['Generate password', 'Copy'],
  zh: ['生成密码', '复制'],
  ko: ['비밀번호 생성', '복사'],
  es: ['Generar contraseña', 'Copiar'],
  de: ['Passwort generieren', 'Kopieren'],
}
function i18n(locale: keyof typeof messages = 'ja') {
  return createI18n({ legacy: false, locale, messages })
}
function globalOptions(locale: keyof typeof messages = 'ja') {
  return {
    plugins: [i18n(locale), PrimeVue],
    components: { Button, Password, PasswordGeneratorActions: Actions },
    stubs: { SectionCard: { template: '<section><slot /></section>' } },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.changePassword.mockResolvedValue({ data: {} })
  mocks.redirect.mockResolvedValue(undefined)
  vi.stubGlobal('useNotification', () => mocks)
  vi.stubGlobal('useUserSettingsApi', () => mocks)
  vi.stubGlobal('usePasswordChangeRedirect', () => ({
    redirecting: ref(false),
    triggerRedirect: mocks.redirect,
  }))
  vi.stubGlobal('useI18n', useI18n)
  vi.stubGlobal('useHydrated', useHydrated)
  vi.stubGlobal('ref', ref)
  vi.stubGlobal('computed', computed)
  vi.stubGlobal('countCharTypes', countCharTypes)
  vi.stubGlobal('meetsPasswordPolicy', meetsPasswordPolicy)
  vi.stubGlobal('crypto', { getRandomValues: vi.fn((array: Uint8Array) => array.fill(0)) })
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
})
afterEach(() => vi.unstubAllGlobals())

async function mountActions(password = '', disabled = false) {
  const wrapper = mount(Actions, { props: { password, disabled }, global: globalOptions() })
  await nextTick()
  return wrapper
}

describe('本人用生成・明示コピー AC-5/7/8/9/10/14', () => {
  it('初期表示は乱数・コピー・生成イベントを発生させず空コピーを無効化する', async () => {
    const wrapper = await mountActions()
    expect(crypto.getRandomValues).not.toHaveBeenCalled()
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
    expect(wrapper.emitted('generated')).toBeUndefined()
    expect(wrapper.findAll('button')[1]!.attributes('disabled')).toBeDefined()
    expect(
      wrapper.findAll('button').every((button) => button.attributes('type') === 'button'),
    ).toBe(true)
  })

  it('明示生成時だけ完成値を通知しコピーはしない', async () => {
    const wrapper = await mountActions('ExistingValue1')
    await wrapper.findAll('button')[0]!.trigger('click')
    expect(wrapper.emitted('generated')?.[0]?.[0]).toMatch(/^[A-Za-z0-9]{16}$/)
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
  })

  it('手修正後の現在値をコピーし完了後のみ成功を通知する', async () => {
    const wrapper = await mountActions('InitialValue1')
    await wrapper.setProps({ password: 'EditedValue2' })
    await wrapper.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('EditedValue2')
    expect(mocks.success).toHaveBeenCalledWith('コピーしました')
    expect(mocks.error).not.toHaveBeenCalled()
  })

  it.each(['unavailable', 'throw'] as const)(
    '乱数%sではフォーム更新イベントを出さず失敗通知する',
    async (mode) => {
      const wrapper = await mountActions('KeepValue1')
      vi.stubGlobal(
        'crypto',
        mode === 'unavailable'
          ? undefined
          : {
              getRandomValues: () => {
                throw new Error('unavailable')
              },
            },
      )
      await wrapper.findAll('button')[0]!.trigger('click')
      expect(wrapper.emitted('generated')).toBeUndefined()
      expect(wrapper.props('password')).toBe('KeepValue1')
      expect(mocks.error).toHaveBeenCalledWith(
        'パスワードを生成できませんでした。手入力してください。',
      )
    },
  )

  it.each(['unavailable', 'denied'] as const)(
    'コピー%sでは値を保持し手動コピー案内を出す',
    async (mode) => {
      const wrapper = await mountActions('KeepValue1')
      vi.stubGlobal(
        'navigator',
        mode === 'unavailable'
          ? {}
          : { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } },
      )
      await wrapper.findAll('button')[1]!.trigger('click')
      await flushPromises()
      expect(wrapper.props('password')).toBe('KeepValue1')
      expect(wrapper.emitted('generated')).toBeUndefined()
      expect(mocks.success).not.toHaveBeenCalled()
      expect(mocks.error).toHaveBeenCalledWith(
        'コピーできませんでした。表示を切り替えて手動でコピーしてください。',
      )
    },
  )

  it('送信・遷移中は両操作を無効化する', async () => {
    const wrapper = await mountActions('KeepValue1', true)
    for (const button of wrapper.findAll('button')) {
      expect(button.attributes('disabled')).toBeDefined()
      await button.trigger('click')
    }
    expect(crypto.getRandomValues).not.toHaveBeenCalled()
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
  })

  it('SSRで乱数に触れず操作ボタンを無効化する', async () => {
    const app = createSSRApp(Actions, { password: 'AutofilledValue1' })
    app.use(i18n()).use(PrimeVue).component('Button', Button)
    const html = await renderToString(app)
    expect(crypto.getRandomValues).not.toHaveBeenCalled()
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
    expect((html.match(/ disabled/g) ?? []).length).toBe(2)
  })

  it.each(Object.keys(messages) as (keyof typeof messages)[])(
    '%sの追加文言を実テキストとして表示する',
    async (locale) => {
      const wrapper = mount(Actions, { props: { password: '' }, global: globalOptions(locale) })
      expect(wrapper.text()).toContain(labels[locale][0])
      expect(wrapper.text()).toContain(labels[locale][1])
      expect(wrapper.text()).not.toContain('settings.password.generator.')
    },
  )
})

describe('本人フォームとの接続 AC-4/6/7/12', () => {
  it('親モデルの新規・確認だけを同時更新し現在値・送信を保持、手修正後も現在値をコピーする', async () => {
    const submit = vi.fn()
    const host = defineComponent({
      components: { SettingsPasswordSection },
      setup() {
        const form = ref({
          currentPassword: 'CurrentValue1',
          newPassword: 'OldValue2',
          confirmPassword: 'Different3',
        })
        return { form, submit }
      },
      template: `<form @submit.prevent="submit"><SettingsPasswordSection v-model:password-form="form" :has-password="true" :submitting-password="false" :can-submit-password="true" :password-error="null" /></form>`,
    })
    const wrapper = mount(host, { global: globalOptions() })
    await nextTick()
    await wrapper.findComponent(Actions).findAll('button')[0]!.trigger('click')
    const inputs = wrapper.findAll('input')
    expect(inputs[0]!.element.value).toBe('CurrentValue1')
    expect(inputs[1]!.element.value).toMatch(/^[A-Za-z0-9]{16}$/)
    expect(inputs[2]!.element.value).toBe(inputs[1]!.element.value)
    expect(submit).not.toHaveBeenCalled()
    expect(inputs[0]!.attributes('autocomplete')).toBe('current-password')
    expect(inputs[1]!.attributes('autocomplete')).toBe('new-password')
    expect(inputs[2]!.attributes('autocomplete')).toBe('new-password')
    expect(inputs[1]!.attributes('type')).toBe('password')
    await inputs[1]!.setValue('EditedValue4')
    await wrapper.findComponent(Actions).findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('EditedValue4')
    expect(inputs[2]!.element.value).not.toBe('EditedValue4')
    expect(submit).not.toHaveBeenCalled()
  })

  it('プロフィールの生成値を本人変更APIへ渡し成功後の既存リダイレクトを呼ぶ', async () => {
    const wrapper = mount(SettingsPasswordChange, { global: globalOptions() })
    await nextTick()
    await wrapper.findAll('input')[0]!.setValue('CurrentValue1')
    await wrapper.findComponent(Actions).findAll('button')[0]!.trigger('click')
    const generated = wrapper.findAll('input')[1]!.element.value
    await wrapper.findAll('button').at(-1)!.trigger('click')
    await flushPromises()
    expect(mocks.changePassword).toHaveBeenCalledWith({
      currentPassword: 'CurrentValue1',
      newPassword: generated,
    })
    expect(mocks.redirect).toHaveBeenCalledOnce()
  })
})
