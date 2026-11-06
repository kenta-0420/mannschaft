// @vitest-environment happy-dom
// Nuxt pageは使わず、実Vueのprovide/injectとcomposableの取得・変換を検証する。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, reactive } from 'vue'
import { flushPromises, mount as mountVue, type VueWrapper } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import * as actions from '~/composables/usePersonalActionRequired'
import { getAuthSessionContext } from '~/composables/authSessionContext'

const fixture = vi.hoisted(() => ({
  api: vi.fn(),
  auth: { user: null, loadFromStorage: vi.fn() } as { user: { id: number } | null; loadFromStorage: () => void },
  proxy: { isPinned: false },
  guardian: { isActingAs: false },
  impersonation: { isImpersonating: false },
}))
mockNuxtImport('useApi', () => () => fixture.api)
mockNuxtImport('useAuthStore', () => () => fixture.auth)
mockNuxtImport('useProxyDeskStore', () => () => fixture.proxy)
mockNuxtImport('useGuardianshipSwitchStore', () => () => fixture.guardian)
mockNuxtImport('useAdminImpersonationStore', () => () => fixture.impersonation)

type Read = (options?: { shareInitial?: boolean }) => ReturnType<ReturnType<typeof actions.usePersonalActionRequired>['fetchActionRequired']>
type Response = { data: { items: { item_type: string; scope_type: string; scope_id: number; scope_slug: string; scope_name: string; item_id: string; title: string; circulated_at: null; deadline: null; starts_at: null }[]; total_count: number } }
const response = (title = '初期項目'): Response => ({ data: { items: [{ item_type: 'SURVEY', scope_type: 'TEAM', scope_id: 1, scope_slug: 'synthetic-team', scope_name: '試験チーム', item_id: '1', title, circulated_at: null, deadline: null, starts_at: null }], total_count: 1 } })
let clients: Read[] = []
let stopInitialSharing: (() => void) | undefined
let wrappers: VueWrapper[] = []
let pending: { resolve: (value: Response) => void; reject: (error: Error) => void }[] = []
const Consumer = defineComponent({
  setup() {
    clients.push(actions.usePersonalActionRequired().fetchActionRequired as Read)
    return () => h('div')
  },
})
const Provider = defineComponent({
  setup() {
    // 旧実装でも通常の実GETを通し、重複2本という挙動でredを確認する。
    const provider = (actions as typeof actions & { provideInitialPersonalActionRequired?: () => (() => void) | undefined }).provideInitialPersonalActionRequired
    stopInitialSharing = provider?.()
    return () => h('div', [h(Consumer), h(Consumer)])
  },
})
async function mount(withProvider = true) {
  const before = clients.length
  wrappers.push(mountVue(withProvider ? Provider : defineComponent({ setup: () => () => h('div', [h(Consumer), h(Consumer)]) })))
  return clients.slice(before)
}
const initial = { shareInitial: true }
beforeEach(() => {
  fixture.auth = reactive({ user: { id: 101 } as { id: number } | null, loadFromStorage: vi.fn() })
  fixture.proxy = reactive({ isPinned: false })
  fixture.guardian = reactive({ isActingAs: false })
  fixture.impersonation = reactive({ isImpersonating: false })
  clients = []; wrappers = []; pending = []; stopInitialSharing = undefined
  fixture.api.mockReset().mockImplementation(() => new Promise<Response>((resolve, reject) => { pending.push({ resolve, reject }) }))
})
afterEach(async () => {
  for (const wrapper of wrappers) wrapper.unmount()
  await nextTick(); await flushPromises()
  getAuthSessionContext(fixture.auth as Parameters<typeof getAuthSessionContext>[0]).dispose()
})

