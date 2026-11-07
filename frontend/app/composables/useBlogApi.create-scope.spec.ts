import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useBlogApi } from './useBlogApi'

const api = vi.fn()
mockNuxtImport('useApi', () => () => api)

describe('useBlogApi ブログ作成のスコープ契約', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue({ data: { id: 1 } })
  })

  for (const method of ['createPost', 'createMyPost'] as const) {
    const path = method === 'createPost' ? '/api/v1/blog/posts' : '/api/v1/users/me/blog/posts'

    it.each([
      { scopeType: 'TEAM', scopeId: 'team-slug', scope: { teamId: 'team-slug' } },
      { scopeType: 'ORGANIZATION', scopeId: 'org-slug', scope: { organizationId: 'org-slug' } },
      { scopeType: 'TEAM', scopeId: '42', scope: { teamId: '42' } },
      { scopeType: 'PERSONAL', scopeId: null, scope: {} },
    ])(`${method}: $scopeType を正規作成DTOへ写像する`, async ({ scopeType, scopeId, scope }) => {
      await useBlogApi()[method]({ title: '本人の記事', body: '.', scopeType, scopeId })

      expect(api).toHaveBeenCalledExactlyOnceWith(path, {
        method: 'POST',
        body: { title: '本人の記事', body: '.', ...scope },
      })
    })

    it.each([{ teamId: '42' }, { organizationId: '7' }])(
      `${method}: 正規DTOの所属IDとその他の作成設定を保持する`,
      async (scope) => {
        const body = {
          title: '本人の記事',
          body: '.',
          ...scope,
          visibility: 'MEMBERS_ONLY',
          crossPostToTimeline: true,
        }

        await useBlogApi()[method](body)

        expect(api).toHaveBeenCalledExactlyOnceWith(path, { method: 'POST', body })
      },
    )
  }

  it('明示PERSONALでは残存するチーム・組織IDを送らない', async () => {
    await useBlogApi().createMyPost({
      title: '個人の記事',
      body: '.',
      scopeType: 'PERSONAL',
      teamId: '42',
      organizationId: '7',
    })

    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/users/me/blog/posts', {
      method: 'POST',
      body: { title: '個人の記事', body: '.' },
    })
  })

  it('明示TEAMでは別の所属IDを混在させず選択したslugのみを送る', async () => {
    await useBlogApi().createPost({
      title: 'チームの記事',
      body: '.',
      scopeType: 'TEAM',
      scopeId: 'team-slug',
      teamId: '42',
      organizationId: '7',
    })

    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/blog/posts', {
      method: 'POST',
      body: { title: 'チームの記事', body: '.', teamId: 'team-slug' },
    })
  })

  it.each([
    { scopeType: 'TEAM', scopeId: undefined },
    { scopeType: 'ORGANIZATION', scopeId: '' },
    { scopeType: 'TEAM', scopeId: ' ' },
    { scopeType: 'UNKNOWN', scopeId: 'team-slug' },
    { scopeType: undefined, scopeId: 'team-slug' },
    { scopeType: 'PERSONAL', scopeId: 'team-slug' },
  ])('欠落・空・未知のスコープを個人記事として送信しない ($scopeType/$scopeId)', async ({ scopeType, scopeId }) => {
    await expect(
      useBlogApi().createPost({ title: '本人の記事', body: '.', scopeType, scopeId }),
    ).rejects.toThrow(TypeError)

    expect(api).not.toHaveBeenCalled()
  })
})
