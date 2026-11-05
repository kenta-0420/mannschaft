import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * CMP-261104-0337 共有 STOMP 接続（chat＋ユーザー宛通知 / corkboard）の認証・拒否の扱い。
 *
 * chat（useChatWebSocket）:
 *   WSCHAT-001: トークンが無いときは Authorization を送らない。あれば Bearer
 *   WSCHAT-002: 許可 → チャンネルと通知（/user/queue/notifications）を receipt 付きで購読
 *   WSCHAT-003: あるチャンネルが FORBIDDEN で拒否されても、再接続後に通知購読は張り直され、
 *               拒否されたチャンネル（メッセージ・イベント）だけが外れる。deactivate しない
 *   WSCHAT-004: UNAUTHENTICATED → 更新 1 回 → 新トークンで再接続し全購読を張り直す
 *   WSCHAT-005: UNAUTHENTICATED で更新失敗 → ログアウト・wsUnauthenticated・deactivate
 *   WSCHAT-006: 未知の ERROR・通信断は再接続を続け、全購読を張り直す
 *   WSCHAT-007: 再接続後もサブスクライブし直した通知が受信できる（store 側へ流れる）
 *
 * corkboard（useCorkboardEventListener）:
 *   WSCORK-001: トークンが無いときは Authorization を送らない
 *   WSCORK-002: FORBIDDEN → DENIED・deactivate（再接続しない）
 *   WSCORK-003: UNAUTHENTICATED → 更新 1 回 → 再接続でボードを張り直す
 *   WSCORK-004: UNAUTHENTICATED で更新失敗 → ログアウト・UNAUTHENTICATED・deactivate
 *   WSCORK-005: 未知の ERROR は console.error で報告し、再接続でボードを張り直す
 */

interface ConfigLike {
  connectHeaders?: Record<string, string>
  beforeConnect?: (client: unknown) => void | Promise<void>
  onConnect?: (frame: unknown) => void
  onStompError?: (frame: { headers: Record<string, string>; body: string }) => void
  onWebSocketClose?: (evt: unknown) => void
}

type FrameCallback = (frame: { body: string }) => void

const h = vi.hoisted(() => {
  const instances: Array<{
    config: ConfigLike
    connected: boolean
    active: boolean
    connectHeaders: Record<string, string>
    subscribe: ReturnType<typeof vi.fn>
    deactivate: ReturnType<typeof vi.fn>
    activate: ReturnType<typeof vi.fn>
  }> = []
  const authStore = {
    accessToken: null as string | null,
    isAuthenticated: true,
    user: { id: 7 } as { id: number } | null,
  }
  return { instances, authStore, refresh: vi.fn(), logout: vi.fn() }
})

vi.mock('@stomp/stompjs', () => {
  class MockClient {
    config: ConfigLike
    connected = false
    active = false
    connectHeaders: Record<string, string>
    reconnectDelay = 0
    subscribe = vi.fn((_d: string, _cb: FrameCallback, _headers?: Record<string, string>) => ({
      id: 's',
      unsubscribe: vi.fn(),
    }))
    deactivate = vi.fn(async () => {
      this.active = false
      this.connected = false
    })
    activate = vi.fn(() => {
      this.active = true
    })
    publish = vi.fn()

    constructor(config: ConfigLike) {
      this.config = config
      this.connectHeaders = config.connectHeaders ?? {}
      h.instances.push(this)
    }
  }
  return { Client: MockClient }
})

vi.mock('~/composables/useApi', async (importOriginal) => {
  const original = await importOriginal<typeof import('~/composables/useApi')>()
  return {
    ...original,
    useApi: () => vi.fn(),
    performTokenRefresh: (...args: unknown[]) => h.refresh(...args),
    handleAuthFailureLogout: (...args: unknown[]) => h.logout(...args),
  }
})

mockNuxtImport('useAuthStore', () => () => h.authStore)

function lastClient() {
  const c = h.instances[h.instances.length - 1]
  if (!c) throw new Error('STOMP client が生成されていない')
  return c
}

async function simulateConnect(): Promise<boolean> {
  const c = lastClient()
  await c.config.beforeConnect?.(c)
  if (!c.active) return false
  c.connected = true
  c.config.onConnect?.({})
  return true
}

function simulateError(headers: Record<string, string>): void {
  const c = lastClient()
  c.config.onStompError?.({ headers, body: '' })
  c.connected = false
  c.config.onWebSocketClose?.({})
}

/** 直近の接続以降に購読された宛先と receipt。 */
function subscribedSince(index: number): Array<{ destination: string; receipt?: string; callback: FrameCallback }> {
  return lastClient().subscribe.mock.calls.slice(index).map((call) => {
    const [destination, callback, headers] = call as [string, FrameCallback, Record<string, string> | undefined]
    return { destination, callback, receipt: headers?.receipt }
  })
}

