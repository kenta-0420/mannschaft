import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import SettingsProfileSection from '~/components/settings/SettingsProfileSection.vue'

const ensureLoaded = vi.fn().mockResolvedValue([])

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('usePostalCodeValidation', () => () => ({
  isLoaded: ref(true),
  isSupported: (countryCode: string) => countryCode === 'JP',
  validateFormat: (_countryCode: string, value: string) => /^\d{3}-?\d{4}$/.test(value),
  ensureLoaded,
}))

const stubs = {
  SectionCard: { template: '<section><slot /></section>' },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
  },
  ToggleSwitch: { template: '<input type="checkbox">' },
  Button: {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled"><slot /></button>',
  },
}

function profile(postalCode: string) {
  return {
    nickname: '住民',
    email: 'resident@example.com',
    phoneNumber: '',
    postalCode,
    avatarUrl: null,
    isSearchable: true,
    countryCode: 'JP',
    locale: 'ja',
  }
}

async function mountProfile(postalCode: string) {
  return mountSuspended(SettingsProfileSection, {
    props: { profile: profile(postalCode), savingProfile: false },
    global: { stubs },
  })
}

describe('SettingsProfileSection の郵便番号入力', () => {
  it('郵便番号入力を20文字に制限し、正規形式なら保存できる', async () => {
    const wrapper = await mountProfile('123-4567')

    expect(wrapper.find('input[maxlength]').attributes('maxlength')).toBe('20')
    expect(wrapper.findAll('button').at(-1)?.element.disabled).toBe(false)
  })

  it('対応国で形式外の郵便番号なら保存を無効化する', async () => {
    const wrapper = await mountProfile('123-456')

    expect(wrapper.findAll('button').at(-1)?.element.disabled).toBe(true)
    expect(wrapper.text()).toContain('postal_code_format')
  })
})
