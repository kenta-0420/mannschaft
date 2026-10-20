// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, onMounted, ref, watch, type Component } from 'vue'
import Avatar from 'primevue/avatar'
import SectionCard from '../SectionCard.vue'
import BulletinThreadDetail from './BulletinThreadDetail.vue'
import BulletinThreadList from './BulletinThreadList.vue'
import BulletinArchiveView from './BulletinArchiveView.vue'
import type { BulletinThreadResponse } from '~/types/bulletin'
import { sanitizeHtml } from '~/utils/sanitizeHtml'

const mocks = vi.hoisted(() => ({
  getThread: vi.fn(), getReplies: vi.fn(), listThreadAttachments: vi.fn(), markRead: vi.fn(),
  getThreads: vi.fn(), getCategories: vi.fn(), getArchiveThreads: vi.fn(), getArchiveFolderTree: vi.fn(),
  showError: vi.fn(),
}))
vi.mock('~/composables/useBulletinApi', () => ({ useBulletinApi: () => mocks }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => ({ showError: mocks.showError, showSuccess: vi.fn() }) }))
vi.mock('~/composables/useRelativeTime', () => ({ useRelativeTime: () => ({ relativeTime: () => '先ほど' }) }))
vi.mock('~/composables/useUndoToast', () => ({ useUndoToast: () => ({ showUndoToast: vi.fn() }) }))
vi.mock('~/stores/useAuthStore', () => ({ useAuthStore: () => ({ currentUser: { id: 1 } }) }))
vi.mock('primevue/useconfirm', () => ({ useConfirm: () => ({ require: vi.fn() }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key === 'bulletin.unknownAuthor' ? '投稿者不明' : key }) }))

function thread(displayName: string | null = null, body = '<p>消えてはならない本文</p>'): BulletinThreadResponse {
  return {
    id: 163, categoryId: null, categoryName: null, categoryColor: null,
    scopeType: 'TEAM', scopeId: '1', author: { id: displayName === null ? null : 1, displayName, avatarUrl: null },
    title: 'アンケートの題名', body, priority: 'INFO', readTrackingMode: 'NONE',
    isPinned: false, isLocked: false, isArchived: false, replyCount: 0, readCount: 0,
    isRead: false, reactionSummary: {}, myReactions: [], lastRepliedAt: null,
    createdAt: '2026-10-18T22:00:00', updatedAt: '2026-10-18T22:00:00',
  }
}

const stubs = {
  SectionCard, Avatar, Button: true, Textarea: true, LoadingBounce: true,
  InputText: true, Select: true, Paginator: true, DashboardEmptyState: true,
  BulletinArchiveFolderTree: true, BulletinArchiveFolderEditDialog: true, Dialog: true,
}

beforeEach(() => {
  vi.clearAllMocks()
  // Nuxt起動を伴わないhappy-dom実行でも、Vueと製品サニタイズは実物を使う。
  for (const [name, value] of Object.entries({ computed, onMounted, ref, watch, sanitizeHtml })) {
    vi.stubGlobal(name, value)
  }
  vi.stubGlobal('useBulletinApi', () => mocks)
  vi.stubGlobal('useNotification', () => ({ showError: mocks.showError, showSuccess: vi.fn() }))
  vi.stubGlobal('useRelativeTime', () => ({ relativeTime: () => '先ほど' }))
  vi.stubGlobal('useUndoToast', () => ({ showUndoToast: vi.fn() }))
  vi.stubGlobal('useAuthStore', () => ({ currentUser: { id: 1 } }))
  vi.stubGlobal('useConfirm', () => ({ require: vi.fn() }))
  vi.stubGlobal('useI18n', () => ({ t: (key: string) => key === 'bulletin.unknownAuthor' ? '投稿者不明' : key }))
  mocks.getThread.mockResolvedValue({ data: thread() })
  mocks.getReplies.mockResolvedValue({ data: [] })
  mocks.listThreadAttachments.mockResolvedValue([])
  mocks.markRead.mockResolvedValue({})
  mocks.getCategories.mockResolvedValue({ data: [] })
  mocks.getThreads.mockResolvedValue({ data: [], meta: { page: 0, totalPages: 0 } })
  mocks.getArchiveThreads.mockResolvedValue({ data: [], meta: { page: 0, totalPages: 0 } })
  mocks.getArchiveFolderTree.mockResolvedValue({ data: [], meta: { unfiledThreadCount: 0 } })
})
afterEach(() => vi.unstubAllGlobals())