async function flush(rounds = 10): Promise<void> {
  for (let i = 0; i < rounds; i++) await Promise.resolve()
}

async function freshChat() {
  vi.resetModules()
  const mod = await import('~/composables/chat/useChatWebSocket')
  return mod.useChatWebSocket()
}

async function freshCorkboard() {
  vi.resetModules()
  const mod = await import('~/composables/useCorkboardEventListener')
  return mod.useCorkboardEventListener
}

beforeEach(() => {
  h.instances.length = 0
  h.authStore.accessToken = null
  h.authStore.isAuthenticated = true
  h.authStore.user = { id: 7 }
  h.refresh.mockReset()
  h.logout.mockReset().mockResolvedValue(undefined)
})

describe('chat 共有接続 — 認証・拒否', () => {
  it('WSCHAT-001: トークンが無いときは Authorization を送らず、あれば Bearer を送る', async () => {
    const ws = await freshChat()
    ws.subscribeChannel(5)
    expect(lastClient().connectHeaders).toEqual({})
    h.authStore.accessToken = 'tok-1'
    await simulateConnect()
    expect(lastClient().connectHeaders).toEqual({ Authorization: 'Bearer tok-1' })
  })

  it('WSCHAT-002: 許可されるとチャンネルと通知を receipt 付きで購読する', async () => {
    const ws = await freshChat()
    ws.subscribeChannel(5)
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    const subs = subscribedSince(0)
    expect(subs.map(s => s.destination).sort()).toEqual(['/topic/channels/5', '/user/queue/notifications'])
    expect(subs.every(s => typeof s.receipt === 'string' && s.receipt.length > 0)).toBe(true)
  })

  it('WSCHAT-003: チャンネルが FORBIDDEN でも通知購読は止まらず、拒否されたチャンネルだけ外れる', async () => {
    const ws = await freshChat()
    const onNotification = vi.fn()
    ws.subscribeRaw('/user/queue/notifications', onNotification)
    ws.subscribeChannel(5)
    ws.subscribeChannelEvents(5)
    ws.subscribeChannel(6)
    await simulateConnect()
    const denied = subscribedSince(0).find(s => s.destination === '/topic/channels/5')
    expect(denied?.receipt).toBeDefined()

    // サーバは拒否した SUBSCRIBE の receipt を ERROR の receipt-id に載せて接続を閉じる。
    simulateError({ 'x-error-code': 'FORBIDDEN', 'receipt-id': denied?.receipt ?? '' })
    await flush()
    expect(lastClient().deactivate).not.toHaveBeenCalled()
    expect(ws.isChannelDenied(5)).toBe(true)
    expect(ws.isChannelDenied(6)).toBe(false)
    expect(ws.wsUnauthenticated.value).toBe(false)

    // stompjs の自動再接続 → 拒否された宛先以外を張り直す。
    const before = lastClient().subscribe.mock.calls.length
    expect(await simulateConnect()).toBe(true)
    const resubscribed = subscribedSince(before).map(s => s.destination).sort()
    expect(resubscribed).toEqual(['/topic/channels/6', '/user/queue/notifications'])
    expect(h.refresh).not.toHaveBeenCalled()
  })

  it('WSCHAT-004: UNAUTHENTICATED → 更新 1 回 → 新トークンで再接続し全購読を張り直す', async () => {
    h.refresh.mockImplementation(async () => {
      h.authStore.accessToken = 'fresh-token'
      return 'refreshed'
    })
    const ws = await freshChat()
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    ws.subscribeChannel(5)
    await simulateConnect()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    const before = lastClient().subscribe.mock.calls.length
    expect(await simulateConnect()).toBe(true)
    expect(h.refresh).toHaveBeenCalledTimes(1)
    expect(h.logout).not.toHaveBeenCalled()
    expect(lastClient().connectHeaders).toEqual({ Authorization: 'Bearer fresh-token' })
    expect(subscribedSince(before).map(s => s.destination).sort()).toEqual([
      '/topic/channels/5',
      '/user/queue/notifications',
    ])
    expect(ws.wsUnauthenticated.value).toBe(false)
  })

  it('WSCHAT-005: UNAUTHENTICATED で更新失敗 → ログアウトし再接続を止める', async () => {
    h.refresh.mockResolvedValue('auth_failed')
    const ws = await freshChat()
    ws.subscribeChannel(5)
    await simulateConnect()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    expect(await simulateConnect()).toBe(false)
    expect(h.refresh).toHaveBeenCalledTimes(1)
    expect(h.logout).toHaveBeenCalledTimes(1)
    expect(ws.wsUnauthenticated.value).toBe(true)
    expect(lastClient().deactivate).toHaveBeenCalledTimes(1)
  })

  it('WSCHAT-006: 未知の ERROR・通信断は再接続を続けて全購読を張り直す', async () => {
    const ws = await freshChat()
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    ws.subscribeChannel(5)
    await simulateConnect()
    simulateError({ message: 'unexpected' })
    await flush()
    expect(lastClient().deactivate).not.toHaveBeenCalled()
    let before = lastClient().subscribe.mock.calls.length
    expect(await simulateConnect()).toBe(true)
    expect(subscribedSince(before)).toHaveLength(2)

    // 通信断（ERROR なし）
    lastClient().connected = false
    lastClient().config.onWebSocketClose?.({})
    before = lastClient().subscribe.mock.calls.length
    expect(await simulateConnect()).toBe(true)
    expect(subscribedSince(before)).toHaveLength(2)
    expect(h.refresh).not.toHaveBeenCalled()
  })

  it('WSCHAT-007: 他チャンネルの拒否後も、張り直した通知購読で受信できる', async () => {
    const ws = await freshChat()
    const onNotification = vi.fn()
    ws.subscribeRaw('/user/queue/notifications', onNotification)
    ws.subscribeChannel(9)
    await simulateConnect()
    const denied = subscribedSince(0).find(s => s.destination === '/topic/channels/9')
    simulateError({ 'x-error-code': 'FORBIDDEN', 'receipt-id': denied?.receipt ?? '' })
    const before = lastClient().subscribe.mock.calls.length
    await simulateConnect()
    const notification = subscribedSince(before).find(s => s.destination === '/user/queue/notifications')
    notification?.callback({ body: '{"id":1}' })
    expect(onNotification).toHaveBeenCalledWith('{"id":1}')
  })
})