describe('個人パネル初回の要対応GETだけを実行中共有する', () => {
  it('同じproviderの初期2callerは1HTTP、変換後のitemsとitemは各callerに分離する', async () => {
    const [first, second] = await mount()
    const a = first!(initial), b = second!(initial)
    expect(fixture.api).toHaveBeenCalledTimes(1)
    pending[0]!.resolve(response())
    const [one, two] = await Promise.all([a, b])
    expect(one).toEqual(two)
    expect(one.items).not.toBe(two.items)
    expect(one.items[0]).not.toBe(two.items[0])
    one.items[0]!.title = '片方だけの更新'
    expect(two.items[0]!.title).toBe('初期項目')
  })
  it('空成功を保持せずsettle後の非重複初期取得は新GETになる', async () => {
    const [first, second] = await mount()
    const a = first!(initial)
    pending[0]!.resolve({ data: { items: [], total_count: 0 } })
    expect(await a).toEqual({ items: [], totalCount: 0 })
    const b = second!(initial)
    expect(fixture.api).toHaveBeenCalledTimes(2)
    pending[1]!.resolve(response('保存変更後'))
    expect((await b).items[0]!.title).toBe('保存変更後')
  })
  it('refreshは新GETで、refresh開始後の初期callerも古いpendingへjoinしない', async () => {
    const [first, second] = await mount()
    const old = first!(initial), fresh = second!(), late = second!(initial)
    expect(fixture.api).toHaveBeenCalledTimes(3)
    pending[1]!.resolve(response('更新後'))
    expect((await fresh).items[0]!.title).toBe('更新後')
    pending[2]!.resolve(response('後着の初期caller'))
    expect((await late).items[0]!.title).toBe('後着の初期caller')
    pending[0]!.resolve(response('更新前'))
    expect((await old).items[0]!.title).toBe('更新前')
  })
  it('共有失敗を両callerへ返し、失敗後のGETは再実行する', async () => {
    const [first, second] = await mount()
    const error = new Error('SYNTHETIC_ACTION_FAILURE')
    const reads = Promise.allSettled([first!(initial), second!(initial)])
    expect(fixture.api).toHaveBeenCalledTimes(1)
    pending[0]!.reject(error)
    expect(await reads).toEqual([{ status: 'rejected', reason: error }, { status: 'rejected', reason: error }])
    const retry = first!(initial)
    expect(fixture.api).toHaveBeenCalledTimes(2)
    pending[1]!.resolve(response())
    expect((await retry).totalCount).toBe(1)
  })
  it('設定操作は新規joinだけを停止し、既に取得中のcallerの成功を壊さない', async () => {
    const [first, second] = await mount()
    const old = first!(initial)
    stopInitialSharing?.()
    const fresh = second!(initial)
    expect(fixture.api).toHaveBeenCalledTimes(2)
    pending[1]!.resolve(response('設定操作後'))
    expect((await fresh).items[0]!.title).toBe('設定操作後')
    pending[0]!.resolve(response('既に取得中'))
    expect((await old).items[0]!.title).toBe('既に取得中')
  })
  it('providerなし・本人未確定の単独利用は従来どおり独立して取得する', async () => {
    const [first, second] = await mount(false)
    const reads = Promise.all([first!(initial), second!(initial)])
    expect(fixture.api).toHaveBeenCalledTimes(2)
    pending[0]!.resolve(response()); pending[1]!.resolve(response())
    await reads
    fixture.auth.user = null
    const [unrestoredFirst, unrestoredSecond] = await mount()
    const unrestored = Promise.all([unrestoredFirst!(initial), unrestoredSecond!(initial)])
    expect(fixture.api).toHaveBeenCalledTimes(4)
    pending[2]!.resolve(response()); pending[3]!.resolve(response())
    await unrestored
  })
  it('別providerには同じ本人でもpendingを共有しない', async () => {
    const [first] = await mount(), [second] = await mount()
    const reads = Promise.all([first!(initial), second!(initial)])
    expect(fixture.api).toHaveBeenCalledTimes(2)
    pending[0]!.resolve(response()); pending[1]!.resolve(response())
    await reads
  })
  it.each(['logout', 'same-session-id', 'other-account'])('%s境界で旧成功を拒否し新providerは正常に取得する', async boundary => {
    const [first, second] = await mount()
    const old = Promise.allSettled([first!(initial), second!(initial)])
    fixture.auth.user = null
    if (boundary !== 'logout') fixture.auth.user = { id: boundary === 'other-account' ? 202 : 101 }
    pending[0]!.resolve(response('旧応答'))
    expect((await old).every(result => result.status === 'rejected')).toBe(true)
    if (boundary !== 'logout') {
      const [nextFirst, nextSecond] = await mount()
      const fresh = Promise.all([nextFirst!(initial), nextSecond!(initial)])
      expect(fixture.api).toHaveBeenCalledTimes(2)
      pending[1]!.resolve(response('新本人'))
      expect((await fresh).every(result => result.items[0]!.title === '新本人')).toBe(true)
    }
  })
  it.each(['proxy', 'guardian', 'impersonation'] as const)('%s中は共有しない', async mode => {
    if (mode === 'proxy') fixture.proxy.isPinned = true
    if (mode === 'guardian') fixture.guardian.isActingAs = true
    if (mode === 'impersonation') fixture.impersonation.isImpersonating = true
    const [first, second] = await mount()
    const reads = Promise.all([first!(initial), second!(initial)])
    expect(fixture.api).toHaveBeenCalledTimes(2)
    pending[0]!.resolve(response()); pending[1]!.resolve(response())
    await reads
  })
  it.each(['proxy', 'guardian', 'impersonation'] as const)('%s開始後に通常モードへ戻っても旧pending成功を採用しない', async mode => {
    const [first, second] = await mount()
    const reads = Promise.allSettled([first!(initial), second!(initial)])
    if (mode === 'proxy') { fixture.proxy.isPinned = true; fixture.proxy.isPinned = false }
    if (mode === 'guardian') { fixture.guardian.isActingAs = true; fixture.guardian.isActingAs = false }
    if (mode === 'impersonation') { fixture.impersonation.isImpersonating = true; fixture.impersonation.isImpersonating = false }
    pending[0]!.resolve(response())
    expect((await reads).every(result => result.status === 'rejected')).toBe(true)
  })
  it('provider破棄後の成功を拒否し再入場のproviderは新GETで回復する', async () => {
    const [first, second] = await mount()
    const old = Promise.allSettled([first!(initial), second!(initial)])
    wrappers[0]!.unmount(); await nextTick()
    const [nextFirst, nextSecond] = await mount()
    const fresh = Promise.all([nextFirst!(initial), nextSecond!(initial)])
    expect(fixture.api).toHaveBeenCalledTimes(2)
    pending[1]!.resolve(response('再入場'))
    expect((await fresh).every(result => result.items[0]!.title === '再入場')).toBe(true)
    pending[0]!.resolve(response('破棄済み'))
    expect((await old).every(result => result.status === 'rejected')).toBe(true)
  })
})
