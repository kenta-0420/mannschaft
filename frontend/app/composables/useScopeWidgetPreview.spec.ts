// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, reactive, type EffectScope } from 'vue'
import { parseBlogPostReadQuery } from '~/utils/blogPostReadQuery'
import {
  useScopeWidgetPreview,
  type ScopeWidgetPreviewContext,
  type ScopeWidgetPreviewTarget,
} from './useScopeWidgetPreview'

const mocks = vi.hoisted(() => ({
  bulletin: vi.fn(),
  blog: vi.fn(),
  todo: vi.fn(),
  schedule: vi.fn(),
  chat: vi.fn(),
  error: vi.fn(),
}))
vi.mock('~/composables/useBulletinApi', () => ({
  useBulletinApi: () => ({ getScopedThread: mocks.bulletin }),
}))
vi.mock('~/composables/useBlogApi', () => ({ useBlogApi: () => ({ getPost: mocks.blog }) }))
vi.mock('~/composables/useTodoApi', () => ({ useTodoApi: () => ({ getTodo: mocks.todo }) }))
vi.mock('~/composables/schedule/useScheduleCrud', () => ({
  useScheduleCrud: () => ({ getSchedule: mocks.schedule }),
}))
vi.mock('~/composables/chat/useChatChannels', () => ({
  useChatChannels: () => ({ getChannel: mocks.chat }),
}))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: mocks.error }),
}))
const kinds = ['schedule', 'bulletin', 'blog', 'chat', 'todo'] as const
const scopes: EffectScope[] = []
beforeEach(() => vi.resetAllMocks())
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop())
})

