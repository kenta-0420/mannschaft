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
const { navigateMock, auth } = vi.hoisted(() => ({
  navigateMock: vi.fn(),
  auth: {
    currentUser: { id: 100 } as { id: number } | null,
    loadFromStorage: vi.fn(),
  },
}))

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
mockNuxtImport('useAuthStore', () => () => auth)
mockNuxtImport('navigateTo', () => navigateMock)

const stubs = {
  LoadingBounce: true,
  DashboardEmptyState: true,
  NuxtLink: {
    name: 'NuxtLink',
    props: ['to'],
    template: '<a v-bind="$attrs"><slot /></a>',
  },
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
    content: { title: '記事1', slug: 'post-1', excerpt: null, coverImageUrl: null, body: '' },
    meta: { status: 'DRAFT', visibility: null, postType: null, publicVisible: true },
    // CmsMapper の現行レスポンス: author オブジェクトは無く、実際の著者は scope.authorId。
    scope: { teamId: 42, organizationId: null, userId: null, authorId: 100 },
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

async function mountPosts(posts: unknown[], canManage = false) {
  apiMock.mockImplementation((url: string) => Promise.resolve({
    data: url.startsWith('/api/v1/blog/posts') ? posts : [],
  }))
  const wrapper = await mountSuspended(BlogPostList, {
    props: { scopeType: 'TEAM', scopeId: 'public-team-slug', canCreate: true, canManage },
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

describe('BlogPostList 一覧取得の失敗表示（CMP-261007-2052）', () => {
  beforeEach(() => {
    apiMock.mockReset()
    navigateMock.mockReset()
    auth.currentUser = { id: 100 }
  })

  const failures: Array<[string, Error]> = [
    ['403', Object.assign(new Error('Forbidden'), { statusCode: 403 })],
    ['500', Object.assign(new Error('Server Error'), { statusCode: 500 })],
    ['ネットワーク断', new TypeError('Failed to fetch')],
  ]

  it.each(failures)('AC-17a: 一覧APIが%sで失敗しても「記事がありません」を出さず、失敗表示と再読み込みを出す', async (_label, err) => {
    apiMock.mockImplementation((url: string) =>
      url.startsWith('/api/v1/blog/posts') ? Promise.reject(err) : Promise.resolve({ data: [] }),
    )
    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: false },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="blog-post-empty"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-post-load-error"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="blog-post-load-error"]').text().length).toBeGreaterThan(0)
    expect(wrapper.find('[data-testid="blog-post-reload-button"]').exists()).toBe(true)
  })

  it('AC-17b: 再読み込みで成功すると失敗表示が消えて記事が並ぶ', async () => {
    let fail = true
    apiMock.mockImplementation((url: string) => {
      if (!url.startsWith('/api/v1/blog/posts')) return Promise.resolve({ data: [] })
      return fail ? Promise.reject(new Error('boom')) : Promise.resolve({ data: [makePost({ id: 5 })] })
    })
    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: false },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="blog-post-load-error"]').exists()).toBe(true)

    fail = false
    await wrapper.get('[data-testid="blog-post-reload-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="blog-post-load-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="blog-post-card"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="blog-post-empty"]').exists()).toBe(false)
  })

  it('AC-17c: 成功して0件のときだけ「記事がありません」を出し、失敗表示は出さない', async () => {
    apiMock.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: false },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="blog-post-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="blog-post-load-error"]').exists()).toBe(false)
  })

  it('AC-18: canCreate=false（非所属者）でも公開記事のカードと詳細リンクが並び、作成ボタンとタグ管理は出ない', async () => {
    const post = makePost({
      id: 9,
      scope: { teamId: 42, organizationId: null, userId: null, authorId: 999 },
      meta: { status: 'PUBLISHED', visibility: 'PUBLIC', postType: 'BLOG', publicVisible: true },
    })
    apiMock.mockImplementation((url: string) =>
      Promise.resolve({ data: url.startsWith('/api/v1/blog/posts') ? [post] : [] }),
    )
    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType: 'TEAM', scopeId: '1', canCreate: false },
      global: { stubs },
    })
    await flushPromises()

    expect(wrapper.findAll('[data-testid="blog-post-card"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="blog-post-read-9"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="blog-post-create-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-tag-add-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-tag-list"]').exists()).toBe(false)
  })
})

