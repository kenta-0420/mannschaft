import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * CMP-261104-0337 専用 STOMP 接続（臨時休業 確認状況 / match ライブ観戦）の認証・拒否の扱い。
 *
 * 観点（emergency・match 共通。it.each で両 composable に同じ観点を当てる）:
 *   WSDED-001: トークンが無いときは Authorization を送らない（Cookie 認証）。あれば Bearer
 *   WSDED-002: 許可 → LIVE・宛先を購読
 *   WSDED-003: FORBIDDEN → DENIED・deactivate（再接続しない）。オンライン復帰でも再接続しない
 *   WSDED-004: UNAUTHENTICATED → 更新 1 回 → beforeConnect で新トークンに差し替えて再接続 → LIVE
 *   WSDED-005: UNAUTHENTICATED で更新失敗 → ログアウト・UNAUTHENTICATED・deactivate
 *   WSDED-006: 未知の ERROR は DENIED にせず再接続を続ける（RECONNECTING）
 *   WSDED-007: 通信断（ERROR なし切断）は従来どおり RECONNECTING
 *   WSDED-008（match のみ）: 匿名の閲覧者は Authorization なしで接続し、UNAUTHENTICATED でも
 *              更新・ログアウトをしない（状態は UNAUTHENTICATED で止まる）
 */

interface ConfigLike {
  connectHeaders?: Record<string, string>
  beforeConnect?: (client: unknown) => void | Promise<void>
  onConnect?: (frame: unknown) => void
  onStompError?: (frame: { headers: Record<string, string>; body: string }) => void
  onWebSocketClose?: (evt: unknown) => void
}

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
  return {
    instances,
    authStore,
    refresh: vi.fn(),
    logout: vi.fn(),
  }
})

vi.mock('@stomp/stompjs', () => {
  class MockClient {
    config: ConfigLike
    connected = false
    active = false
    connectHeaders: Record<string, string>
    subscribe = vi.fn((_d: string, _cb: unknown, _headers?: Record<string, string>) => ({ id: 's', unsubscribe: vi.fn() }))
    deactivate = vi.fn(async () => {
      this.active = false
      this.connected = false
    })
    activate = vi.fn(() => {
      this.active = true
    })

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
    performTokenRefresh: (...args: unknown[]) => h.refresh(...args),
    handleAuthFailureLogout: (...args: unknown[]) => h.logout(...args),
  }
})

mockNuxtImport('useAuthStore', () => () => h.authStore)
mockNuxtImport('useEmergencyClosureApi', () => () => ({
  getConfirmations: vi.fn().mockResolvedValue({ data: [] }),
}))
mockNuxtImport('useMatchApi', () => () => ({
  getMatch: vi.fn().mockResolvedValue({ status: 'IN_PROGRESS', homeScore: 0, awayScore: 0 }),
}))
mockNuxtImport('useMatchEventApi', () => () => ({
  listEvents: vi.fn().mockResolvedValue({ events: [] }),
}))

const { useEmergencyClosureLive } = await import('~/composables/useEmergencyClosureLive')
const { useMatchLiveSpectator } = await import('~/composables/match/useMatchLiveSpectator')

type Live = {
  connectionState: { value: string }
  start: () => Promise<void>
  resync: () => Promise<void>
}

const targets: Array<{ name: string; destination: string; create: () => Live }> = [
  {
    name: 'emergency',
    destination: '/topic/teams/42/emergency-closures/100/confirmations',
    create: () => useEmergencyClosureLive({ teamId: ref(42), apiTeamRef: ref('42'), closureId: 100 }),
  },
  {
    name: 'match',
    destination: '/topic/matches/m-1/live',
    create: () => useMatchLiveSpectator({ orgId: ref(1), teamId: ref(2), matchId: 'm-1' }),
  },
]

function lastClient() {
  const c = h.instances[h.instances.length - 1]
  if (!c) throw new Error('STOMP client が生成されていない')
  return c
}

/** stompjs の接続手順（beforeConnect → active なら CONNECTED → onConnect）を模す。 */
async function simulateConnect(): Promise<boolean> {
  const c = lastClient()
  await c.config.beforeConnect?.(c)
  if (!c.active) return false
  c.connected = true
  c.config.onConnect?.({})
  return true
}

/** ERROR フレーム受信 → サーバが接続を閉じる。 */
function simulateError(headers: Record<string, string>): void {
  const c = lastClient()
  c.config.onStompError?.({ headers, body: '' })
  c.connected = false
  c.config.onWebSocketClose?.({})
}

async function flush(rounds = 10): Promise<void> {
  for (let i = 0; i < rounds; i++) await Promise.resolve()
}

beforeEach(() => {
  h.instances.length = 0
  h.authStore.accessToken = null
  h.authStore.isAuthenticated = true
  h.authStore.user = { id: 7 }
  h.refresh.mockReset()
  h.logout.mockReset().mockResolvedValue(undefined)
})

