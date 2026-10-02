import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { BlogPostResponse, BlogTag } from '~/types/cms'
import BlogPostList from './BlogPostList.vue'

// CMP-260917-0041: /admin/blog-management のタグ管理・公開切替・削除機能を
// 共通部品 BlogPostList.vue へ移植した際の単体テスト。
//
// 権限の粒度は BE の実測に合わせる:
// - タグ一覧・作成: メンバー（canCreate）
// - タグ更新・削除: ADMIN/DEPUTY_ADMIN（canManage）
// - 記事の公開切替・削除: 投稿者本人 または ADMIN/DEPUTY_ADMIN（canManage）
//
// useBlogApi は実装をモックせず、useApi のみモックする。これにより
// createTag/getTags が組み立てるクエリ・ボディ（teamId/organizationId 写像）を
// 実際のロジックで検証できる（既存バグ: 旧実装は scopeType/scopeId をそのまま
// 送っており BE の teamId/organizationId と名前が一致せず、スコープが一切
// 送信されていなかった）。

const apiMock = vi.fn()

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
}))
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: vi.fn(),
}))
mockNuxtImport('useRelativeTime', () => () => ({
  relativeTime: (v: string) => v,
}))
mockNuxtImport('useAuthStore', () => () => ({
  currentUser: { id: 100 },
}))

const stubs = {
  LoadingBounce: true,
  DashboardEmptyState: true,
  Button: {
    props: ['label', 'loading', 'disabled', 'icon', 'severity', 'text', 'ariaLabel'],
    emits: ['click'],
    template: '<button :disabled="disabled || loading" v-bind="$attrs" @click="$emit(\'click\', $event)"><slot>{{ label }}</slot></button>',
  },
  Dialog: {
    props: ['visible', 'header'],
    emits: ['update:visible'],
    template: '<div v-if="visible" role="dialog"><header>{{ header }}</header><slot /><slot name="footer" /></div>',
  },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}

function makePost(overrides: Partial<BlogPostResponse> = {}): BlogPostResponse {
  return {
    id: 1,
    content: { title: '記事1', excerpt: null, coverImageUrl: null, body: '' } as never,
    meta: { status: 'DRAFT', visibility: null, postType: null } as never,
    audit: { createdAt: '2026-09-01T00:00:00Z', updatedAt: null, publishedAt: null } as never,
    author: { id: 100, displayName: 'テスト太郎', avatarUrl: null },
    tags: [],
    seriesId: null,
    seriesName: null,
    seriesOrder: null,
    scheduledAt: null,
    ...overrides,
  }
}

function makeTag(overrides: Partial<BlogTag> = {}): BlogTag {
  return { id: 1, name: 'お知らせ', postCount: 0, ...overrides }
}

