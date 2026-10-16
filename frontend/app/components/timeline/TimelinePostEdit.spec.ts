import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { reactive, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { TimelinePostResponse } from '~/types/timeline'
import TimelinePostEdit from './TimelinePostEdit.vue'
import TimelineFeed from './TimelineFeed.vue'

const apiMock = vi.fn()
const errorMock = vi.fn()
const auth = reactive({ currentUser: { id: 7 } as { id: number } | null })
const role = ref<'ADMIN' | 'MEMBER' | null>('MEMBER')
const permitted = ref(false)
const loadPermissions = vi.fn(async () => ({ ok: true as const }))
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useAuthStore', () => () => auth)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, te: () => true, locale: ref('en') }))
mockNuxtImport('useNotification', () => () => ({ showError: errorMock, showSuccess: vi.fn() }))
mockNuxtImport('useUndoToast', () => () => ({ showUndoToast: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: errorMock }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: role, can: () => permitted.value, loadPermissions,
}))

const stubs = {
  Button: {
    props: ['label', 'disabled'], emits: ['click'],
    template: `<button :disabled="disabled" @click="$emit('click')">{{ label }}</button>`,
  },
  Dialog: {
    props: ['visible'],
    template: '<section v-if="visible" role="dialog"><slot /><slot name="footer" /></section>',
  },
  Textarea: {
    props: ['modelValue'], emits: ['update:modelValue'],
    template: `<textarea :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
  },
}

function post(): TimelinePostResponse {
  return {
    id: 11, scope: { scopeType: 'PUBLIC', scopeId: '0' },
    author: { userId: 7, socialProfileId: null, postedAsType: 'USER', postedAsId: null },
    content: { content: 'old text', parentId: null, repostOfId: null, status: 'PUBLISHED', scheduledAt: null, isPinned: false },
    stats: { repostCount: 0, reactionCount: 0, replyCount: 0, attachmentCount: 1, editCount: 0 },
    audit: { createdAt: '', updatedAt: '' }, user: null, postedAs: null,
    isBookmarked: false, isEdited: false, isTruncated: false, mitayo: false, mitayoCount: 0,
    attachments: [{ id: 4, attachmentType: 'VIDEO_LINK', sortOrder: 0, video: { videoUrl: 'https://example.invalid/video' } }],
    repostOf: null, poll: null,
  }
}
async function mountEditor(value = post()) {
  return mountSuspended(TimelinePostEdit, { props: { post: value }, global: { stubs } })
}
beforeEach(() => {
  apiMock.mockReset()
  apiMock.mockResolvedValue({ data: {} })
  errorMock.mockReset()
  auth.currentUser = { id: 7 }
  role.value = 'MEMBER'
  permitted.value = false
  loadPermissions.mockClear()
})

describe('タイムラインの通常本人投稿の本文編集', () => {
  it('実 composable の本文限定 PATCH で再取得を通知し、元の投稿データを保持する', async () => {
    const value = post()
    const before = structuredClone(value)
    const wrapper = await mountEditor(value)
    await wrapper.get('[data-testid="timeline-post-edit"]').trigger('click')
    await wrapper.get('textarea').setValue('new text')
    await wrapper.get('[data-testid="timeline-edit-save"]').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/timeline/posts/11', { method: 'PATCH', body: { content: 'new text' } })
    expect(wrapper.emitted('edited')).toHaveLength(1)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(value).toEqual(before)
  })

  it('編集の実クリック後に実 Feed を再取得して GET の本文を表示する', async () => {
    let saved = false
    apiMock.mockImplementation(async (url: string, options?: { method?: string }) => {
      if (options?.method === 'PATCH') { saved = true; return { data: {} } }
      if (url.startsWith('/api/v1/timeline/feed?')) {
        const value = post()
        value.content.content = saved ? 'reloaded text' : 'old text'
        return { data: { pinned: [], posts: [value] }, meta: { nextCursor: null, hasNext: false } }
      }
      throw new Error('試験で想定していない API 呼出')
    })
    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'PUBLIC', scopeId: '0' }, global: { stubs },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('old text')
    await wrapper.get('[data-testid="timeline-post-edit"]').trigger('click')
    await wrapper.get('[data-testid="timeline-edit-content"]').setValue('reloaded text')
    await wrapper.get('[data-testid="timeline-edit-save"]').trigger('click')
    await flushPromises()
    expect(apiMock.mock.calls.map(call => call[0])).toEqual([
      '/api/v1/timeline/feed?scopeType=PUBLIC&scopeId=0',
      '/api/v1/timeline/posts/11',
      '/api/v1/timeline/feed?scopeType=PUBLIC&scopeId=0',
    ])
    expect(wrapper.text()).toContain('reloaded text')
  })

  it('取消は PATCH を送信せず元の本文を保持する', async () => {
    const value = post()
    const wrapper = await mountEditor(value)
    await wrapper.get('[data-testid="timeline-post-edit"]').trigger('click')
    await wrapper.get('textarea').setValue('unsaved')
    await wrapper.get('[data-testid="timeline-edit-cancel"]').trigger('click')
    expect(apiMock).not.toHaveBeenCalled()
    expect(value.content.content).toBe('old text')
  })

  it('サーバーの権限拒否時に編集欄と元の表示を保持する', async () => {
    const denied = { statusCode: 403 }
    apiMock.mockRejectedValue(denied)
    const value = post()
    const wrapper = await mountEditor(value)
    await wrapper.get('[data-testid="timeline-post-edit"]').trigger('click')
    await wrapper.get('textarea').setValue('denied edit')
    await wrapper.get('[data-testid="timeline-edit-save"]').trigger('click')
    await flushPromises()
    expect(errorMock).toHaveBeenCalledWith(denied)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.emitted('edited')).toBeUndefined()
    expect(value.content.content).toBe('old text')
  })

  it.each(['other', 'system', 'reply', 'repost', 'delegated', 'draft'] as const)('%s 投稿には編集入口を出さない', async kind => {
    const value = post()
    if (kind === 'other') value.author.userId = 8
    if (kind === 'system') value.systemPostType = 'EVENT_CREATED'
    if (kind === 'reply') value.content.parentId = 2
    if (kind === 'repost') value.content.repostOfId = 2
    if (kind === 'delegated') value.author.postedAsType = 'TEAM'
    if (kind === 'draft') value.content.status = 'DRAFT'
    const wrapper = await mountEditor(value)
    expect(wrapper.find('[data-testid="timeline-post-edit"]').exists()).toBe(false)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('通常 SOCIAL_PROFILE 投稿の入口は実効ユーザー本人だけに許可する', async () => {
    const value = post()
    value.author.postedAsType = 'SOCIAL_PROFILE'
    value.author.socialProfileId = 3
    const wrapper = await mountEditor(value)
    expect(wrapper.find('[data-testid="timeline-post-edit"]').exists()).toBe(true)
    auth.currentUser = { id: 8 }
    await flushPromises()
    expect(wrapper.find('[data-testid="timeline-post-edit"]').exists()).toBe(false)
  })

  it('スコープの本人 MEMBER は MANAGE_POSTS を必要とし、付与後に入口を出す', async () => {
    const value = post()
    value.scope = { scopeType: 'TEAM', scopeId: '22' }
    const wrapper = await mountEditor(value)
    await flushPromises()
    expect(loadPermissions).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="timeline-post-edit"]').exists()).toBe(false)
    permitted.value = true
    await flushPromises()
    expect(wrapper.find('[data-testid="timeline-post-edit"]').exists()).toBe(true)
    auth.currentUser = { id: 8 }
    await flushPromises()
    expect(wrapper.find('[data-testid="timeline-post-edit"]').exists()).toBe(false)
  })

  it('PUBLIC の280字上限を維持し、空白だけの本文を保存しない', async () => {
    const wrapper = await mountEditor()
    await wrapper.get('[data-testid="timeline-post-edit"]').trigger('click')
    expect(wrapper.get('textarea').attributes('maxlength')).toBe('280')
    await wrapper.get('textarea').setValue(' '.repeat(3))
    expect(wrapper.get('[data-testid="timeline-edit-save"]').attributes('disabled')).toBeDefined()
    await wrapper.get('textarea').setValue('x'.repeat(281))
    expect(wrapper.get('[data-testid="timeline-edit-save"]').attributes('disabled')).toBeDefined()
    expect(apiMock).not.toHaveBeenCalled()
  })
})
