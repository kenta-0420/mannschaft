/**
 * V4（CMP-261110-0426）: SecretIssuedDialog は Esc・マスククリックで確認なしに閉じない。
 * 発行直後の秘密を見失わないよう、明示の閉じるボタンのみで閉じる。
 */
import { describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

describe('SecretIssuedDialog.vue 閉じ方の制限', () => {
  it('Esc とマスククリックでは閉じない設定になっている', async () => {
    const SecretIssuedDialog = (await import('~/components/SecretIssuedDialog.vue')).default
    const wrapper = await mountSuspended(SecretIssuedDialog, {
      props: { visible: true, url: 'https://app.example/invite/x' },
    })
    const dialog = wrapper.findComponent({ name: 'Dialog' })
    expect(dialog.exists()).toBe(true)
    expect(dialog.props('closeOnEscape')).toBe(false)
    expect(dialog.props('dismissableMask')).toBe(false)
  })
})
