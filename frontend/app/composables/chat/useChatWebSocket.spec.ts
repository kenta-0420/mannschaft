import { reactive, effectScope } from 'vue'
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
    publish: ReturnType<typeof vi.fn>
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
    useStompAuthRecovery: () =>
      original.createStompAuthRecovery({
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
function subscribedSince(
  index: number,
): Array<{ destination: string; receipt?: string; callback: FrameCallback }> {
  return lastClient()
    .subscribe.mock.calls.slice(index)
    .map((call) => {
      const [destination, callback, headers] = call as [
        string,
        FrameCallback,
        Record<string, string> | undefined,
      ]
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

beforeEach(() => {
  h.instances.length = 0
  h.authStore = reactive({
    accessToken: 'token-A',
    isAuthenticated: true,
    user: { id: 7 },
    loadFromStorage: () => {},
  })
  h.refresh.mockReset().mockResolvedValue('refreshed')
  h.logout.mockReset().mockResolvedValue(undefined)
})

describe('N2: 共有STOMPのユーザー隔離', () => {
  it('N2追加: 匿名sendは接続0、認証後の最初の正当sendは同handleで送れる', async () => {
    h.authStore.user = null
    h.authStore.isAuthenticated = false
    const ws = await freshChat()
    ws.publishRaw('/app/villages/fixture/lobby/presence/join')
    await flush()
    expect(h.instances).toHaveLength(0)
    h.authStore.user = { id: 7 }
    h.authStore.isAuthenticated = true
    ws.publishRaw('/app/villages/fixture/lobby/presence/join')
    await simulateConnect()
    await flush()
    expect(lastClient().publish).toHaveBeenCalledTimes(1)
  })

  it('N2追加: 同id再login後も旧publisher handleを新世代へ流用しない', async () => {
    const old = await freshChat()
    old.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    h.authStore.user = null
    h.authStore.user = { id: 7 }
    h.authStore.accessToken = 'token-A-new-session'
    const mod = await import('~/composables/chat/useChatWebSocket')
    const next = mod.useChatWebSocket()
    next.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    old.publishRaw('/app/villages/fixture/lobby/presence/leave')
    await flush()
    expect(lastClient().publish).not.toHaveBeenCalled()
    next.publishRaw('/app/villages/fixture/lobby/presence/join')
    await flush()
    expect(lastClient().publish).toHaveBeenCalledTimes(1)
  })

  it('N2追加: Aの旧publishRaw handleをB切替後にinvokeしてもBへ送らない', async () => {
    const old = await freshChat()
    old.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    h.authStore.user = { id: 8 }
    const mod = await import('~/composables/chat/useChatWebSocket')
    const next = mod.useChatWebSocket()
    next.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    const client = lastClient()
    old.publishRaw('/app/villages/fixture/lobby/presence/leave', '{}')
    await flush()
    expect(client.publish).not.toHaveBeenCalled()
    next.publishRaw('/app/villages/fixture/lobby/presence/join', '{}')
    await flush()
    expect(client.publish).toHaveBeenCalledTimes(1)
  })

  it('N2追加: Aの旧sendTyping handleをB切替後にinvokeしてもBへ送らない', async () => {
    const old = await freshChat()
    old.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    h.authStore.user = { id: 8 }
    const mod = await import('~/composables/chat/useChatWebSocket')
    const subscriber = mod.useChatWebSocket()
    subscriber.subscribeChannel(5)
    await simulateConnect()
    const publisher = mod.useChatWebSocket()
    old.sendTyping(5)
    await flush()
    expect(lastClient().publish).not.toHaveBeenCalled()
    publisher.sendTyping(5)
    await flush()
    expect(lastClient().publish).toHaveBeenCalledTimes(1)
  })

  it('匿名setupでは接続せず、認証復元後の取得で初めて接続する', async () => {
    h.authStore.user = null
    h.authStore.isAuthenticated = false
    const ws = await freshChat()
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    expect(h.instances).toHaveLength(0)
    h.authStore.user = { id: 7 }
    h.authStore.isAuthenticated = true
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    expect(h.instances).toHaveLength(1)
  })

  it('Aの遅着CONNECT・close・error・MESSAGEはBに作用せず、Bが受信できる', async () => {
    const { ws, diagnostics } = await freshChatModule()
    const oldReceive = vi.fn()
    ws.subscribeRaw('/user/queue/notifications', oldReceive)
    await simulateConnect()
    const oldClient = lastClient()
    const oldCallback = subscribedSince(0)[0]!.callback
    h.authStore.user = null
    h.authStore.user = { id: 8 }
    h.authStore.accessToken = 'token-B'
    const received = vi.fn()
    ws.subscribeRaw('/user/queue/notifications', received)
    const next = lastClient()
    expect(next).not.toBe(oldClient)
    await simulateConnect()
    const nextCallback = subscribedSince(0)[0]!.callback
    oldClient.config.onConnect?.({})
    oldClient.config.onWebSocketClose?.({})
    oldClient.config.onStompError?.({ headers: { 'x-error-code': 'FORBIDDEN' }, body: '' })
    oldCallback({ body: 'old-user' })
    expect(oldReceive).not.toHaveBeenCalled()
    expect(next.deactivate).not.toHaveBeenCalled()
    expect(diagnostics().activeSubscriptions).toBe(1)
    nextCallback({ body: 'new-user' })
    expect(received).toHaveBeenCalledWith('new-user')
    expect(next.connectHeaders).toEqual({ Authorization: 'Bearer token-B' })
  })

  it('同userの停止済みrouteのqueued MESSAGEを再取得先へ流さない', async () => {
    const ws = await freshChat()
    const receive = vi.fn()
    const release = ws.subscribeRaw('/user/queue/notifications', receive)
    await simulateConnect()
    const oldCallback = subscribedSince(0)[0]!.callback
    release()
    ws.subscribeRaw('/user/queue/notifications', receive)
    oldCallback({ body: 'obsolete-route' })
    expect(receive).not.toHaveBeenCalled()
  })

  it('旧channel所有者の解除は新userの同channel参照数を減らさない', async () => {
    vi.resetModules()
    const mod = await import('~/composables/chat/useChatWebSocket')
    const old = mod.useChatWebSocket()
    old.subscribeChannel(5)
    await simulateConnect()
    h.authStore.user = { id: 8 }
    const next = mod.useChatWebSocket()
    next.subscribeChannel(5)
    expect(h.instances).toHaveLength(2)
    await simulateConnect()
    old.unsubscribeChannel(5)
    expect(mod.getChatWsDiagnostics().routes).toBe(1)
    next.unsubscribeChannel(5)
    expect(mod.getChatWsDiagnostics().routes).toBe(0)
  })

  it('component scope停止後もidentity監視を維持し、同id再loginで旧接続を捨てる', async () => {
    vi.resetModules()
    const mod = await import('~/composables/chat/useChatWebSocket')
    const scope = effectScope()
    const ws = scope.run(() => mod.useChatWebSocket())!
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    const old = lastClient()
    scope.stop()
    h.authStore.user = null
    h.authStore.user = { id: 7 }
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    expect(lastClient()).not.toBe(old)
    expect(old.deactivate).toHaveBeenCalled()
  })

  it('Aのpending recovery/beforeConnectの遅着でBを復活・停止させない', async () => {
    const ws = await freshChat()
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    const old = lastClient()
    let complete!: (value: string) => void
    h.refresh.mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          complete = resolve
        }),
    )
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    const pendingConnect = old.config.beforeConnect?.(old)
    h.authStore.user = { id: 8 }
    h.authStore.accessToken = 'token-B'
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    const next = lastClient()
    expect(next).not.toBe(old)
    await simulateConnect()
    complete('auth_failed')
    await pendingConnect
    await flush()
    expect(old.active).toBe(false)
    expect(next.active).toBe(true)
    expect(next.connectHeaders).toEqual({ Authorization: 'Bearer token-B' })
    expect(h.logout).not.toHaveBeenCalled()
  })

  it('同user token更新では接続を作り直さず、次CONNECTで最新tokenを使う', async () => {
    const ws = await freshChat()
    ws.subscribeChannel(5)
    await simulateConnect()
    const client = lastClient()
    h.authStore.accessToken = 'fresh-token'
    await simulateConnect()
    expect(h.instances).toHaveLength(1)
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer fresh-token' })
  })

  it('A直後のBの正当な回復へAの30秒窓を持ち越さない', async () => {
    const ws = await freshChat()
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    await flush()
    h.authStore.user = { id: 8 }
    ws.subscribeRaw('/user/queue/notifications', vi.fn())
    await simulateConnect()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    await flush()
    expect(h.refresh).toHaveBeenCalledTimes(2)
    expect(ws.wsUnauthenticated.value).toBe(false)
  })
})
