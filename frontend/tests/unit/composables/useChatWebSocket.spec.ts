import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F04.2.1 Phase6 useChatApi — WebSocket STOMP 購読管理のユニットテスト。
 *
 * テストケース:
 * 1. 同一 channelId を2回 subscribeChannel しても STOMP SUBSCRIBE が1回のみ呼ばれる
 * 2. unsubscribeChannel でカウントが0になったら STOMP UNSUBSCRIBE が呼ばれる
 * 3. カウントが1のとき unsubscribeChannel を呼んでも SUBSCRIBE が残っている（カウントが0になってから UNSUBSCRIBE）
 */

// ============================================================
// テスト用ヘルパー: モック STOMP Client の制御
// ============================================================

type StompFrameLike = { body: string }
type SubscribeCallback = (frame: StompFrameLike) => void
type OnConnectCallback = () => void

/** テスト間で共有するモック関数 */
const mockSubscribeFn = vi.fn((_destination: string, _callback: SubscribeCallback) => ({
  id: 'sub-mock',
  unsubscribe: mockUnsubscribeFn,
}))
const mockUnsubscribeFn = vi.fn()
const mockActivateFn = vi.fn()

/** onConnect コールバックをキャプチャして後から呼べるようにする */
let capturedOnConnect: OnConnectCallback | null = null

