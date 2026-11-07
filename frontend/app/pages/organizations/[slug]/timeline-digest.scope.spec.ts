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

beforeEach(() => {
  vi.clearAllMocks()
  mocks.list.mockReset()
  mocks.list.mockResolvedValue(empty())
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
})
