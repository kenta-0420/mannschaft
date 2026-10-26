import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import MemberProfileFieldManager from '~/components/member/MemberProfileFieldManager.vue'

const listFields = vi.fn()
const createField = vi.fn()
const notifyError = vi.fn()

vi.mock('~/composables/useMemberProfileApi', () => ({
  useMemberProfileApi: () => ({ listFields, createField }),
}))

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: notifyError, success: vi.fn() }),
}))

beforeEach(() => {
  vi.clearAllMocks()
  listFields.mockResolvedValue([])
})

describe('メンバー紹介のカスタム項目管理', () => {
  it('チーム画面ではチームIDだけで項目を取得する', async () => {
    await mountSuspended(MemberProfileFieldManager, {
      props: { scopeType: 'team', scopeId: 12 },
    })
    await flushPromises()

    expect(listFields).toHaveBeenCalledWith(12, undefined)
  })

  it('組織画面では組織IDだけで項目を取得する', async () => {
    await mountSuspended(MemberProfileFieldManager, {
      props: { scopeType: 'organization', scopeId: 34 },
    })
    await flushPromises()

    expect(listFields).toHaveBeenCalledWith(undefined, 34)
  })

  it('取得失敗を空の一覧として扱わず、再試行を表示する', async () => {
    listFields.mockRejectedValue(new Error('403'))
    const wrapper = await mountSuspended(MemberProfileFieldManager, {
      props: { scopeType: 'team', scopeId: 12 },
    })
    await flushPromises()

    expect(notifyError).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="member-field-retry"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="member-field-empty"]').exists()).toBe(false)
  })
})
