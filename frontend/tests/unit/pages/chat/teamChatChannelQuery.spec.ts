import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, onMounted, reactive, ref } from 'vue'
import TeamChatPage from '~/pages/teams/[slug]/chat.vue'
import type { ChatChannelResponse } from '~/types/chat'

/**
 * CMP-261030-0648 AC-24: 問い合わせ通知の遷移先 /teams/{slug}/chat?channel={id} を開くと、
 * チームチャットはそのチャンネルを選択した状態で開く。
 *
 * 同じ slug のまま query だけが変わる SPA 遷移ではページが作り直されないため、
 * ページは route.query.channel を watch して追従する（Codex 検分 P2）。
 * 正の整数で一覧にあるチャンネルなら選択し、それ以外・存在しない・見られないなら未選択にする。
 * ページは 1 回だけ mount し、query を書き換えて追従を確かめる。
 */

const mocks = vi.hoisted(() => ({
  route: null as null | { params: { slug: string }, query: Record<string, unknown> },
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

function channel(id: number): ChatChannelResponse {
  return {
    id,
    identity: { channelType: 'TEAM_PUBLIC', teamId: 1, organizationId: null },
    meta: { name: `room-${id}`, iconKey: null, description: null },
    settings: { isPrivate: false, isInquiryChannel: id === 7, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: null, sourceId: null },
    audit: { createdBy: null, createdAt: null, updatedAt: null },
    memberCount: 2,
    dmPartner: null,
    viewer: { unreadCount: 0, isMuted: false, isPinned: false, category: null, role: null },
  } as ChatChannelResponse
}

/** 一覧のスタブ。一覧に載る（＝見られる）のは 7 と 8 だけ。 */
const VISIBLE = new Map([[7, channel(7)], [8, channel(8)]])
const ChatChannelListStub = defineComponent({
  name: 'ChatChannelList',
  props: {
    teamId: { type: String, default: undefined },
    initialChannelId: { type: Number, default: undefined },
  },
  emits: ['select', 'create', 'loaded'],
  setup(props, { emit, expose }) {
    const pick = (id: number | undefined) => {
      const ch = id === undefined ? undefined : VISIBLE.get(id)
      if (ch) emit('select', ch)
    }
    expose({
      refresh: vi.fn(),
      refreshAndSelect: vi.fn(async (id: number) => pick(id)),
    })
    onMounted(() => {
      pick(props.initialChannelId)
      emit('loaded', VISIBLE.size)
    })
    return () => h('div')
  },
})

beforeEach(() => {
  mocks.route = reactive({ params: { slug: 'lions-fc' }, query: {} as Record<string, unknown> })
})

async function mountWithQuery(query: Record<string, unknown>) {
  mocks.route!.query = query
  const wrapper = await mountSuspended(TeamChatPage, {
    shallow: true,
    global: { stubs: { ChatChannelList: ChatChannelListStub } },
  })
  await flushPromises()
  return wrapper
}

async function changeQuery(query: Record<string, unknown>) {
  mocks.route!.query = query
  await flushPromises()
}

function shownChannelId(wrapper: Awaited<ReturnType<typeof mountWithQuery>>): number | null {
  const panel = wrapper.findComponent({ name: 'ChatMessagePanel' })
  return panel.exists() ? (panel.props('channel') as ChatChannelResponse).id : null
}

describe('/teams/[slug]/chat.vue の ?channel=（AC-24）', () => {
  it('AC-24: 開いたときの ?channel={id} のチャンネルが選択された状態で開く', async () => {
    const wrapper = await mountWithQuery({ channel: '7' })
    expect(shownChannelId(wrapper)).toBe(7)
  })

  it('AC-24: 1回 mount したまま query だけが変わっても選択が追従する（見られない・存在しない・不正値・無しは未選択）', async () => {
    const wrapper = await mountWithQuery({ channel: '7' })
    expect(shownChannelId(wrapper)).toBe(7)

    await changeQuery({ channel: '8' })
    expect(shownChannelId(wrapper)).toBe(8)

    // 一覧に無い（存在しない・見られない）チャンネル
    await changeQuery({ channel: '999' })
    expect(shownChannelId(wrapper)).toBeNull()

    await changeQuery({ channel: '7' })
    expect(shownChannelId(wrapper)).toBe(7)

    for (const bad of ['abc', '0', '-3', '1.5']) {
      await changeQuery({ channel: '8' })
      expect(shownChannelId(wrapper)).toBe(8)
      await changeQuery({ channel: bad })
      expect(shownChannelId(wrapper), `?channel=${bad}`).toBeNull()
    }

    await changeQuery({ channel: '7' })
    await changeQuery({})
    expect(shownChannelId(wrapper)).toBeNull()
  })

  it('AC-24: 開いたときの ?channel= が不正値なら未選択（エラーにしない）', async () => {
    const wrapper = await mountWithQuery({ channel: 'abc' })
    expect(shownChannelId(wrapper)).toBeNull()
  })
})
