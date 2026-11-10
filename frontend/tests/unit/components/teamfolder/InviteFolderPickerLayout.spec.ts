/**
 * V7（CMP-261110-0426）: InviteFolderPicker の select が親（モバイル幅）からはみ出さない。
 * flex 子には min-w-0 が必要（select の内在最小幅で押し出されるため）。
 */
import { describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import InviteFolderPicker from '~/components/teamfolder/InviteFolderPicker.vue'

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

describe('InviteFolderPicker レイアウト', () => {
  it('select は min-w-0 と w-full 相当で親に収まる', async () => {
    const wrapper = await mountSuspended(InviteFolderPicker, {
      props: { scopeType: 'TEAM', modelValue: null },
      global: { stubs: { Button: true } },
    })
    const select = wrapper.find('select')
    expect(select.exists()).toBe(true)
    const classes = select.classes()
    expect(classes).toContain('min-w-0')
    expect(classes).toContain('flex-1')
    expect(wrapper.find('[data-testid="invite-folder-picker"]').classes()).toContain('min-w-0')
  })
})
