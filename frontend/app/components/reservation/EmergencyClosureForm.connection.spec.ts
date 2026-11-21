import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import EmergencyClosureForm from './EmergencyClosureForm.vue'
import EmergencyClosureHistory from './emergency-closure/EmergencyClosureHistory.vue'

interface ClientConfig {
  onConnect: () => void
  onStompError: (frame: { headers: Record<string, string> }) => void
  onWebSocketClose: () => void
}

const h = vi.hoisted(() => ({
  snapshots: vi.fn(),
  clients: [] as Array<{
    config: ClientConfig
    active: boolean
    connected: boolean
    activate: ReturnType<typeof vi.fn>
    deactivate: ReturnType<typeof vi.fn>
    unsubscribe: ReturnType<typeof vi.fn>
  }>,
}))

// 実Form・実History・実connection-state本体を結線する。HTTP/STOMPとrole/auth/notificationの
// DI fixtureを固定した表示状態の検証であり、実認可試験ではない（後段の実機で別途確認する）。
vi.mock('@stomp/stompjs', () => ({
  Client: class {
    active = false
    connected = false
    unsubscribe = vi.fn()
    subscribe = vi.fn(() => ({ unsubscribe: this.unsubscribe }))
    activate = vi.fn(() => { this.active = true })
    deactivate = vi.fn(async () => { this.active = false; this.connected = false })
    constructor(public config: ClientConfig) { h.clients.push(this) }
  },
}))
mockNuxtImport('useEmergencyClosureApi', () => () => ({
  listClosures: async () => ({ data: [{ id: 10, teamId: 1, sentCount: 1 }] }),
  getConfirmations: h.snapshots,
}))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: true, loadPermissions: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() }))
mockNuxtImport('useAuthStore', () => () => ({ accessToken: null, isAuthenticated: false, loadFromStorage: vi.fn() }))

beforeEach(() => {
  h.clients.length = 0
  h.snapshots.mockReset().mockResolvedValue({ data: [{ userId: 1, confirmed: false }] })
})
afterEach(() => { vi.restoreAllMocks() })

async function openPanel() {
  const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
  await flushPromises()
  await wrapper.get('[data-testid="closure-card-toggle"]').trigger('click')
  await flushPromises()
  const client = h.clients[0]!
  client.connected = true
  client.config.onConnect()
  await flushPromises()
  const history = wrapper.findComponent(EmergencyClosureHistory)
  expect(history.props('connectionState')).toBe('LIVE')
  return { wrapper, client, history }
}

async function networkEvent(type: 'offline' | 'online') {
  window.dispatchEvent(new Event(type))
  await flushPromises()
}

describe('CMP-261115-0945 確認状況のオンライン復帰', () => {
  it('AC1: 既存socketを維持した人工offline→onlineで再展開せずLIVEに戻る', async () => {
    const { wrapper, client, history } = await openPanel()
    await networkEvent('offline')
    expect(history.props('connectionState')).toBe('OFFLINE')
    expect(client.connected).toBe(true)
    await networkEvent('online')
    expect(h.snapshots).toHaveBeenCalledTimes(2)
    expect(history.props('connectionState')).toBe('LIVE')
    expect(h.clients).toHaveLength(1)
    expect(client.deactivate).not.toHaveBeenCalled()
    wrapper.unmount()
  }, 60000)

  it('AC2: snapshot失敗はLIVEにせず、再同期成功で回復する', async () => {
    const { wrapper, history } = await openPanel()
    h.snapshots.mockRejectedValueOnce(new Error('offline'))
    await networkEvent('online')
    expect(history.props('connectionState')).toBe('OFFLINE')
    await networkEvent('online')
    expect(history.props('connectionState')).toBe('LIVE')
    wrapper.unmount()
  }, 60000)

  it('AC1/5: socket切断後は再接続中、再接続でLIVEへ戻る', async () => {
    const { wrapper, client, history } = await openPanel()
    client.connected = false
    client.config.onWebSocketClose()
    await networkEvent('offline')
    await networkEvent('online')
    expect(history.props('connectionState')).toBe('RECONNECTING')
    client.connected = true
    client.config.onConnect()
    await flushPromises()
    expect(history.props('connectionState')).toBe('LIVE')
    wrapper.unmount()
  }, 60000)

  it.each(['FORBIDDEN', 'UNAUTHENTICATED'])('AC3: %sはoffline/onlineで解除しない', async (code) => {
    const { wrapper, client, history } = await openPanel()
    client.config.onStompError({ headers: { 'x-error-code': code } })
    await flushPromises()
    const state = code === 'FORBIDDEN' ? 'DENIED' : 'UNAUTHENTICATED'
    expect(history.props('connectionState')).toBe(state)
    const activations = client.activate.mock.calls.length
    await networkEvent('offline')
    await networkEvent('online')
    expect(history.props('connectionState')).toBe(state)
    expect(client.activate).toHaveBeenCalledTimes(activations)
    wrapper.unmount()
  }, 60000)

  it('AC4: stop後のsnapshot応答で再活性化せず、online/offline handlerを解除する', async () => {
    const add = vi.spyOn(window, 'addEventListener')
    const remove = vi.spyOn(window, 'removeEventListener')
    const { wrapper, client } = await openPanel()
    let resolve!: (value: { data: [] }) => void
    h.snapshots.mockReturnValueOnce(new Promise<{ data: [] }>(done => { resolve = done }))
    window.dispatchEvent(new Event('online'))
    const activations = client.activate.mock.calls.length
    wrapper.unmount()
    resolve({ data: [] })
    await flushPromises()
    expect(client.activate).toHaveBeenCalledTimes(activations)
    expect(client.unsubscribe).toHaveBeenCalledTimes(1)
    for (const type of ['online', 'offline']) {
      const handler = add.mock.calls.find(([name]) => name === type)?.[1]
      expect(handler).toBeDefined()
      expect(remove).toHaveBeenCalledWith(type, handler)
    }
    const calls = h.snapshots.mock.calls.length
    await networkEvent('online')
    await networkEvent('offline')
    expect(h.snapshots).toHaveBeenCalledTimes(calls)
  }, 60000)
})