describe.each(targets)('$name — 専用 STOMP 接続の認証・拒否', ({ destination, create }) => {
  it('WSDED-001: トークンが無いときは Authorization を送らず、あれば Bearer を送る', async () => {
    const live = create()
    await live.start()
    expect(lastClient().connectHeaders).toEqual({})
    h.authStore.accessToken = 'tok-1'
    await simulateConnect()
    expect(lastClient().connectHeaders).toEqual({ Authorization: 'Bearer tok-1' })
  })

  it('WSDED-002: 許可されると LIVE になり宛先を購読する', async () => {
    const live = create()
    await live.start()
    await simulateConnect()
    expect(live.connectionState.value).toBe('LIVE')
    expect(lastClient().subscribe).toHaveBeenCalledWith(destination, expect.any(Function))
  })

  it('WSDED-003: FORBIDDEN は DENIED にして deactivate し、オンライン復帰でも再接続しない', async () => {
    const live = create()
    await live.start()
    await simulateConnect()
    simulateError({ 'x-error-code': 'FORBIDDEN', message: 'denied' })
    await flush()
    const c = lastClient()
    expect(live.connectionState.value).toBe('DENIED')
    expect(c.deactivate).toHaveBeenCalledTimes(1)
    expect(h.refresh).not.toHaveBeenCalled()
    const activations = c.activate.mock.calls.length
    await live.resync()
    expect(c.activate.mock.calls.length).toBe(activations)
    expect(await simulateConnect()).toBe(false)
  })

  it('WSDED-004: UNAUTHENTICATED → 更新 1 回 → 新トークンで再接続して LIVE', async () => {
    h.refresh.mockImplementation(async () => {
      h.authStore.accessToken = 'fresh-token'
      return 'refreshed'
    })
    const live = create()
    await live.start()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    expect(live.connectionState.value).toBe('RECONNECTING')
    expect(await simulateConnect()).toBe(true)
    expect(h.refresh).toHaveBeenCalledTimes(1)
    expect(h.logout).not.toHaveBeenCalled()
    expect(lastClient().connectHeaders).toEqual({ Authorization: 'Bearer fresh-token' })
    expect(live.connectionState.value).toBe('LIVE')
    expect(lastClient().deactivate).not.toHaveBeenCalled()
  })

  it('WSDED-005: UNAUTHENTICATED で更新失敗 → ログアウトし UNAUTHENTICATED で再接続を止める', async () => {
    h.refresh.mockResolvedValue('auth_failed')
    const live = create()
    await live.start()
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    expect(await simulateConnect()).toBe(false)
    expect(h.refresh).toHaveBeenCalledTimes(1)
    expect(h.logout).toHaveBeenCalledTimes(1)
    expect(live.connectionState.value).toBe('UNAUTHENTICATED')
    expect(lastClient().deactivate).toHaveBeenCalledTimes(1)
  })

  it('WSDED-006: 未知の ERROR は DENIED にせず、再接続を続ける', async () => {
    const live = create()
    await live.start()
    await simulateConnect()
    simulateError({ message: 'unexpected' })
    await flush()
    expect(live.connectionState.value).toBe('RECONNECTING')
    expect(lastClient().deactivate).not.toHaveBeenCalled()
    expect(await simulateConnect()).toBe(true)
    expect(live.connectionState.value).toBe('LIVE')
  })

  it('WSDED-007: 通信断（ERROR なしの切断）は RECONNECTING → 再接続で LIVE', async () => {
    const live = create()
    await live.start()
    await simulateConnect()
    const c = lastClient()
    c.connected = false
    c.config.onWebSocketClose?.({})
    expect(live.connectionState.value).toBe('RECONNECTING')
    expect(await simulateConnect()).toBe(true)
    expect(live.connectionState.value).toBe('LIVE')
    expect(h.refresh).not.toHaveBeenCalled()
  })
})

describe('match — 匿名の閲覧者', () => {
  it('WSDED-008: 匿名は Authorization なしで接続し、UNAUTHENTICATED でも更新・ログアウトしない', async () => {
    h.authStore.isAuthenticated = false
    h.authStore.user = null
    const live = useMatchLiveSpectator({ orgId: ref(1), teamId: ref(2), matchId: 'm-1' })
    await live.start()
    expect(lastClient().connectHeaders).toEqual({})
    // 匿名 CONNECT は成立する（公開試合の匿名購読は従来どおり試みる）。
    await simulateConnect()
    expect(lastClient().subscribe).toHaveBeenCalledWith('/topic/matches/m-1/live', expect.any(Function))
    simulateError({ 'x-error-code': 'UNAUTHENTICATED' })
    await flush()
    expect(h.refresh).not.toHaveBeenCalled()
    expect(h.logout).not.toHaveBeenCalled()
    expect(live.connectionState.value).toBe('UNAUTHENTICATED')
    expect(lastClient().deactivate).toHaveBeenCalledTimes(1)
  })
})
