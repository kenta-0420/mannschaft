import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'

/**
 * CMP-261113-1507 AC-16: UI 言語の切替で users.locale が更新される。
 *
 * ログイン済み利用者のエラー文言は BE が users.locale で決める（Accept-Language より優先）。
 * 表示言語だけ切り替わって users.locale が古いままだと、画面は英語・エラーは日本語のように食い違う。
 * ログイン中の言語切替の入口である設定 > 言語（pages/settings/language.vue）の保存が、
 * PUT /api/v1/users/me（useUserSettingsApi.updateProfile）で選んだ locale を送ることを固定する。
 * 未ログインの公開レイアウト（landing / public）の切替は users.locale を持たないため対象外。
 */

const mocks = vi.hoisted(() => ({
  getProfile: vi.fn(),
  updateProfile: vi.fn(),
  applyAccountLocale: vi.fn(),
}))

mockNuxtImport('useUserSettingsApi', () => () => ({
  getProfile: mocks.getProfile,
  updateProfile: mocks.updateProfile,
}))
mockNuxtImport('useLocale', () => () => ({
  locale: ref('ja'),
  locales: ref([]),
  applyAccountLocale: mocks.applyAccountLocale,
}))

const LanguagePage = (await import('~/pages/settings/language.vue')).default

describe('設定 > 言語の保存が users.locale を更新する（AC-16）', () => {
  beforeEach(() => {
    mocks.getProfile.mockReset().mockResolvedValue({ data: {
      lastName: '試練', firstName: '多言語', lastNameKana: null, firstNameKana: null,
      nickname: 'cmp1507', nickname2: null, isSearchable: false, avatarUrl: null, phoneNumber: null,
      locale: 'ja', timezone: 'Asia/Tokyo', countryCode: null,
    } })
    mocks.updateProfile.mockReset().mockResolvedValue({ data: {} })
    mocks.applyAccountLocale.mockReset().mockResolvedValue(undefined)
  })

  it('AC-16: 表示言語を en に切り替えて保存すると updateProfile（PUT /api/v1/users/me）に locale=en が送られ、表示言語にも適用される', async () => {
    const wrapper = await mountSuspended(LanguagePage)
    await flushPromises()

    const selects = wrapper.findAllComponents({ name: 'Select' })
    expect(selects.length).toBeGreaterThan(0)
    selects[0]!.vm.$emit('update:modelValue', 'en')
    await flushPromises()

    const saveButton = wrapper.findAll('button').find(b => b.find('.pi-check').exists())
    expect(saveButton).toBeTruthy()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(mocks.updateProfile).toHaveBeenCalledTimes(1)
    expect(mocks.updateProfile).toHaveBeenCalledWith(expect.objectContaining({ locale: 'en' }))
    expect(mocks.applyAccountLocale).toHaveBeenLastCalledWith('en')
  })
  it('CMP-261120-0200 AC3: 保存で送る内容は locale/timezone/countryCode のみ（avatarUrl・氏名系を送り返さない）', async () => {
    mocks.getProfile.mockResolvedValue({ data: {
      lastName: '試練', firstName: '多言語', lastNameKana: 'シレン', firstNameKana: 'タゲンゴ',
      nickname: 'cmp0200', nickname2: 'n2', isSearchable: true,
      avatarUrl: 'https://signed.example/bucket/user/1/icon/a.png?X-Amz-Signature=abc', phoneNumber: '090',
      locale: 'ja', timezone: 'Asia/Tokyo', countryCode: 'JP',
    } })
    const wrapper = await mountSuspended(LanguagePage)
    await flushPromises()

    const saveButton = wrapper.findAll('button').find(b => b.find('.pi-check').exists())
    expect(saveButton).toBeTruthy()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(mocks.updateProfile).toHaveBeenCalledTimes(1)
    const sent = mocks.updateProfile.mock.calls[0]![0] as Record<string, unknown>
    expect(Object.keys(sent).sort()).toEqual(['countryCode', 'locale', 'timezone'])
  })
})