describe('BlogPostList タグ管理・記事モデレーション', () => {
  beforeEach(() => {
    apiMock.mockReset()
  })

  it('タグ一覧・作成 UI はメンバー（canCreate）で描画される', async () => {
    apiMock.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/blog/posts')) return Promise.resolve({ data: [] })
      if (url.startsWith('/api/v1/blog/tags')) return Promise.resolve({ data: [makeTag()] })
      return Promise.resolve({ data: [] })
    })
    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: true, canManage: false },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="blog-tag-add-button"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="blog-tag-list"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('お知らせ')
  })

  it('canCreate=false（メンバーでない）のときタグ UI 自体が出ない', async () => {
    apiMock.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: false, canManage: false },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="blog-tag-add-button"]').exists()).toBe(false)
  })

  it('タグ削除ボタンは canManage（ADMIN/DEPUTY_ADMIN）でのみ描画され、メンバーだけでは出ない', async () => {
    apiMock.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/blog/posts')) return Promise.resolve({ data: [] })
      if (url.startsWith('/api/v1/blog/tags')) return Promise.resolve({ data: [makeTag({ id: 7 })] })
      return Promise.resolve({ data: [] })
    })

    const memberOnly = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: true, canManage: false },
      global: { stubs },
    })
    await flushPromises()
    expect(memberOnly.find('[data-testid="blog-tag-delete-7"]').exists()).toBe(false)

    const admin = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: true, canManage: true },
      global: { stubs },
    })
    await flushPromises()
    expect(admin.find('[data-testid="blog-tag-delete-7"]').exists()).toBe(true)
  })

  it('記事の公開切替・削除は投稿者本人には出るが、他人の記事には（canManage=false なら）出ない', async () => {
    apiMock.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/blog/posts')) {
        return Promise.resolve({
          data: [makePost({ id: 1, author: { id: 100, displayName: '本人', avatarUrl: null } }), makePost({ id: 2, author: { id: 999, displayName: '他人', avatarUrl: null } })],
        })
      }
      return Promise.resolve({ data: [] })
    })

    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: true, canManage: false },
      global: { stubs },
    })
    await flushPromises()

    // 投稿者本人（id:100 === currentUser.id）の記事には操作が出る
    expect(wrapper.find('[data-testid="blog-post-toggle-publish-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="blog-post-delete-1"]').exists()).toBe(true)
    // 他人（id:999）の記事には出ない
    expect(wrapper.find('[data-testid="blog-post-toggle-publish-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-post-delete-2"]').exists()).toBe(false)
  })

  it('canManage=true（ADMIN/DEPUTY_ADMIN）なら他人の記事にも公開切替・削除が出る', async () => {
    apiMock.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/blog/posts')) {
        return Promise.resolve({ data: [makePost({ id: 2, author: { id: 999, displayName: '他人', avatarUrl: null } })] })
      }
      return Promise.resolve({ data: [] })
    })

    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: true, canManage: true },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="blog-post-toggle-publish-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="blog-post-delete-2"]').exists()).toBe(true)
  })

  it('個人ブログ（scopeType 未指定）では投稿者本人でも公開切替・削除は出ない（/blog の専用UIと二重化させない）', async () => {
    apiMock.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/blog/posts')) {
        return Promise.resolve({ data: [makePost({ id: 1, author: { id: 100, displayName: '本人', avatarUrl: null } })] })
      }
      return Promise.resolve({ data: [] })
    })

    const wrapper = await mountSuspended(BlogPostList, {
      props: { showCreate: false },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="blog-post-toggle-publish-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-post-delete-1"]').exists()).toBe(false)
  })

  it('getTags は scopeType/scopeId を BE の teamId/organizationId として送る（team スコープ）', async () => {
    apiMock.mockResolvedValue({ data: [] })
    await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '42', canCreate: true, canManage: false },
      global: { stubs },
    })
    await flushPromises()

    const tagsCall = apiMock.mock.calls.find(([url]) => String(url).startsWith('/api/v1/blog/tags'))
    expect(tagsCall).toBeDefined()
    const calledUrl = String(tagsCall![0])
    expect(calledUrl).toContain('teamId=42')
    expect(calledUrl).not.toContain('organizationId')
    expect(calledUrl).not.toContain('scopeType')
    expect(calledUrl).not.toContain('scope_type')
  })

  it('getTags は organization スコープでは organizationId を送る', async () => {
    apiMock.mockResolvedValue({ data: [] })
    await mountSuspended(BlogPostList, {
      props: { scopeType: 'ORGANIZATION', scopeId: '7', canCreate: true, canManage: false },
      global: { stubs },
    })
    await flushPromises()

    const tagsCall = apiMock.mock.calls.find(([url]) => String(url).startsWith('/api/v1/blog/tags'))
    expect(tagsCall).toBeDefined()
    const calledUrl = String(tagsCall![0])
    expect(calledUrl).toContain('organizationId=7')
    expect(calledUrl).not.toContain('teamId')
  })

  it('createTag は team スコープのとき body に teamId を含めて POST する', async () => {
    apiMock.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/blog/tags') && url.includes('teamId')) return Promise.resolve({ data: [] })
      if (url === '/api/v1/blog/tags') return Promise.resolve({ data: makeTag() })
      return Promise.resolve({ data: [] })
    })

    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '42', canCreate: true, canManage: false },
      global: { stubs },
    })
    await flushPromises()
    apiMock.mockClear()
    apiMock.mockResolvedValue({ data: makeTag() })

    await wrapper.get('[data-testid="blog-tag-add-button"]').trigger('click')
    await flushPromises()
    await wrapper.get('input').setValue('新しいタグ')
    await wrapper.get('[data-testid="blog-tag-create-submit"]').trigger('click')
    await flushPromises()

    const createCall = apiMock.mock.calls.find(([url]) => url === '/api/v1/blog/tags')
    expect(createCall).toBeDefined()
    const [, options] = createCall!
    expect(options).toMatchObject({ method: 'POST', body: { name: '新しいタグ', teamId: '42' } })
    expect(options.body).not.toHaveProperty('organizationId')
    expect(options.body).not.toHaveProperty('scopeType')
  })

  it('createTag は organization スコープのとき body に organizationId を含めて POST する', async () => {
    apiMock.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'ORGANIZATION', scopeId: '9', canCreate: true, canManage: false },
      global: { stubs },
    })
    await flushPromises()
    apiMock.mockClear()
    apiMock.mockResolvedValue({ data: makeTag() })

    await wrapper.get('[data-testid="blog-tag-add-button"]').trigger('click')
    await flushPromises()
    await wrapper.get('input').setValue('新しいタグ2')
    await wrapper.get('[data-testid="blog-tag-create-submit"]').trigger('click')
    await flushPromises()

    const createCall = apiMock.mock.calls.find(([url]) => url === '/api/v1/blog/tags')
    expect(createCall).toBeDefined()
    const [, options] = createCall!
    expect(options).toMatchObject({ method: 'POST', body: { name: '新しいタグ2', organizationId: '9' } })
    expect(options.body).not.toHaveProperty('teamId')
  })
})