describe('BlogPostList タグ管理・記事モデレーション', () => {
  beforeEach(() => {
    apiMock.mockReset()
    navigateMock.mockReset()
    auth.currentUser = { id: 100 }
  })

  it.each([
    { scopeType: 'TEAM', scopeId: 'public-team-slug', requestScope: { teamId: 'public-team-slug' } },
    {
      scopeType: 'ORGANIZATION',
      scopeId: 'public-org-slug',
      requestScope: { organizationId: 'public-org-slug' },
    },
  ])('通常 $scopeType 作成 UI は正規スコープの下書きを作って編集へ進む', async ({ scopeType, scopeId, requestScope }) => {
    apiMock.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(BlogPostList, {
      props: { scopeType, scopeId, canCreate: true, canManage: false },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.get('[data-testid="blog-post-create-button"]').trigger('click')
    await flushPromises()
    await wrapper.get('input').setValue('本人の下書き')
    apiMock.mockClear()
    apiMock.mockResolvedValue({
      data: makePost({
        id: 9,
        content: {
          title: '本人の下書き',
          slug: 'own-draft',
          body: '.',
          excerpt: null,
          coverImageUrl: null,
        },
      }),
    })

    await wrapper.get('[data-testid="blog-post-create-submit"]').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledExactlyOnceWith('/api/v1/blog/posts', {
      method: 'POST',
      body: { title: '本人の下書き', body: '.', ...requestScope },
    })
    expect(navigateMock).toHaveBeenCalledExactlyOnceWith(
      `/blog/posts/9/edit?title=${encodeURIComponent('本人の下書き')}`,
    )
  })

  it.each([
    { scope: { teamId: 42, organizationId: null, userId: null, authorId: 999 }, to: { path: '/blog/posts/same-slug', query: { teamId: '42' } } },
    { scope: { teamId: null, organizationId: 7, userId: null, authorId: 999 }, to: { path: '/blog/posts/same-slug', query: { organizationId: '7' } } },
    { scope: { teamId: null, organizationId: null, userId: 999, authorId: 999 }, to: { path: '/users/999/blog/posts/same-slug' } },
  ])('公開記事のタイトルは実際の記事スコープで閲覧に入る（$to.path）', async ({ scope, to }) => {
    const post = makePost({ scope, content: { title: '読む記事', slug: 'same-slug', body: '本文', excerpt: null, coverImageUrl: null }, meta: { status: 'PUBLISHED', visibility: 'MEMBERS_ONLY', postType: 'BLOG', publicVisible: true } })
    expect(post).not.toHaveProperty('author')
    const wrapper = await mountPosts([post])
    const link = wrapper.getComponent({ name: 'NuxtLink' })
    expect(link.attributes('data-testid')).toBe('blog-post-read-1')
    expect(link.props('to')).toEqual(to)
    await link.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[post]])
    expect(navigateMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="blog-post-edit-1"]').exists()).toBe(false)
  })

  it.each(['DRAFT', 'SCHEDULED', 'PENDING_REVIEW', 'PENDING_SELF_REVIEW', 'REJECTED', 'ARCHIVED', 'PUBLISHED'] as const)(
    '本人の記事は%sでも明示編集から既存エディタへ入り、未公開タイトルはリンクにならない',
    async (status) => {
      const post = makePost({ meta: { status, visibility: null, postType: 'BLOG', publicVisible: true } })
      const wrapper = await mountPosts([post])
      expect(wrapper.find('[data-testid="blog-post-read-1"]').exists()).toBe(status === 'PUBLISHED')
      await wrapper.get('[data-testid="blog-post-edit-1"]').trigger('click')
      expect(navigateMock).toHaveBeenCalledExactlyOnceWith('/blog/posts/1/edit')
      expect(wrapper.find('button button, a button').exists()).toBe(false)
    },
  )

  it.each([null, undefined, 0, -1, 1.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1])(
    '著者ID欠損・不正（%s）は本人の編集・公開切替・削除を出さない',
    async (authorId) => {
      const wrapper = await mountPosts([makePost({ scope: { teamId: 42, organizationId: null, userId: null, authorId } })])
      expect(wrapper.find('[data-testid="blog-post-edit-1"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="blog-post-toggle-publish-1"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="blog-post-delete-1"]').exists()).toBe(false)
    },
  )

  it('認証ユーザーと著者が両方欠損しても、本人操作を出さない', async () => {
    auth.currentUser = null
    const wrapper = await mountPosts([makePost({ scope: undefined })])
    expect(wrapper.find('[data-testid="blog-post-edit-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-post-toggle-publish-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-post-delete-1"]').exists()).toBe(false)
  })

  it('旧author.idが一致しても正準scope.authorIdが他人なら本人操作を出さない', async () => {
    const wrapper = await mountPosts([makePost({
      scope: { teamId: 42, organizationId: null, userId: null, authorId: 999 },
      author: { id: 100, displayName: '旧データ', avatarUrl: null },
    })])
    expect(wrapper.find('[data-testid="blog-post-edit-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-post-toggle-publish-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="blog-post-delete-1"]').exists()).toBe(false)
  })

  it('管理者は他人・著者欠損記事をモデレーションできるが、本人専用編集へは入らない', async () => {
    const wrapper = await mountPosts([makePost({ scope: { teamId: 42, organizationId: null, userId: null, authorId: null } })], true)
    expect(wrapper.find('[data-testid="blog-post-toggle-publish-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="blog-post-delete-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="blog-post-edit-1"]').exists()).toBe(false)
  })

  it('公開記事でもslug・scope・状態が欠けると壊れた閲覧リンクを出さない', async () => {
    const published = makePost({ meta: { status: 'PUBLISHED', visibility: 'PUBLIC', postType: 'BLOG', publicVisible: true } })
    const invalidPosts = [
      { ...published, id: 1, content: undefined },
      { ...published, id: 2, content: { ...published.content!, slug: '' } },
      { ...published, id: 3, scope: undefined },
      { ...published, id: 4, scope: { teamId: 0, organizationId: null, userId: null, authorId: 100 } },
      { ...published, id: 5, scope: { teamId: 42, organizationId: 7, userId: null, authorId: 100 } },
      { ...published, id: 6, meta: undefined },
      { ...published, id: 7, meta: { ...published.meta, status: 'UNKNOWN' } },
    ]
    const wrapper = await mountPosts(invalidPosts)
    expect(wrapper.findAll('[data-testid^="blog-post-read-"]')).toHaveLength(0)
    expect(navigateMock).not.toHaveBeenCalled()
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
          data: [makePost({ id: 1 }), makePost({ id: 2, scope: { teamId: 42, organizationId: null, userId: null, authorId: 999 } })],
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
        return Promise.resolve({ data: [makePost({ id: 2, scope: { teamId: 42, organizationId: null, userId: null, authorId: 999 } })] })
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
        return Promise.resolve({ data: [makePost({ id: 1 })] })
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
