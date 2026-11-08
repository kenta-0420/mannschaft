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
  // auth.client プラグインがアプリ初期化時に呼ぶ loadFromStorage も持たせる。初期値は未ログイン
  // （プラグインに先回り更新を武装させない）。各テストの beforeEach でログイン状態にする。
  const authStore = {
    accessToken: null as string | null,
    isAuthenticated: false,
    user: null as { id: number } | null,
    loadFromStorage: () => {},
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

// 回復手順が使う authStore をテストの偽ストアに固定する（回復ロジック自体は本物の createStompAuthRecovery）。
vi.mock('~/composables/stomp/stompAuth', async (importOriginal) => {
  const original = await importOriginal<typeof import('~/composables/stomp/stompAuth')>()
  type Deps = Parameters<typeof original.createStompAuthRecovery>[0]
  return {
    ...original,
    useStompAuthRecovery: () => original.createStompAuthRecovery({
      config: { public: { apiBase: '' } } as unknown as Deps['config'],
      authStore: h.authStore as unknown as Deps['authStore'],
    }),
  }
})

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

async function freshChatModule() {
  vi.resetModules()
  const mod = await import('~/composables/chat/useChatWebSocket')
  return { ws: mod.useChatWebSocket(), diagnostics: mod.getChatWsDiagnostics }
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

describe('chat 共有接続 — receipt の寿命と二重購読', () => {
  it('WSCHAT-008: 再接続・購読解除・拒否を繰り返しても receipt は現在の購読数を超えて増えない', async () => {
    const { ws, diagnostics } = await freshChatModule()
    const unsubscribeNotification = ws.subscribeRaw('/user/queue/notifications', vi.fn())
    ws.subscribeChannel(1)
    ws.subscribeChannel(2)
    await simulateConnect()
    expect(diagnostics()).toEqual({ routes: 3, activeSubscriptions: 3, receipts: 3 })

    // 通信断 → 再接続を 5 回繰り返しても、receipt は現在の購読数のまま。
    for (let i = 0; i < 5; i++) {
      lastClient().connected = false
      lastClient().config.onWebSocketClose?.({})
      expect(diagnostics().receipts).toBe(0)
      await simulateConnect()
      expect(diagnostics()).toEqual({ routes: 3, activeSubscriptions: 3, receipts: 3 })
    }

    // 拒否: 拒否された宛先の receipt は消え、再接続後は残りの購読分だけ。
    const denied = subscribedSince(0).reverse().find(s => s.destination === '/topic/channels/2')
    simulateError({ 'x-error-code': 'FORBIDDEN', 'receipt-id': denied?.receipt ?? '' })
    await simulateConnect()
    expect(diagnostics()).toEqual({ routes: 3, activeSubscriptions: 2, receipts: 2 })

    // 購読解除: その購読の receipt も消える。
    ws.unsubscribeChannel(1)
    unsubscribeNotification()
    expect(diagnostics()).toEqual({ routes: 1, activeSubscriptions: 0, receipts: 0 })
  })

  it('WSCHAT-009: 再接続後に同じ宛先を二重購読しない（各宛先 1 回だけ SUBSCRIBE）', async () => {
    const { ws, diagnostics } = await freshChatModule()
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    ws.subscribeChannel(5)
    ws.subscribeChannelEvents(5)
    await simulateConnect()
    for (let i = 0; i < 3; i++) {
      lastClient().connected = false
      lastClient().config.onWebSocketClose?.({})
      const before = lastClient().subscribe.mock.calls.length
      await simulateConnect()
      const destinations = subscribedSince(before).map(s => s.destination).sort()
      expect(destinations).toEqual([
        '/topic/channels/5',
        '/topic/channels/5/events',
        '/user/queue/notifications',
      ])
    }
    // 接続中に同じチャンネルを再度購読しても SUBSCRIBE は増えない（参照カウント）。
    const before = lastClient().subscribe.mock.calls.length
    ws.subscribeChannel(5)
    expect(lastClient().subscribe.mock.calls.length).toBe(before)
    expect(diagnostics().activeSubscriptions).toBe(3)
  })
})

describe('corkboard 共有接続 — 認証・拒否', () => {
  it('WSCORK-006: DENIED で止まった後も、別のボードの購読で接続を再開して同期できる', async () => {
    const useListener = await freshCorkboard()
    const first = useListener({ boardId: 1, onEvent: vi.fn() })
    first.connect()
    await simulateConnect()
    simulateError({ 'x-error-code': 'FORBIDDEN' })
    await flush()
    expect(first.connectionState.value).toBe('DENIED')
    first.disconnect()

    const second = useListener({ boardId: 2, onEvent: vi.fn() })
    second.connect()
    expect(second.connectionState.value).toBe('OK')
    const before = lastClient().subscribe.mock.calls.length
    expect(await simulateConnect()).toBe(true)
    expect(subscribedSince(before).map(s => s.destination)).toEqual(['/topic/corkboard/2'])
  })

  it('WSCORK-007: UNAUTHENTICATED で止まった後も、新しいセッションでの購読で再開できる', async () => {
    h.refresh.mockResolvedValue('auth_failed')
    const useListener = await freshCorkboard()
    const first = useListener({ boardId: 3, onEvent: vi.fn() })
    first.connect()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    expect(await simulateConnect()).toBe(false)
    expect(first.connectionState.value).toBe('UNAUTHENTICATED')
    first.disconnect()

    // 再ログイン後にボードを開き直す。
    h.authStore.accessToken = 'new-session-token'
    const second = useListener({ boardId: 3, onEvent: vi.fn() })
    second.connect()
    expect(second.connectionState.value).toBe('OK')
    const before = lastClient().subscribe.mock.calls.length
    expect(await simulateConnect()).toBe(true)
    expect(lastClient().connectHeaders).toEqual({ Authorization: 'Bearer new-session-token' })
    expect(subscribedSince(before).map(s => s.destination)).toEqual(['/topic/corkboard/3'])
  })

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
