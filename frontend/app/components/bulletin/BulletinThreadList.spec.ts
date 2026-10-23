// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { BulletinThreadResponse } from '~/types/bulletin'
import BulletinThreadList from './BulletinThreadList.vue'

const mocks = vi.hoisted(() => ({ getThreads: vi.fn(), getCategories: vi.fn(), archive: vi.fn() }))
mockNuxtImport('useBulletinApi', () => () => ({
  getThreads: mocks.getThreads, getCategories: mocks.getCategories,
  archiveScopedThread: mocks.archive, readAll: vi.fn(),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useNotification', () => () => ({ showError: vi.fn(), showSuccess: vi.fn() }))
mockNuxtImport('useRelativeTime', () => () => ({ relativeTime: (value: string) => value }))

const stubs = {
  SectionCard: { template: '<article v-bind="$attrs"><slot /></article>' },
  Button: { props: ['label'], emits: ['click'], template: '<button v-bind="$attrs" @click="$emit(\'click\')">{{ label }}</button>' },
  DashboardEmptyState: { template: '<div data-testid="empty" />' },
  DashboardErrorState: { emits: ['retry'], template: '<button data-testid="retry" @click="$emit(\'retry\')">再試行</button>' },
  InputText: true, Select: true, Paginator: true, LoadingBounce: true,
}
const thread: BulletinThreadResponse = {
  id: 1, categoryId: null, categoryName: '連絡', categoryColor: null,
  scopeType: 'TEAM', scopeId: '1', author: { id: 2, displayName: '投稿者', avatarUrl: null },
  title: '見えるタイトル', body: '見える本文', priority: 'INFO', readTrackingMode: 'COUNT_ONLY',
  isPinned: true, isLocked: false, isArchived: false, replyCount: 1, readCount: 2,
  isRead: false, reactionSummary: {}, myReactions: [], lastRepliedAt: null,
  createdAt: '2026-10-24T12:00:00+09:00', updatedAt: '2026-10-24T12:00:00+09:00',
}
const page = (body: string) => ({ data: [{ ...thread, body }], meta: { totalPages: 1, page: 0 } })
async function mountList() {
  const wrapper = mount(BulletinThreadList, {
    props: { scopeType: 'TEAM', scopeId: 1, canManage: true }, global: { stubs },
  })
  await flushPromises()
  return wrapper
}

describe('掲示板一覧の本文とカード表示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getCategories.mockResolvedValue({ data: [] })
    mocks.getThreads.mockResolvedValue(page(thread.body))
  })
  it('タイトルと本文を表示し選択できる', async () => {
    const wrapper = await mountList()
    expect(wrapper.text()).toContain('見えるタイトル')
    expect(wrapper.text()).toContain('見える本文')
    await wrapper.get('[data-testid="bulletin-thread-select"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]?.[0]).toMatchObject({ id: 1 })
  })
  it.each([0, 99, 100, 101])('%i字の本文を100字まで表示する', async (length) => {
    mocks.getThreads.mockResolvedValue(page('あ'.repeat(length)))
    const wrapper = await mountList()
    expect(wrapper.get('[data-testid="bulletin-thread-preview"]').text()).toBe('あ'.repeat(Math.min(length, 100)))
  })
  it('HTMLとMarkdownをプレーン化し危険な要素を描画しない', async () => {
    mocks.getThreads.mockResolvedValue(page('# 見出し\n\n**重要** [案内](https://example.com)\n<p>次の行 &amp; 本文</p><script>alert(1)</script>'))
    const wrapper = await mountList()
    expect(wrapper.get('[data-testid="bulletin-thread-preview"]').text()).toBe('見出し 重要 案内 次の行 & 本文')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('[data-testid="bulletin-thread-preview"] a').exists()).toBe(false)
  })
  it('ページ切替の失敗時も失敗したページを再試行する', async () => {
    mocks.getThreads.mockResolvedValueOnce({ ...page(thread.body), meta: { totalPages: 3, page: 0 } })
    const wrapper = await mountList()
    mocks.getThreads.mockRejectedValueOnce(new Error('offline'))
    wrapper.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 1 })
    await flushPromises()
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()
    expect(mocks.getThreads.mock.calls.slice(-2).map(call => call[0].page)).toEqual([1, 1])
  })
  it('取得失敗を空一覧にせず同じ取得処理で再試行する', async () => {
    mocks.getThreads.mockRejectedValueOnce(new Error('offline'))
    const wrapper = await mountList()
    expect(wrapper.find('[data-testid="empty"]').exists()).toBe(false)
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('見える本文')
    expect(mocks.getThreads).toHaveBeenCalledTimes(2)
  })
})
