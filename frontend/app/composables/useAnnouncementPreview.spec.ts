// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, type EffectScope } from 'vue'
import type { AnnouncementPreviewResponse, AnnouncementPreviewTarget } from './useAnnouncementPreview'
import { isAnnouncementSourceUrl, useAnnouncementPreview } from './useAnnouncementPreview'

const { api, capture, warn, notificationError } = vi.hoisted(() => ({
  api: vi.fn(), capture: vi.fn(), warn: vi.fn(), notificationError: vi.fn(),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({ captureQuiet: capture }) }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => ({ warn, error: notificationError }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

const target: AnnouncementPreviewTarget = { id: 12, scopeType: 'ORGANIZATION', scopeId: 7, title: '連絡', isRead: false }
function full(id = 12, body: string | undefined = '本文'): AnnouncementPreviewResponse {
  return {
    feedId: id, scopeType: 'ORGANIZATION', scopeId: 7, accessState: 'FULL',
    sourceType: 'BLOG_POST', sourceId: 31, sourceUrl: '/blog/posts/notice?organizationId=7',
    blogPost: { id: 31, content: { body } }, bulletinThread: null, attachments: [],
  }
}
function locked(): AnnouncementPreviewResponse {
  return { feedId: 12, scopeType: 'ORGANIZATION', scopeId: 7, accessState: 'LOCKED', sourceType: null, sourceId: null, sourceUrl: null, blogPost: null, bulletinThread: null, attachments: [] }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
let scope: EffectScope
function create() {
  const onRead = vi.fn()
  const onUnavailable = vi.fn()
  scope = effectScope()
  const value = scope.run(() => useAnnouncementPreview({ onRead, onUnavailable }))!
  return { ...value, onRead, onUnavailable }
}
beforeEach(() => { vi.resetAllMocks() })
afterEach(() => { scope?.stop() })

describe('お知らせ本文プレビュー', () => {
  it('PREVIEW-02/11/17: 開くまで取得せず、項目の実scopeへGETし描画後だけ既読POSTする', async () => {
    const preview = create()
    expect(api).not.toHaveBeenCalled()
    api.mockResolvedValue({ data: full() })
    await preview.open(target)
    expect(api).toHaveBeenCalledTimes(1)
    expect(api.mock.calls[0]?.[0]).toBe('/api/v1/organizations/7/announcements/12/preview')
    expect(preview.state.value).toBe('FULL')
    await preview.markDisplayed()
    expect(api.mock.calls[1]?.[0]).toBe('/api/v1/organizations/7/announcements/12/read')
    expect(preview.onRead).toHaveBeenCalledWith(target)
    await preview.markDisplayed()
    expect(api).toHaveBeenCalledTimes(2)
  })

  it.each(['', undefined])('PREVIEW-03/11: 空・省略本文(%s)はFULL、既読済みでも最新GETをする', async (body) => {
    const preview = create()
    const response = full()
    response.blogPost = { id: 31, content: { body } }
    api.mockResolvedValue({ data: response })
    await preview.open({ ...target, isRead: true })
    await preview.markDisplayed()
    expect(preview.state.value).toBe('FULL')
    expect(api).toHaveBeenCalledTimes(1)
  })

  it('PREVIEW-07/11: LOCKEDは本文を持たず既読にしない', async () => {
    const preview = create()
    api.mockResolvedValue({ data: locked() })
    await preview.open(target)
    await preview.markDisplayed()
    expect(preview.state.value).toBe('LOCKED')
    expect(preview.preview.value).toBeNull()
    expect(api).toHaveBeenCalledTimes(1)
  })

  it('PREVIEW-03/11: null本文でも認可FULLを空本文として保持する', async () => {
    const preview = create()
    api.mockResolvedValue({ data: { ...full(), blogPost: { id: 31, content: { body: null } } } })
    await preview.open({ ...target, isRead: true })
    await preview.markDisplayed()
    expect(preview.state.value).toBe('FULL')
    expect(api).toHaveBeenCalledTimes(1)
  })

  it('PREVIEW-07: LOCKEDに秘密本文を含む契約違反はfail-closed', async () => {
    const preview = create()
    api.mockResolvedValue({ data: { ...locked(), blogPost: { content: { body: '秘密' } } } })
    await preview.open(target)
    expect(preview.state.value).toBe('ERROR')
    expect(preview.preview.value).toBeNull()
  })

  it('PREVIEW-10: Aの遅い応答がBの本文を上書きしない', async () => {
    const preview = create()
    const first = deferred<{ data: AnnouncementPreviewResponse }>()
    api.mockReturnValueOnce(first.promise).mockResolvedValueOnce({ data: full(13, 'B') })
    const pending = preview.open(target)
    const signal = api.mock.calls[0]?.[1].signal as AbortSignal
    await preview.open({ ...target, id: 13 })
    expect(signal.aborted).toBe(true)
    first.resolve({ data: full(12, 'A') })
    await pending
    expect(preview.preview.value?.feedId).toBe(13)
    expect(preview.preview.value?.blogPost?.content?.body).toBe('B')
  })

  it('PREVIEW-10/11: GET完了前closeは遅い応答を破棄しPOSTしない', async () => {
    const preview = create()
    const first = deferred<{ data: AnnouncementPreviewResponse }>()
    api.mockReturnValue(first.promise)
    const pending = preview.open(target)
    preview.close()
    first.resolve({ data: full() })
    await pending
    await preview.markDisplayed()
    expect(preview.state.value).toBe('CLOSED')
    expect(preview.preview.value).toBeNull()
    expect(api).toHaveBeenCalledTimes(1)
  })

  it.each([true, false])('PREVIEW-10: 旧GETの失敗は閉じた後(%s)やB表示に混入しない', async (closed) => {
    const preview = create()
    const first = deferred<{ data: AnnouncementPreviewResponse }>()
    api.mockReturnValueOnce(first.promise).mockResolvedValueOnce({ data: full(13, 'B') })
    const pending = preview.open(target)
    if (closed) preview.close()
    else await preview.open({ ...target, id: 13 })
    first.reject({ statusCode: 404, data: { error: { code: 'ANNOUNCE_001' } } })
    await pending
    expect(preview.state.value).toBe(closed ? 'CLOSED' : 'FULL')
    expect(preview.error.value).toBeNull()
    expect(preview.onUnavailable).not.toHaveBeenCalled()
    expect(capture).not.toHaveBeenCalled()
    if (!closed) expect(preview.preview.value?.feedId).toBe(13)
  })

  it('PREVIEW-11: 表示済みPOST開始後closeは当該feed一覧だけ既読更新する', async () => {
    const preview = create()
    const read = deferred<unknown>()
    api.mockResolvedValueOnce({ data: full() }).mockReturnValueOnce(read.promise)
    await preview.open(target)
    const pending = preview.markDisplayed()
    preview.close()
    read.resolve({ data: {} })
    await pending
    expect(preview.onRead).toHaveBeenCalledWith(target)
    expect(preview.state.value).toBe('CLOSED')
    expect(preview.preview.value).toBeNull()
  })

  it('PREVIEW-09: 取得失敗を空扱いせず手動再試行する', async () => {
    const preview = create()
    api.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ data: full() })
    await preview.open(target)
    expect(preview.state.value).toBe('ERROR')
    expect(preview.onUnavailable).not.toHaveBeenCalled()
    await preview.retry()
    expect(preview.state.value).toBe('FULL')
  })

  it('PREVIEW-10/11: Aの既読POST完了はBの表示を変更せずA一覧だけへ通知する', async () => {
    const preview = create()
    const read = deferred<unknown>()
    api.mockResolvedValueOnce({ data: full() }).mockReturnValueOnce(read.promise)
      .mockResolvedValueOnce({ data: full(13, 'B') })
    await preview.open(target)
    const pending = preview.markDisplayed()
    await preview.open({ ...target, id: 13 })
    read.resolve({ data: {} })
    await pending
    expect(preview.onRead.mock.calls).toEqual([[target]])
    expect(preview.preview.value?.feedId).toBe(13)
    expect(preview.state.value).toBe('FULL')
  })

  it('PREVIEW-12: read404は表示本文を消去して当該カードを除去する', async () => {
    const preview = create()
    api.mockResolvedValueOnce({ data: full() }).mockRejectedValueOnce({ statusCode: 404, data: { error: { code: 'ANNOUNCE_001' } } })
    await preview.open(target)
    await preview.markDisplayed()
    expect(preview.preview.value).toBeNull()
    expect(preview.state.value).toBe('UNAVAILABLE')
    expect(preview.onUnavailable).toHaveBeenCalledWith(target)
    expect(preview.onRead).not.toHaveBeenCalled()
  })

  it.each(['LOCKED', 'ERROR'] as const)('PREVIEW-11: %sを閉じても既読POSTしない', async (state) => {
    const preview = create()
    if (state === 'LOCKED') api.mockResolvedValueOnce({ data: locked() })
    else api.mockRejectedValueOnce(new Error('offline'))
    await preview.open(target)
    expect(preview.state.value).toBe(state)
    preview.close()
    await preview.markDisplayed()
    expect(api).toHaveBeenCalledTimes(1)
    expect(preview.onRead).not.toHaveBeenCalled()
  })

  it('PREVIEW-09/10: 再試行前の古い応答は新しい結果へ混入しない', async () => {
    const preview = create()
    const old = deferred<{ data: AnnouncementPreviewResponse }>()
    api.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ data: full(12, '最新') })
    const pending = preview.open(target)
    await preview.retry()
    old.resolve({ data: full(12, '古い') })
    await pending
    expect(preview.preview.value?.blogPost?.content?.body).toBe('最新')
    expect(api).toHaveBeenCalledTimes(2)
  })

  it('PREVIEW-05/09: ANNOUNCE_001は項目除去・本文なし利用不可', async () => {
    const preview = create()
    api.mockRejectedValue({ statusCode: 404, data: { error: { code: 'ANNOUNCE_001' } } })
    await preview.open(target)
    expect(preview.state.value).toBe('UNAVAILABLE')
    expect(preview.onUnavailable).toHaveBeenCalledWith(target)
    expect(preview.preview.value).toBeNull()
  })

  it('PREVIEW-12: readの5xxは本文保持・未読、401は本文消去', async () => {
    const preview = create()
    api.mockResolvedValueOnce({ data: full() }).mockRejectedValueOnce({ statusCode: 500 })
    await preview.open(target)
    await preview.markDisplayed()
    expect(preview.state.value).toBe('FULL')
    expect(preview.onRead).not.toHaveBeenCalled()
    api.mockRejectedValueOnce({ statusCode: 401 })
    await preview.markDisplayed()
    expect(preview.state.value).toBe('ERROR')
    expect(preview.preview.value).toBeNull()
  })

  it('PREVIEW-08/15: 元URLは対象に対応した内部pathだけ許可する', () => {
    expect(isAnnouncementSourceUrl('/blog/posts/notice?teamId=8', 'BLOG_POST')).toBe(true)
    expect(isAnnouncementSourceUrl('/organizations/org/bulletin?threadId=8', 'BULLETIN_THREAD')).toBe(true)
    expect(isAnnouncementSourceUrl('/blog/posts/notice?teamId=8', 'BLOG_POST', target)).toBe(false)
    expect(isAnnouncementSourceUrl('/blog/posts/notice?organizationId=8', 'BLOG_POST', target)).toBe(false)
    expect(isAnnouncementSourceUrl('/teams/team/bulletin?threadId=8', 'BULLETIN_THREAD', target)).toBe(false)
    for (const url of ['//evil.test/page', 'https://evil.test', '/blog/posts/notice?teamId=8&organizationId=3', '/teams/team/bulletin?threadId=-1', 'javascript:alert(1)']) {
      expect(isAnnouncementSourceUrl(url, 'BLOG_POST')).toBe(false)
    }
  })
})
