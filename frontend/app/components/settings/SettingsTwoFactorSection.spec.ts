import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsTwoFactorSection from './SettingsTwoFactorSection.vue'

const apiMock = vi.fn()
const notificationMock = { success: vi.fn(), error: vi.fn() }

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useNotification', () => () => notificationMock)

const stubs = {
  SectionCard: { template: '<section><slot /></section>' },
  QrCodeImage: { props: ['value'], template: '<div data-testid="qr-stub" :data-value="value" />' },
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  Tag: { props: ['value'], template: '<span>{{ value }}</span>' },
  Message: { template: '<div><slot /></div>' },
  Button: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  InputOtp: { props: ['modelValue'], template: '<input />' },
  SettingsTwoFactorReauthDialog: { template: '<div data-testid="reauth-stub" />' },
  SettingsBackupCodesDialog: { template: '<div data-testid="backup-stub" />' },
}

async function mountSection() {
  const wrapper = await mountSuspended(SettingsTwoFactorSection, { props: { hasPassword: true }, global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('SettingsTwoFactorSection', () => {
  beforeEach(() => {
    apiMock.mockReset()
    notificationMock.success.mockReset()
    notificationMock.error.mockReset()
  })

  it('AC-5: 未設定ならセットアップ導線を出し、再生成・無効化は出さない', async () => {
    apiMock.mockResolvedValueOnce({ data: { enabled: false, backupCodesRemaining: 0 } })
    const wrapper = await mountSection()
    expect(wrapper.find('[data-testid="two-fa-disabled"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="two-fa-setup-totp"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="two-fa-setup-email"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="two-fa-regenerate"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="two-fa-disable"]').exists()).toBe(false)
  })

  it('AC-5: 有効(TOTP)なら方式と残数、再生成・無効化ボタンを出す', async () => {
    apiMock.mockResolvedValueOnce({ data: { enabled: true, method: 'TOTP', backupCodesRemaining: 1 } })
    const wrapper = await mountSection()
    expect(wrapper.find('[data-testid="two-fa-enabled"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="two-fa-backup-low"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="two-fa-disabled"]').exists()).toBe(false)
  })

  it('AC-2: セットアップ開始で QrCodeImage に otpauth URI を渡し、秘密鍵を表示する', async () => {
    apiMock
      .mockResolvedValueOnce({ data: { enabled: false, backupCodesRemaining: 0 } })
      .mockResolvedValueOnce({ data: { secret: 'JBSWY3DP', qrCodeUrl: 'otpauth://totp/Mannschaft:a?secret=JBSWY3DP' } })
    const wrapper = await mountSection()
    await wrapper.get('[data-testid="two-fa-setup-totp"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="qr-stub"]').attributes('data-value')).toBe('otpauth://totp/Mannschaft:a?secret=JBSWY3DP')
    expect(wrapper.find('[data-testid="two-fa-secret"]').text()).toBe('JBSWY3DP')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('状態取得に失敗したら未設定と誤認させず再試行を出す', async () => {
    apiMock.mockRejectedValueOnce({ response: { status: 500 } })
    const wrapper = await mountSection()
    expect(wrapper.find('[data-testid="two-fa-status-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="two-fa-disabled"]').exists()).toBe(false)
  })
})