function renderDetail() {
  const errors: unknown[] = []
  const view = mount(BulletinThreadDetail, {
    props: { threadId: 163, canManage: true },
    global: { stubs, config: { errorHandler: error => errors.push(error) } },
  })
  return { view, errors }
}

describe('掲示板詳細の投稿者表示', () => {
  it('AC1: 投稿者の全fieldがnullでも題名・本文・代替名・Avatarを描画する', async () => {
    const { view, errors } = renderDetail()
    await flushPromises()

    expect(view.find('h2').exists()).toBe(true)
    expect(view.find('h2').text()).toBe('アンケートの題名')
    expect(view.find('.article-body').text()).toBe('消えてはならない本文')
    expect(view.text()).toContain('投稿者不明')
    expect(view.findComponent(Avatar).props('label')).toBe('投')
    expect(errors).toEqual([])
  })

  it('AC2: SURVEY由来の空本文・返信0件・添付0件でも題名を描画する', async () => {
    mocks.getThread.mockResolvedValue({ data: thread(null, '') })
    const { view, errors } = renderDetail()
    await flushPromises()

    expect(view.find('h2').text()).toBe('アンケートの題名')
    expect(view.find('.article-body').text()).toBe('')
    expect(view.text()).toContain('返信 0件')
    expect(errors).toEqual([])
  })

  it.each([
    ['', '投稿者不明', '投'],
    ['山', '山', '山'],
    ['山田太郎', '山田太郎', '山'],
  ])('AC3: 表示名「%s」を「%s」・頭文字「%s」で描画する', async (name, expected, initial) => {
    mocks.getThread.mockResolvedValue({ data: thread(name) })
    const { view, errors } = renderDetail()
    await flushPromises()

    expect(view.text()).toContain(expected)
    expect(view.findComponent(Avatar).props('label')).toBe(initial)
    expect(errors).toEqual([])
  })

  it('AC4: threadId切替でnull投稿者から通常投稿者へ再取得・表示する', async () => {
    const { view, errors } = renderDetail()
    await flushPromises()
    expect(view.text()).toContain('投稿者不明')
    mocks.getThread.mockResolvedValue({ data: { ...thread('佐藤花子'), id: 164, title: '次の題名' } })

    await view.setProps({ threadId: 164 })
    await flushPromises()

    expect(mocks.getThread).toHaveBeenLastCalledWith(164)
    expect(view.find('h2').text()).toBe('次の題名')
    expect(view.text()).toContain('佐藤花子')
    expect(view.text()).not.toContain('投稿者不明')
    expect(view.findComponent(Avatar).props('label')).toBe('佐')
    expect(errors).toEqual([])
  })

  it('AC6: 初回getThreadが403なら本文と管理操作を描画しない', async () => {
    mocks.getThread.mockRejectedValue({ statusCode: 403 })
    const { view, errors } = renderDetail()
    await flushPromises()

    expect(view.find('h2').exists()).toBe(false)
    expect(view.find('.article-body').exists()).toBe(false)
    expect(view.findAllComponents({ name: 'Button' })).toHaveLength(0)
    expect(mocks.markRead).not.toHaveBeenCalled()
    expect(mocks.showError).toHaveBeenCalledOnce()
    expect(errors).toEqual([])
  })
})

const threadListCases: Array<[string, Component, typeof mocks.getThreads]> = [
  ['一覧', BulletinThreadList, mocks.getThreads],
  ['保管庫', BulletinArchiveView, mocks.getArchiveThreads],
]

describe.each(threadListCases)('掲示板%sの投稿者表示', (_name, component, getThreads) => {
  it.each([null, '', '山田太郎'])('AC5: 投稿者名%sを代替名または通常名で描画する', async (name) => {
    getThreads.mockResolvedValue({ data: [thread(name)], meta: { page: 0, totalPages: 1 } })
    const view = mount(component, {
      props: { scopeType: 'TEAM', scopeId: '1', canManage: false },
      global: { stubs, mocks: { $t: (key: string) => key } },
    })
    await flushPromises()

    expect(view.find('h3').text()).toBe('アンケートの題名')
    expect(view.text()).toContain(name || '投稿者不明')
  })
})
