import { describe, it, expect, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import TeamChatPage from '~/pages/teams/[slug]/chat.vue'

/**
 * CMP-261030-0648 AC-24: 問い合わせ通知の遷移先 /teams/{slug}/chat?channel={id} を開くと、
 * チームチャットはそのチャンネルを選択した状態で開く。
 * ページは ?channel= を読んで一覧へ initialChannelId として渡し、一覧の読み込みが済んだら渡すのをやめる
 * （モバイルで一覧へ戻ったときに同じチャンネルを選び直さないため）。
 */

const mocks = vi.hoisted(() => ({
  route: { params: { slug: 'lions-fc' }, query: {} as Record<string, unknown> },
}))
mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(false),
  isAdminOrDeputy: ref(false),
  loadPermissions: vi.fn(),
}))
mockNuxtImport('useIsDesktop', () => () => ({ isDesktop: ref(true) }))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

const ChatChannelListStub = defineComponent({
  name: 'ChatChannelList',
  props: {
    teamId: { type: String, default: undefined },
    initialChannelId: { type: Number, default: undefined },
  },
  emits: ['select', 'create', 'loaded'],
  setup(_props, { expose }) {
    expose({ refresh: vi.fn(), refreshAndSelect: vi.fn().mockResolvedValue(undefined) })
    return () => h('div')
  },
})

async function mountWithQuery(query: Record<string, unknown>) {
  mocks.route = { params: { slug: 'lions-fc' }, query }
  const wrapper = await mountSuspended(TeamChatPage, {
    shallow: true,
    global: { stubs: { ChatChannelList: ChatChannelListStub } },
  })
  await flushPromises()
  return wrapper
}

describe('/teams/[slug]/chat.vue の ?channel=（AC-24）', () => {
  it('AC-24: ?channel={id} を一覧へ initialChannelId として渡す', async () => {
    const wrapper = await mountWithQuery({ channel: '7' })
    expect(wrapper.findComponent({ name: 'ChatChannelList' }).props('initialChannelId')).toBe(7)
  })

  it.each(['abc', '0', '-3', '1.5'])('AC-24: ?channel=%s（正の整数でない）は渡さない', async (q) => {
    const wrapper = await mountWithQuery({ channel: q })
    expect(wrapper.findComponent({ name: 'ChatChannelList' }).props('initialChannelId')).toBeUndefined()
  })

  it('AC-24: ?channel= が無ければ渡さない', async () => {
    const wrapper = await mountWithQuery({})
    expect(wrapper.findComponent({ name: 'ChatChannelList' }).props('initialChannelId')).toBeUndefined()
  })

  it('AC-24: 一覧の読み込みが済んだら initialChannelId を渡すのをやめる', async () => {
    const wrapper = await mountWithQuery({ channel: '7' })
    expect(wrapper.findComponent({ name: 'ChatChannelList' }).props('initialChannelId')).toBe(7)
    wrapper.findComponent({ name: 'ChatChannelList' }).vm.$emit('loaded', 3)
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ChatChannelList' }).props('initialChannelId')).toBeUndefined()
  })
})
