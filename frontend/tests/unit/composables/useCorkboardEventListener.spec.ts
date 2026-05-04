import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F09.8 Phase F useCorkboardEventListener — STOMP 購読・受信ハンドラのユニットテスト。
 *
 * テストケース:
 *  1. CORK-WS-UNIT-001: connect() で `/topic/corkboard/{boardId}` に SUBSCRIBE される
 *  2. CORK-WS-UNIT-002: 同一 boardId への複数 connect() でも STOMP SUBSCRIBE は 1 回のみ（参照カウント）
 *  3. CORK-WS-UNIT-003: 受信メッセージが onEvent コールバックへ渡される
 *  4. CORK-WS-UNIT-004: disconnect() でカウント 0 になったら STOMP UNSUBSCRIBE が呼ばれる
 *  5. CORK-WS-UNIT-005: 異なる boardId にはそれぞれ別の SUBSCRIBE が行われる
 *
 * モック方針: `useChatWebSocket.spec.ts` のパターンを踏襲。
 *  - `@stomp/stompjs` の Client を MockClient に差し替え
 *  - `useAuthStore` を最小モック（accessToken のみ）
 *  - `vi.resetModules()` でモジュールレベルのシングルトン状態を毎回リセット
 */

// ============================================================
// テスト用ヘルパー: モック STOMP Client
// ============================================================

type StompFrameLike = { body: string }
type SubscribeCallback = (frame: StompFrameLike) => void
type OnConnectCallback = () => void

const mockSubscribeFn = vi.fn((_destination: string, _callback: SubscribeCallback) => ({
  id: 'sub-mock',
  unsubscribe: mockUnsubscribeFn,
}))
const mockUnsubscribeFn = vi.fn()
const mockActivateFn = vi.fn()

vi.mock('@stomp/stompjs', () => {
  class MockClient {
    connected = false
    private onConnect: OnConnectCallback | null = null
    connectHeaders: Record<string, string> = {}

    constructor(config: { onConnect?: OnConnectCallback }) {
      if (config.onConnect) {
        this.onConnect = config.onConnect
      }
    }

    activate() {
      mockActivateFn()
      this.connected = true
      const cb = this.onConnect
      if (cb) {
        Promise.resolve().then(() => cb())
      }
    }

    subscribe(destination: string, callback: SubscribeCallback) {
      return mockSubscribeFn(destination, callback)
    }
  }

  return { Client: MockClient }
})

// ============================================================
// useAuthStore のモック
// ============================================================

vi.mock('~/stores/auth', () => ({
  useAuthStore: () => ({ accessToken: 'mock-token' }),
}))

// 一部のコードで autoImport された useAuthStore がトップレベル global として参照されるため、
// グローバル名前解決もダミー化しておく（Nuxt の auto-imports を擬似的に補う）
;(globalThis as { useAuthStore?: () => { accessToken: string } }).useAuthStore = () => ({
  accessToken: 'mock-token',
})

// ============================================================
// テスト対象を動的 import（シングルトンを毎回リセット）
// ============================================================

async function freshUseCorkboardEventListener() {
  vi.resetModules()
  vi.doMock('@stomp/stompjs', () => {
    class MockClient {
      connected = false
      private onConnect: OnConnectCallback | null = null
      connectHeaders: Record<string, string> = {}

      constructor(config: { onConnect?: OnConnectCallback }) {
        if (config.onConnect) {
          this.onConnect = config.onConnect
        }
      }

      activate() {
        mockActivateFn()
        this.connected = true
        const cb = this.onConnect
        if (cb) {
          Promise.resolve().then(() => cb())
        }
      }

      subscribe(destination: string, callback: SubscribeCallback) {
        return mockSubscribeFn(destination, callback)
      }
    }

    return { Client: MockClient }
  })
  vi.doMock('~/stores/auth', () => ({
    useAuthStore: () => ({ accessToken: 'mock-token' }),
  }))

  const mod = await import('~/composables/useCorkboardEventListener')
  return mod.useCorkboardEventListener
}

async function flushPromises(rounds = 5): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    await Promise.resolve()
  }
}

// ============================================================
// テスト本体
// ============================================================

