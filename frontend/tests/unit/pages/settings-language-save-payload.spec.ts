import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import LanguagePage from '~/pages/settings/language.vue'

/**
 * CMP-261010-1130: 言語設定の保存で、GET /users/me が返した解決済みURL（署名付き avatarUrl）を
 * そのまま PUT /users/me へ戻してはならない（BE が保存キー列へ書き込み、2回目で列長超過の500になる）。
 */
const getProfile = vi.fn()
const updateProfile = vi.fn()
vi.mock('~/composables/useUserSettingsApi', () => ({
  useUserSettingsApi: () => ({ getProfile, updateProfile }),
}))
vi.mock('~/composables/useLocale', () => ({
  useLocale: () => ({ applyAccountLocale: vi.fn().mockResolvedValue(undefined) }),
}))

describe('settings/language.vue 保存ペイロード', () => {
  beforeEach(() => {
    getProfile.mockReset()
    updateProfile.mockReset()
    updateProfile.mockResolvedValue({ data: {} })
    getProfile.mockResolvedValue({
      data: {
        lastName: '山田',
        firstName: '太郎',
        nickname: 'taro',
        isSearchable: true,
        avatarUrl: 'https://minio.example.com/bucket/users/1/icon/a.png?X-Amz-Signature=abc',
        phoneNumber: '090-0000-0000',
        locale: 'ja',
        timezone: 'Asia/Tokyo',
        countryCode: 'JP',
      },
    })
  })

  it('アバターありのユーザーが2回保存しても PUT に avatarUrl を含めない', async () => {
    const wrapper = await mountSuspended(LanguagePage)
    await flushPromises()
    const saveButton = wrapper.findAll('button').find((b) => b.classes().includes('p-button'))
    expect(saveButton).toBeTruthy()

    await saveButton!.trigger('click')
    await flushPromises()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(updateProfile).toHaveBeenCalledTimes(2)
    for (const call of updateProfile.mock.calls) {
      expect(call[0]).not.toHaveProperty('avatarUrl')
    }
  })
})
