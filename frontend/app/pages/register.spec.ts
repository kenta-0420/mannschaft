import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import RegisterPage from './register.vue'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  navigate: vi.fn(),
  query: {} as Record<string, string>,
}))
const values = {
  email: 'invite@example.test',
  password: 'Password123!',
  lastName: '招待',
  firstName: 'テスト',
  displayName: '招待',
  postalCode: '1000001',
  birthDate: '2000-01-01',
  privacyPolicyAccepted: true,
  termsAccepted: true,
}
vi.mock('vee-validate', () => ({
  useForm: () => ({
    errors: {},
    defineField: (name: keyof typeof values) => [ref(values[name]), {}],
    handleSubmit: (submit: (value: typeof values) => Promise<void>) => () => submit(values),
  }),
}))
mockNuxtImport('useRoute', () => () => ({ query: mocks.query }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useAuthApi', () => () => ({ getGoogleAuthUrl: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useHydrated', () => () => ref(true))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('usePostalCodeValidation', () => () => ({
  ensureLoaded: vi.fn().mockResolvedValue(undefined),
  isSupported: () => true,
  validateFormat: () => true,
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
const options = {
  global: {
    stubs: {
      Button: true,
      InputText: true,
      Password: true,
      Checkbox: true,
      DatePicker: true,
      Dialog: true,
      TermsOfServiceModal: true,
      PrivacyPolicyModal: true,
      NuxtLink: {
        name: 'NuxtLink',
        props: ['to'],
        template: '<a><slot /></a>',
      },
    },
  },
}

describe('招待からの登録と既存β招待の分離', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockResolvedValue({})
    mocks.query = { redirect: '/invite/team-token?folder=7' }
  })
  it('チーム招待をβinviteTokenにせず登録案内とログインに復帰先を渡す', async () => {
    const wrapper = await mountSuspended(RegisterPage, options)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(mocks.api.mock.calls[0]![1].body).not.toHaveProperty('inviteToken')
    expect(mocks.navigate).toHaveBeenCalledWith({
      path: '/verify-email',
      query: { email: values.email, redirect: '/invite/team-token?folder=7' },
    })
    expect(wrapper.findAllComponents({ name: 'NuxtLink' }).at(-1)!.props('to')).toEqual({
      path: '/login',
      query: { redirect: '/invite/team-token?folder=7' },
    })
  })
  it('明示された既存βinviteは従来どおり登録payloadに渡す', async () => {
    mocks.query = { invite: 'beta-token' }
    const wrapper = await mountSuspended(RegisterPage, options)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(mocks.api.mock.calls[0]![1].body.inviteToken).toBe('beta-token')
    expect(mocks.navigate).toHaveBeenCalledWith({
      path: '/verify-email',
      query: { email: values.email },
    })
  })
})