describe('useCorkboardEventListener — STOMP 購読・受信', () => {
  beforeEach(() => {
    mockSubscribeFn.mockClear().mockImplementation(
      (_destination: string, _callback: SubscribeCallback) => ({
        id: 'sub-mock',
        unsubscribe: mockUnsubscribeFn,
      }),
    )
    mockUnsubscribeFn.mockClear()
    mockActivateFn.mockClear()
  })

  describe('connect()', () => {
    it('CORK-WS-UNIT-001: /topic/corkboard/{boardId} に SUBSCRIBE される', async () => {
      const useCorkboardEventListener = await freshUseCorkboardEventListener()
      const onEvent = vi.fn()

      const listener = useCorkboardEventListener({ boardId: 42, onEvent })
      listener.connect()
      await flushPromises()

      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)
      expect(mockSubscribeFn).toHaveBeenCalledWith(
        '/topic/corkboard/42',
        expect.any(Function),
      )
    })

    it('CORK-WS-UNIT-002: 同一 boardId への 2 つの listener でも SUBSCRIBE は 1 回（参照カウント）', async () => {
      const useCorkboardEventListener = await freshUseCorkboardEventListener()
      const onEvent1 = vi.fn()
      const onEvent2 = vi.fn()

      const listener1 = useCorkboardEventListener({ boardId: 7, onEvent: onEvent1 })
      const listener2 = useCorkboardEventListener({ boardId: 7, onEvent: onEvent2 })
      listener1.connect()
      listener2.connect()
      await flushPromises()

      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)
    })

    it('CORK-WS-UNIT-005: 異なる boardId にはそれぞれ SUBSCRIBE が走る', async () => {
      const useCorkboardEventListener = await freshUseCorkboardEventListener()
      const onEvent = vi.fn()

      useCorkboardEventListener({ boardId: 1, onEvent }).connect()
      await flushPromises()
      useCorkboardEventListener({ boardId: 2, onEvent }).connect()
      await flushPromises()

      expect(mockSubscribeFn).toHaveBeenCalledTimes(2)
      expect(mockSubscribeFn).toHaveBeenCalledWith(
        '/topic/corkboard/1',
        expect.any(Function),
      )
      expect(mockSubscribeFn).toHaveBeenCalledWith(
        '/topic/corkboard/2',
        expect.any(Function),
      )
    })
  })

  describe('STOMP メッセージ受信', () => {
    it('CORK-WS-UNIT-003: 受信メッセージが onEvent へ JSON.parse 済みで渡される', async () => {
      const useCorkboardEventListener = await freshUseCorkboardEventListener()
      const onEvent = vi.fn()

      const listener = useCorkboardEventListener({ boardId: 100, onEvent })
      listener.connect()
      await flushPromises()

      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)
      const [, stompCallback] = mockSubscribeFn.mock.calls[0] as [string, SubscribeCallback]

      const fakePayload = {
        boardId: 100,
        eventType: 'CARD_CREATED' as const,
        cardId: 555,
        sectionId: null,
      }
      stompCallback({ body: JSON.stringify(fakePayload) })

      expect(onEvent).toHaveBeenCalledTimes(1)
      expect(onEvent).toHaveBeenCalledWith(fakePayload)
    })

    it('同一 boardId の複数 listener へ同時配信される（fan-out）', async () => {
      const useCorkboardEventListener = await freshUseCorkboardEventListener()
      const onEvent1 = vi.fn()
      const onEvent2 = vi.fn()

      useCorkboardEventListener({ boardId: 200, onEvent: onEvent1 }).connect()
      useCorkboardEventListener({ boardId: 200, onEvent: onEvent2 }).connect()
      await flushPromises()

      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)
      const [, stompCallback] = mockSubscribeFn.mock.calls[0] as [string, SubscribeCallback]

      const payload = {
        boardId: 200,
        eventType: 'CARD_MOVED' as const,
        cardId: 1,
        sectionId: null,
      }
      stompCallback({ body: JSON.stringify(payload) })

      expect(onEvent1).toHaveBeenCalledWith(payload)
      expect(onEvent2).toHaveBeenCalledWith(payload)
    })
  })

  describe('disconnect()', () => {
    it('CORK-WS-UNIT-004: カウントが 0 になったら STOMP UNSUBSCRIBE が呼ばれる', async () => {
      const useCorkboardEventListener = await freshUseCorkboardEventListener()
      const onEvent = vi.fn()

      const listener = useCorkboardEventListener({ boardId: 50, onEvent })
      listener.connect()
      await flushPromises()
      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)

      listener.disconnect()
      expect(mockUnsubscribeFn).toHaveBeenCalledTimes(1)
    })

    it('2 つの listener で disconnect が 1 回ではまだ UNSUBSCRIBE されない', async () => {
      const useCorkboardEventListener = await freshUseCorkboardEventListener()
      const onEvent1 = vi.fn()
      const onEvent2 = vi.fn()

      const l1 = useCorkboardEventListener({ boardId: 60, onEvent: onEvent1 })
      const l2 = useCorkboardEventListener({ boardId: 60, onEvent: onEvent2 })
      l1.connect()
      l2.connect()
      await flushPromises()

      l1.disconnect()
      expect(mockUnsubscribeFn).toHaveBeenCalledTimes(0)

      l2.disconnect()
      expect(mockUnsubscribeFn).toHaveBeenCalledTimes(1)
    })

    it('connect() していない状態で disconnect() を呼んでも何もしない', async () => {
      const useCorkboardEventListener = await freshUseCorkboardEventListener()
      const onEvent = vi.fn()

      const listener = useCorkboardEventListener({ boardId: 999, onEvent })
      // connect せずに disconnect
      listener.disconnect()

      expect(mockUnsubscribeFn).toHaveBeenCalledTimes(0)
    })
  })
})