describe('corkboard 共有接続 — 認証・拒否', () => {
  it('WSCORK-001: トークンが無いときは Authorization を送らない', async () => {
    const useListener = await freshCorkboard()
    useListener({ boardId: 1, onEvent: vi.fn() }).connect()
    expect(lastClient().connectHeaders).toEqual({})
  })

  it('WSCORK-002: FORBIDDEN は DENIED にして deactivate（再接続しない）', async () => {
    const useListener = await freshCorkboard()
    const listener = useListener({ boardId: 1, onEvent: vi.fn() })
    listener.connect()
    await simulateConnect()
    simulateError({ 'x-error-code': 'FORBIDDEN' })
    await flush()
    expect(listener.connectionState.value).toBe('DENIED')
    expect(lastClient().deactivate).toHaveBeenCalledTimes(1)
    expect(await simulateConnect()).toBe(false)
  })

  it('WSCORK-003: UNAUTHENTICATED → 更新 1 回 → 再接続でボードを張り直す', async () => {
    h.refresh.mockImplementation(async () => {
      h.authStore.accessToken = 'fresh-token'
      return 'refreshed'
    })
    const useListener = await freshCorkboard()
    const listener = useListener({ boardId: 3, onEvent: vi.fn() })
    listener.connect()
    await simulateConnect()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    const before = lastClient().subscribe.mock.calls.length
    expect(await simulateConnect()).toBe(true)
    expect(h.refresh).toHaveBeenCalledTimes(1)
    expect(lastClient().connectHeaders).toEqual({ Authorization: 'Bearer fresh-token' })
    expect(subscribedSince(before).map(s => s.destination)).toEqual(['/topic/corkboard/3'])
    expect(listener.connectionState.value).toBe('OK')
  })

  it('WSCORK-004: UNAUTHENTICATED で更新失敗 → ログアウトし UNAUTHENTICATED で止める', async () => {
    h.refresh.mockResolvedValue('auth_failed')
    const useListener = await freshCorkboard()
    const listener = useListener({ boardId: 3, onEvent: vi.fn() })
    listener.connect()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    expect(await simulateConnect()).toBe(false)
    expect(h.logout).toHaveBeenCalledTimes(1)
    expect(listener.connectionState.value).toBe('UNAUTHENTICATED')
    expect(lastClient().deactivate).toHaveBeenCalledTimes(1)
  })

  it('WSCORK-005: 未知の ERROR は console.error で報告し、再接続でボードを張り直す', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const useListener = await freshCorkboard()
    const listener = useListener({ boardId: 4, onEvent: vi.fn() })
    listener.connect()
    await simulateConnect()
    simulateError({ message: 'unexpected' })
    expect(errorSpy).toHaveBeenCalled()
    expect(lastClient().deactivate).not.toHaveBeenCalled()
    const before = lastClient().subscribe.mock.calls.length
    expect(await simulateConnect()).toBe(true)
    expect(subscribedSince(before).map(s => s.destination)).toEqual(['/topic/corkboard/4'])
    errorSpy.mockRestore()
  })
})