vi.mock('@stomp/stompjs', () => {
  class MockClient {
    connected = false
    private onConnect: OnConnectCallback | null = null

    constructor(config: { onConnect?: OnConnectCallback }) {
      if (config.onConnect) {
        this.onConnect = config.onConnect
        capturedOnConnect = config.onConnect
      }
    }

    activate() {
      mockActivateFn()
      // 接続を同期的にシミュレート（Promise マイクロタスク内で解決）
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
// useApi のモック
// ============================================================

const mockApiFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApiFetch,
}))

// ============================================================
// useEventBus のモック
// ============================================================

const mockEmit = vi.fn()
vi.mock('@vueuse/core', () => ({
  useEventBus: vi.fn(() => ({
    emit: mockEmit,
    on: vi.fn(),
    off: vi.fn(),
  })),
}))

// ============================================================
// テスト対象を動的 import
//
// NOTE: useChatApi.ts にはモジュールレベルのシングルトン（_subscriptionCounts / _stompSubscriptions / _stompClient）
// があるため、テスト間でシングルトン状態が残る。
// 各テストで vi.resetModules() + await import() によりモジュールを再ロードして状態をリセットする。
// ============================================================

async function freshUseChatApi() {
  vi.resetModules()
  // モックはリセット後に再登録が必要
  vi.doMock('@stomp/stompjs', () => {
    class MockClient {
      connected = false
      private onConnect: OnConnectCallback | null = null

      constructor(config: { onConnect?: OnConnectCallback }) {
        if (config.onConnect) {
          this.onConnect = config.onConnect
          capturedOnConnect = config.onConnect
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
  vi.doMock('~/composables/useApi', () => ({ useApi: () => mockApiFetch }))
  vi.doMock('@vueuse/core', () => ({
    useEventBus: vi.fn(() => ({ emit: mockEmit, on: vi.fn(), off: vi.fn() })),
  }))

  const mod = await import('~/composables/useChatApi')
  return mod.useChatApi
}

/**
 * subscribeChannel が内部で呼ぶ ensureConnected → activate → onConnect(Promise) → subscribe
 * という非同期チェーンを全て flush する。
 * Promise.resolve() を複数回 await することでマイクロタスクキューを空にする。
 */
async function flushPromises(rounds = 5) {
  for (let i = 0; i < rounds; i++) {
    await Promise.resolve()
  }
}

// ============================================================
// テスト本体
// ============================================================

describe('useChatApi — WebSocket STOMP 購読管理', () => {
  beforeEach(() => {
    mockSubscribeFn.mockClear().mockImplementation(
      (_destination: string, _callback: SubscribeCallback) => ({
        id: 'sub-mock',
        unsubscribe: mockUnsubscribeFn,
      }),
    )
    mockUnsubscribeFn.mockClear()
    mockActivateFn.mockClear()
    mockEmit.mockClear()
    capturedOnConnect = null
  })

  describe('subscribeChannel', () => {
    it('同一 channelId を2回呼んでも STOMP SUBSCRIBE が1回のみ実行される', async () => {
      const useChatApi = await freshUseChatApi()
      const api = useChatApi()

      api.subscribeChannel(42)
      api.subscribeChannel(42)
      await flushPromises()

      // SUBSCRIBE は1回のみ
      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)
      expect(mockSubscribeFn).toHaveBeenCalledWith('/topic/channels/42', expect.any(Function))
    })

    it('異なる channelId では SUBSCRIBE がそれぞれ1回ずつ実行される', async () => {
      const useChatApi = await freshUseChatApi()
      const api = useChatApi()

      api.subscribeChannel(1)
      await flushPromises()

      api.subscribeChannel(2)
      await flushPromises()

      // チャンネル1と2それぞれで SUBSCRIBE が呼ばれる
      expect(mockSubscribeFn).toHaveBeenCalledTimes(2)
      expect(mockSubscribeFn).toHaveBeenCalledWith('/topic/channels/1', expect.any(Function))
      expect(mockSubscribeFn).toHaveBeenCalledWith('/topic/channels/2', expect.any(Function))
    })
  })

  describe('unsubscribeChannel', () => {
    it('カウントが0になったら STOMP UNSUBSCRIBE が呼ばれる', async () => {
      const useChatApi = await freshUseChatApi()
      const api = useChatApi()

      // 1回 subscribe
      api.subscribeChannel(10)
      await flushPromises()
      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)

      // 1回 unsubscribe → カウントが0になるので UNSUBSCRIBE が呼ばれる
      api.unsubscribeChannel(10)

      expect(mockUnsubscribeFn).toHaveBeenCalledTimes(1)
    })

    it('subscribe2回 → unsubscribe1回ではまだ UNSUBSCRIBE されない', async () => {
      const useChatApi = await freshUseChatApi()
      const api = useChatApi()

      // 2回 subscribe（1回目のみ STOMP SUBSCRIBE が実行される）
      api.subscribeChannel(20)
      api.subscribeChannel(20)
      await flushPromises()

      // SUBSCRIBE は1回のみ
      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)

      // 1回 unsubscribe → カウントは2→1 なので UNSUBSCRIBE はまだ呼ばれない
      api.unsubscribeChannel(20)
      expect(mockUnsubscribeFn).toHaveBeenCalledTimes(0)

      // もう1回 unsubscribe → カウントが0になるので UNSUBSCRIBE が呼ばれる
      api.unsubscribeChannel(20)
      expect(mockUnsubscribeFn).toHaveBeenCalledTimes(1)
    })

    it('購読していないチャンネルを unsubscribeChannel しても何もしない', async () => {
      const useChatApi = await freshUseChatApi()
      const api = useChatApi()

      // subscribe せずに unsubscribe を呼ぶ
      api.unsubscribeChannel(999)

      // UNSUBSCRIBE は呼ばれない
      expect(mockUnsubscribeFn).toHaveBeenCalledTimes(0)
    })
  })

  describe('STOMP メッセージ受信', () => {
    it('SUBSCRIBE コールバックが呼ばれると useEventBus.emit が発火する', async () => {
      const useChatApi = await freshUseChatApi()
      const api = useChatApi()

      // subscribe して STOMP SUBSCRIBE コールバックをキャプチャ
      api.subscribeChannel(30)
      await flushPromises()

      expect(mockSubscribeFn).toHaveBeenCalledTimes(1)
      const [_destination, stompCallback] = mockSubscribeFn.mock.calls[0] as [
        string,
        SubscribeCallback,
      ]

      // 受信メッセージをシミュレート
      const fakeMessage = {
        id: 1,
        channelId: 30,
        sender: null,
        parentId: null,
        body: 'こんにちは',
        isEdited: false,
        isSystem: false,
        isPinned: false,
        replyCount: 0,
        reactionCount: 0,
        reactionSummary: {},
        myReactions: [],
        attachments: [],
        isBookmarked: false,
        forwardedFrom: null,
        isDeleted: false,
        createdAt: '2026-04-26T00:00:00Z',
        updatedAt: '2026-04-26T00:00:00Z',
      }
      stompCallback({ body: JSON.stringify(fakeMessage) })

      // EventBus で emit が呼ばれること
      expect(mockEmit).toHaveBeenCalledTimes(1)
      expect(mockEmit).toHaveBeenCalledWith(
        expect.objectContaining({ id: 1, body: 'こんにちは', channelId: 30 }),
      )
    })
  })
})
