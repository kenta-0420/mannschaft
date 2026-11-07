// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive, ref, type Ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import TeamDigest from '~/pages/teams/[slug]/timeline-digest.vue'
import OrgDigest from '~/pages/organizations/[slug]/timeline-digest.vue'

const mocks = vi.hoisted(() => ({
  route: null as unknown,
  shell: null as unknown,
  list: vi.fn(),
  teamDetail: vi.fn(),
  orgDetail: vi.fn(),
  notifyError: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useTimelineDigestApi', () => () => ({
  listDigests: mocks.list,
  deleteDigest: vi.fn(),
  publishDigest: vi.fn(),
  regenerateDigest: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({ error: mocks.notifyError, success: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (value: string) => value }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.teamDetail }))
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization: mocks.orgDetail }))
vi.mock('~/composables/useTeamShellContext', () => ({ useTeamShellContext: () => mocks.shell }))
vi.mock('~/composables/useOrgShellContext', () => ({ useOrgShellContext: () => mocks.shell }))

const Table = defineComponent({
  name: 'DataTable',
  props: ['value', 'loading'],
  setup: () => () => h('div'),
})
const Generate = defineComponent({
  name: 'DigestGenerateDialog',
  props: ['scopeType', 'scopeId', 'visible'],
  setup: () => () => h('div'),
})
const Config = defineComponent({
  name: 'DigestConfigForm',
  props: ['scopeType', 'scopeId'],
  setup: () => () => h('div'),
})
const Dialog = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', slots.default?.()),
})
const Loading = defineComponent({ name: 'PageLoading', setup: () => () => h('div') })
const LoadError = defineComponent({
  name: 'DashboardErrorState',
  props: ['error'],
  emits: ['retry'],
  setup:
    (_, { emit }) =>
    () =>
      h('button', { onClick: () => emit('retry') }, 'retry'),
})
interface Scope {
  id: string
  slug: string
  numericId?: number
}
let entity: Ref<Scope | null>
let route: { params: { slug: string } }
let wrapper: VueWrapper | undefined
const empty = () => ({ data: [], meta: { nextCursor: null, hasNext: false, limit: 20 } })

