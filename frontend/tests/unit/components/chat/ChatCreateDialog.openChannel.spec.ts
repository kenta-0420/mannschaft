import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import ChatCreateDialog from '~/components/chat/ChatCreateDialog.vue'

/**
 * CMP-261105-1507: Zimmer（部屋）を作成したら、作った部屋の id を openChannel で知らせる
 * （createChannel の応答 id を捨てると、作った部屋が右ペインに開かない）。
 */

const createChannel = vi.fn()
mockNuxtImport('useChatApi', () => () => ({ createChannel, getOrCreateDm: vi.fn() }))
mockNuxtImport('useChatCandidateMembers', () => () => ({ loadCandidates: vi.fn().mockResolvedValue([]) }))
mockNuxtImport('useChatScopeIds', () => () => ({ resolveScopeIds: vi.fn().mockResolvedValue({}) }))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, currentUser: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

const Passthrough = defineComponent({
  setup(_p, { slots }) {
    return () => h('div', slots.default?.())
  },
})

beforeEach(() => {
  createChannel.mockReset()
})

describe('ChatCreateDialog.vue（Zimmer 作成後の openChannel）', () => {
  it('作成した部屋の id を openChannel で通知し、created も出す', async () => {
    createChannel.mockResolvedValue({ data: { id: 321 } })
    const wrapper = await mountSuspended(ChatCreateDialog, {
      props: { visible: true },
      global: {
        stubs: { Dialog: Passthrough, Tabs: Passthrough, TabList: Passthrough, Tab: Passthrough, TabPanels: Passthrough, TabPanel: Passthrough },
      },
    })
    await flushPromises()
    // 2 つ目のタブの「部屋名」入力（連絡先タブの検索欄の次の入力）を埋める
    const inputs = wrapper.findAll('input[type="text"], input:not([type])')
    await inputs[1]!.setValue('新しい部屋')
    const createBtn = wrapper.findAll('button').filter((b) => b.attributes('disabled') === undefined).at(-1)
    await createBtn!.trigger('click')
    await flushPromises()

    expect(createChannel).toHaveBeenCalled()
    expect(wrapper.emitted('openChannel')?.[0]).toEqual([321])
    expect(wrapper.emitted('created')).toBeTruthy()
  })
})