function create(scopeType: ScopeWidgetPreviewContext['scopeType'] = 'TEAM') {
  const context = reactive<ScopeWidgetPreviewContext>({
    scopeType,
    scopeSlug: 'my-scope',
    numericScopeId: '7',
  })
  const scope = effectScope()
  scopes.push(scope)
  return { ...scope.run(() => useScopeWidgetPreview(() => ({ ...context })))!, context, scope }
}
function item(kind: ScopeWidgetPreviewTarget['kind'], id = 12): ScopeWidgetPreviewTarget {
  return { kind, id, title: '概要タイトル', ...(kind === 'blog' ? { slug: 'article-name' } : {}) }
}
function payload(
  kind: (typeof kinds)[number],
  scopeType: ScopeWidgetPreviewContext['scopeType'] = 'TEAM',
  id = 12,
) {
  const scope =
    scopeType === 'TEAM' ? { teamId: 7, organizationId: null } : { teamId: null, organizationId: 7 }
  switch (kind) {
    case 'bulletin':
      return { id, title: '詳細タイトル', body: '<p>API本文</p>', scopeType, scopeId: 7 }
    case 'blog':
      return {
        id,
        content: { title: '詳細タイトル', body: '**API本文**', slug: 'article-name' },
        scope: { ...scope, userId: null },
      }
    case 'chat':
      return {
        id,
        identity: scope,
        meta: { name: '詳細タイトル', description: 'API説明' },
        memberCount: 3,
        lastMessage: { lastMessagePreview: '最新文' },
      }
    case 'todo':
      return {
        id,
        content: { title: '詳細タイトル', description: 'API説明' },
        scope: { scopeType, scopeId: 7 },
        schedule: { dueDate: null },
      }
    case 'schedule':
      return {
        id,
        content: { title: '詳細タイトル', location: '会議室', attendanceRequired: false },
        time: {
          startAt: '2026-10-24T10:00:00+09:00',
          endAt: '2026-10-24T12:00:00+09:00',
          allDay: false,
        },
        targetCount: 4,
      }
  }
}
function api(kind: (typeof kinds)[number]) {
  return mocks[kind]
}
function deferred() {
  let resolve!: (value: unknown) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

describe.each(['TEAM', 'ORGANIZATION'] as const)('%s 詳細プレビュー', (scopeType) => {
  const plural = scopeType === 'TEAM' ? 'teams' : 'organizations'
  const type = scopeType === 'TEAM' ? 'team' : 'organization'
  it('AC3: ブログqueryの実parserへLong正準文字列を渡す', async () => {
    const preview = create(scopeType)
    mocks.blog.mockImplementation(
      async (_slug: string, query: Readonly<Record<string, unknown>>) => {
        parseBlogPostReadQuery(query)
        return { data: payload('blog', scopeType) }
      },
    )
    await preview.open(item('blog'))
    expect(preview.state.value).toBe('FULL')
    expect(mocks.blog).toHaveBeenCalledWith('article-name', {
      [scopeType === 'TEAM' ? 'teamId' : 'organizationId']: '7',
    })
  })
  it.each(kinds)('AC2/3/9: %sは選択時だけ詳細APIを呼び正規リンクと実本文を返す', async (kind) => {
    const preview = create(scopeType)
    expect(kinds.every((key) => api(key).mock.calls.length === 0)).toBe(true)
    api(kind).mockResolvedValue({ data: payload(kind, scopeType) })

    await preview.open(item(kind))

    expect(preview.state.value).toBe('FULL')
    expect(preview.detail.value?.title).toBe('詳細タイトル')
    expect(api(kind)).toHaveBeenCalledTimes(1)
    if (kind === 'bulletin') {
      expect(mocks.bulletin).toHaveBeenCalledWith(plural, '7', 12)
      expect(preview.detail.value?.body).toContain('API本文')
      expect(preview.detail.value?.route).toBe(`/${plural}/my-scope/bulletin?threadId=12`)
    } else if (kind === 'blog') {
      expect(mocks.blog).toHaveBeenCalledWith('article-name', {
        [scopeType === 'TEAM' ? 'teamId' : 'organizationId']: '7',
      })
      expect(preview.detail.value?.route).toEqual({
        path: '/blog/posts/article-name',
        query: { [scopeType === 'TEAM' ? 'teamId' : 'organizationId']: '7' },
      })
      expect(preview.detail.value?.body).toBe('**API本文**')
    } else if (kind === 'chat') {
      expect(mocks.chat).toHaveBeenCalledWith(12)
      expect(preview.detail.value?.route).toBe(`/${plural}/my-scope/chat`)
      expect(preview.detail.value?.fields).toContainEqual({
        label: 'lastMessage',
        value: '最新文',
        format: undefined,
      })
    } else {
      expect(api(kind)).toHaveBeenCalledWith(type, 'my-scope', 12)
      expect(preview.detail.value?.route).toBe(
        kind === 'todo'
          ? `/${plural}/my-scope/todos/12`
          : `/${plural}/my-scope/schedule?eventId=12`,
      )
      if (kind === 'todo') expect(preview.detail.value?.body).toBe('API説明')
      else
        expect(preview.detail.value?.fields).toContainEqual({
          label: 'location',
          value: '会議室',
          format: undefined,
        })
    }
  })
  it('AC4: カレンダー0件と次予定なしを表示し追加GETをしない', async () => {
    const preview = create(scopeType)
    await preview.open({
      kind: 'calendar',
      title: 'カレンダー',
      calendar: { events_today: 0, events_this_week: 0, next_event: null, days_with_events: [] },
    })
    expect(preview.state.value).toBe('FULL')
    expect(preview.detail.value?.fields.map((field) => field.value)).toEqual([
      0,
      0,
      'swipeWidgets.list.noNextEvent',
    ])
    expect(preview.detail.value?.route).toBe(`/${plural}/my-scope/schedule`)
    expect(kinds.every((key) => api(key).mock.calls.length === 0)).toBe(true)
  })
})

describe('詳細取得の境界・競合', () => {
  it.each(kinds)('AC5: %sの別ID応答を拒否し本文/CTAを保持しない', async (kind) => {
    const preview = create()
    api(kind).mockResolvedValue({ data: payload(kind, 'TEAM', 99) })
    await preview.open(item(kind))
    expect(preview.state.value).toBe('ERROR')
    expect(preview.detail.value).toBeNull()
  })
  it.each(['bulletin', 'blog', 'chat', 'todo'] as const)(
    'AC5: %sの他所属応答を拒否する',
    async (kind) => {
      const preview = create()
      api(kind).mockResolvedValue({ data: payload(kind, 'ORGANIZATION') })
      await preview.open(item(kind))
      expect(preview.state.value).toBe('ERROR')
      expect(preview.detail.value).toBeNull()
    },
  )
  it('AC5: ブログのslug・複数所属応答を拒否する', async () => {
    const preview = create()
    for (const invalid of [
      { ...payload('blog'), content: { slug: 'different', title: '不一致', body: '隠す本文' } },
      { ...payload('blog'), scope: { teamId: 7, organizationId: 8, userId: null } },
    ]) {
      mocks.blog.mockResolvedValue({ data: invalid })
      await preview.open(item('blog'))
      expect(preview.detail.value).toBeNull()
      expect(preview.state.value).toBe('ERROR')
    }
  })
  it.each([403, 404])('AC5: %sで前の本文を消して再試行なしの状態にする', async (status) => {
    const preview = create()
    mocks.todo
      .mockResolvedValueOnce({ data: payload('todo') })
      .mockRejectedValueOnce({ statusCode: status })
    await preview.open(item('todo'))
    await preview.open(item('todo'))
    expect(preview.state.value).toBe('UNAVAILABLE')
    expect(preview.detail.value).toBeNull()
  })
  it('AC5: 通信失敗から同じ対象を再取得でき、空説明・期限なしも成功する', async () => {
    const preview = create()
    mocks.todo.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({
      data: { ...payload('todo'), content: { title: '空', description: null } },
    })
    await preview.open(item('todo'))
    expect(preview.state.value).toBe('ERROR')
    await preview.retry()
    expect(mocks.todo).toHaveBeenLastCalledWith('team', 'my-scope', 12)
    expect(preview.state.value).toBe('FULL')
    expect(preview.detail.value?.body).toBe('')
    expect(preview.detail.value?.fields).toContainEqual({
      label: 'due',
      value: 'swipeWidgets.preview.noDue',
      format: 'translation',
    })
  })
  it.each([undefined, '', '0', 'NaN'])(
    'AC5: 内部ID未解決(%s)でAPIを呼ばない',
    async (numericScopeId) => {
      const preview = create()
      preview.context.numericScopeId = numericScopeId
      await preview.open(item('bulletin'))
      expect(preview.state.value).toBe('ERROR')
      expect(mocks.bulletin).not.toHaveBeenCalled()
    },
  )
  it.each(['resolve', 'reject'] as const)(
    'AC6: 連続選択の遅い旧応答(%s)を混入させない',
    async (result) => {
      const preview = create()
      const old = deferred()
      mocks.todo
        .mockReturnValueOnce(old.promise)
        .mockResolvedValueOnce({ data: payload('todo', 'TEAM', 13) })
      const pending = preview.open(item('todo'))
      await preview.open(item('todo', 13))
      if (result === 'resolve') old.resolve({ data: payload('todo') })
      else old.reject({ statusCode: 403 })
      await pending
      expect(preview.target.value?.id).toBe(13)
      expect(preview.detail.value?.route).toBe('/teams/my-scope/todos/13')
      expect(preview.state.value).toBe('FULL')
      expect(mocks.error).not.toHaveBeenCalled()
    },
  )
  it('AC5/6: 内部IDの遅延解決後に現対象だけ再試行できる', async () => {
    const preview = create()
    preview.context.numericScopeId = undefined
    await preview.open(item('bulletin'))
    expect(mocks.bulletin).not.toHaveBeenCalled()
    preview.context.numericScopeId = '7'
    mocks.bulletin.mockResolvedValue({ data: payload('bulletin') })
    await preview.retry()
    expect(mocks.bulletin).toHaveBeenCalledWith('teams', '7', 12)
    expect(preview.state.value).toBe('FULL')
  })
  it.each(['close', 'scope', 'dispose'] as const)('AC6: %s後の旧応答を破棄する', async (action) => {
    const preview = create()
    const old = deferred()
    mocks.todo.mockReturnValue(old.promise)
    const pending = preview.open(item('todo'))
    if (action === 'close') preview.close()
    if (action === 'scope') preview.context.scopeSlug = 'other-scope'
    if (action === 'dispose') preview.scope.stop()
    old.resolve({ data: payload('todo') })
    await pending
    await preview.retry()
    expect(preview.state.value).toBe('CLOSED')
    expect(preview.detail.value).toBeNull()
    expect(mocks.todo).toHaveBeenCalledTimes(1)
  })
})