function deferred() {
  let resolve!: (value: {
    data: Array<{ id: number }>
    meta: ReturnType<typeof empty>['meta']
  }) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<Parameters<typeof resolve>[0]>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function detailDeferred() {
  let resolve!: (value: { data: { numericId: number | null } }) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<Parameters<typeof resolve>[0]>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.list.mockReset()
  mocks.list.mockResolvedValue(empty())
  // 既存のshell遅延解決試験では、詳細APIは未settleのままにしてshell側の更新を観測する。
  mocks.teamDetail.mockReset().mockImplementation(() => new Promise(() => {}))
  mocks.orgDetail.mockReset().mockImplementation(() => new Promise(() => {}))
  route = reactive({ params: { slug: 'public-a' } })
  entity = ref({ id: 'public-a', slug: 'public-a', numericId: 101 })
  mocks.route = route
  mocks.shell = { team: entity, org: entity, isAdmin: ref(true), isAdminOrDeputy: ref(true) }
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe.each([
  { scopeType: 'TEAM', component: TeamDigest },
  { scopeType: 'ORGANIZATION', component: OrgDigest },
])('$scopeType ダイジェストの画面結線', ({ scopeType, component }) => {
  function render() {
    wrapper = mount(component, {
      global: {
        mocks: { $t: (key: string) => key },
        stubs: {
          DataTable: Table,
          DigestGenerateDialog: Generate,
          DigestConfigForm: Config,
          Dialog,
          Button: true,
          Column: true,
          PageLoading: Loading,
          DashboardErrorState: LoadError,
          PageHeader: true,
        },
      },
    })
    return wrapper
  }

  it('公開slugではなく内部数値IDを一覧・設定・生成Dialogへ渡す', async () => {
    const view = render()
    await flushPromises()

    expect(mocks.list.mock.calls[0]?.slice(0, 2)).toEqual([scopeType, 101])
    expect(view.findComponent(Config).props()).toMatchObject({ scopeType, scopeId: 101 })
    expect(view.findComponent(Generate).props()).toMatchObject({ scopeType, scopeId: 101 })
  })

  it('ID未解決ではAPIを呼ばず成功空表示を出さず、解決後に取得する', async () => {
    entity.value = null
    const view = render()
    await flushPromises()

    expect(mocks.list).not.toHaveBeenCalled()
    expect(view.findComponent(Table).exists()).toBe(false)
    expect(view.findComponent(Loading).exists()).toBe(true)

    entity.value = { id: 'public-a', slug: 'public-a', numericId: 101 }
    await flushPromises()
    expect(mocks.list.mock.calls[0]?.slice(0, 2)).toEqual([scopeType, 101])
  })

  it('slug切替中は前IDを使わず、切替先取得後に旧応答を表示しない', async () => {
    const old = deferred()
    mocks.list.mockReturnValueOnce(old.promise)
    const view = render()
    await flushPromises()
    const oldConfig = view.findComponent(Config).vm
    const oldGenerate = view.findComponent(Generate).vm

    route.params.slug = 'public-b'
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(view.findComponent(Table).exists()).toBe(false)

    mocks.list.mockResolvedValueOnce({ ...empty(), data: [{ id: 2020 }] })
    entity.value = { id: 'public-b', slug: 'public-b', numericId: 202 }
    await flushPromises()
    expect(mocks.list.mock.calls[1]?.slice(0, 2)).toEqual([scopeType, 202])
    expect(view.findComponent(Table).props('value')).toEqual([{ id: 2020 }])
    expect(view.findComponent(Config).vm).not.toBe(oldConfig)
    expect(view.findComponent(Generate).vm).not.toBe(oldGenerate)

    old.resolve({ ...empty(), data: [{ id: 1010 }] })
    await flushPromises()
    expect(view.findComponent(Table).props('value')).toEqual([{ id: 2020 }])
  })

  it('切替前リクエストの遅延失敗は切替先へエラー通知しない', async () => {
    const old = deferred()
    mocks.list.mockReturnValueOnce(old.promise)
    render()
    await flushPromises()

    route.params.slug = 'public-b'
    entity.value = { id: 'public-b', slug: 'public-b', numericId: 202 }
    await flushPromises()
    old.reject({ statusCode: 403 })
    await flushPromises()

    expect(mocks.notifyError).not.toHaveBeenCalled()
  })

  const detailApi = () => (scopeType === 'TEAM' ? mocks.teamDetail : mocks.orgDetail)

  it('非shell: fresh mountとremountはshellが永久nullでもslugから数値IDを取得する', async () => {
    entity.value = null
    const first = detailDeferred()
    detailApi().mockReturnValueOnce(first.promise)
    let view = render()
    await flushPromises()

    expect(detailApi()).toHaveBeenCalledExactlyOnceWith('public-a')
    expect(mocks.list).not.toHaveBeenCalled()
    expect(view.findComponent(Loading).exists()).toBe(true)
    expect(view.findComponent(Table).exists()).toBe(false)

    first.resolve({ data: { numericId: 101 } })
    await flushPromises()
    expect(entity.value).toBeNull()
    expect(mocks.list).toHaveBeenCalledExactlyOnceWith(scopeType, 101)
    expect(view.findComponent(Config).props('scopeId')).toBe(101)
    expect(view.findComponent(Generate).props('scopeId')).toBe(101)

    view.unmount()
    wrapper = undefined
    const second = detailDeferred()
    detailApi().mockReturnValueOnce(second.promise)
    view = render()
    await flushPromises()
    expect(detailApi().mock.calls.map((call) => call[0])).toEqual(['public-a', 'public-a'])
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(view.findComponent(Loading).exists()).toBe(true)
    second.resolve({ data: { numericId: 101 } })
    await flushPromises()
    expect(mocks.list.mock.calls.map((call) => call.slice(0, 2))).toEqual([
      [scopeType, 101],
      [scopeType, 101],
    ])
    expect(view.findComponent(Loading).exists()).toBe(false)
  })

  it('非shell: 詳細取得失敗をerror面へ出し再試行で一覧へ復帰する', async () => {
    entity.value = null
    detailApi().mockRejectedValueOnce({ statusCode: 503 })
    const view = render()
    await flushPromises()

    expect(view.findComponent(LoadError).exists()).toBe(true)
    expect(view.findComponent(Loading).exists()).toBe(false)
    expect(view.findComponent(Table).exists()).toBe(false)
    expect(mocks.list).not.toHaveBeenCalled()
    detailApi().mockResolvedValueOnce({ data: { numericId: 101 } })
    await view.findComponent(LoadError).trigger('click')
    await flushPromises()
    expect(detailApi().mock.calls.map((call) => call[0])).toEqual(['public-a', 'public-a'])
    expect(mocks.list).toHaveBeenCalledExactlyOnceWith(scopeType, 101)
    expect(view.findComponent(LoadError).exists()).toBe(false)
    expect(view.findComponent(Table).exists()).toBe(true)
  })

  it('非shell: A→B→Aで旧詳細のsuccess・failure・finallyが最新解決を変更しない', async () => {
    entity.value = null
    const firstA = detailDeferred(),
      oldB = detailDeferred(),
      latestA = detailDeferred()
    detailApi()
      .mockReturnValueOnce(firstA.promise)
      .mockReturnValueOnce(oldB.promise)
      .mockReturnValueOnce(latestA.promise)
    const view = render()
    await flushPromises()
    route.params.slug = 'public-b'
    await flushPromises()
    route.params.slug = 'public-a'
    await flushPromises()
    expect(detailApi().mock.calls.map((call) => call[0])).toEqual([
      'public-a',
      'public-b',
      'public-a',
    ])

    oldB.reject({ statusCode: 403 })
    firstA.resolve({ data: { numericId: 909 } })
    await flushPromises()
    expect(mocks.list).not.toHaveBeenCalled()
    expect(mocks.notifyError).not.toHaveBeenCalled()
    expect(view.findComponent(Loading).exists()).toBe(true)
    expect(view.findComponent(LoadError).exists()).toBe(false)
    expect(view.findComponent(Config).exists()).toBe(false)
    latestA.resolve({ data: { numericId: 101 } })
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledExactlyOnceWith(scopeType, 101)
    expect(view.findComponent(Config).props('scopeId')).toBe(101)
    expect(view.findComponent(Table).props('loading')).toBe(false)
  })

  it.each([
    { label: 'null', numericId: null },
    { label: '0', numericId: 0 },
    { label: 'out-of-safe', numericId: Number.MAX_SAFE_INTEGER + 1 },
  ])('非shell: 詳細の不正ID $label を拒否してdigest APIを呼ばない', async ({ numericId }) => {
    entity.value = null
    detailApi().mockResolvedValueOnce({ data: { numericId } })
    const view = render()
    await flushPromises()

    expect(view.findComponent(LoadError).exists()).toBe(true)
    expect(view.findComponent(Loading).exists()).toBe(false)
    expect(view.findComponent(Table).exists()).toBe(false)
    expect(view.findComponent(Config).exists()).toBe(false)
    expect(view.findComponent(Generate).exists()).toBe(false)
    expect(mocks.list).not.toHaveBeenCalled()
  })
})
