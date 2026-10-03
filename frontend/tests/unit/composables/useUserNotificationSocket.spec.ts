import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useNotificationStore } from '~/stores/useNotificationStore'
import {
  useUserNotificationSocket,
  parseUserNotificationPayload,
} from '~/composables/useUserNotificationSocket'

/**
 * F10.7（WS 外部ブローカー化・隊5・AC-9前段）
 * useUserNotificationSocket — /user/queue/notifications 購読 composable のユニットテスト。
 *
 * 観点:
 *   SPEC-001: start() で既存の共有チャット接続（useChatWebSocket）の subscribeRaw を通じて
 *             '/user/queue/notifications' を購読する（独自の WebSocket 接続を新設しない）
 *   SPEC-002: 受信ペイロードを useNotificationStore.setLatestNotification へ反映する
 *   SPEC-003: start() を複数回呼んでも SUBSCRIBE は1回のみ（多重接続防止・グローバルに1本）
 *   SPEC-004: stop() で購読解除ハンドルが呼ばれ、以後 start() すると再購読できる
 *   SPEC-005: 不正フレーム（非 JSON / id 欠落）は store に反映しない
 *   SPEC-006: parseUserNotificationPayload は正常フレームを型へ載せる／不正フレームは null
 */

type SubscribeCallback = (body: string) => void

const mockUnsubscribeFn = vi.fn()
const mockSubscribeRawFn = vi.fn((_topic: string, _callback: SubscribeCallback) => mockUnsubscribeFn)

vi.mock('~/composables/chat/useChatWebSocket', () => ({
  useChatWebSocket: () => ({ subscribeRaw: mockSubscribeRawFn }),
}))

function notificationPayload(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    userId: 42,
    notificationType: 'RESERVATION_RECEIVED',
    priority: 'NORMAL',
    title: 'テスト通知',
    body: '本文',
    actionUrl: null,
    sourceType: 'RESERVATION',
    sourceId: 10,
    scopeType: 'TEAM',
    scopeId: 5,
    actorId: 7,
    isRead: false,
    readAt: null,
    channelsSent: 'WEBSOCKET',
    snoozedUntil: null,
    createdAt: '2026-07-10T09:00:00',
    ...overrides,
  }
}

describe('useUserNotificationSocket — /user/queue/notifications 購読', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    // モジュールレベルの購読ハンドルをテスト間でリセットする（先にクリーンアップしてからモックをクリアする —
    // 逆順だと前テストの unsubscribe 呼び出しが今回の呼び出し回数アサートに混入する）。
    useUserNotificationSocket().stop()
    mockSubscribeRawFn.mockClear()
    mockUnsubscribeFn.mockClear()
  })

  it('SPEC-001: start() で共有チャット接続の subscribeRaw を通じて所定トピックを購読する', () => {
    const socket = useUserNotificationSocket()
    socket.start()

    expect(mockSubscribeRawFn).toHaveBeenCalledTimes(1)
    expect(mockSubscribeRawFn).toHaveBeenCalledWith(
      '/user/queue/notifications',
      expect.any(Function),
    )
  })

  it('SPEC-002: 受信ペイロードを useNotificationStore.setLatestNotification へ反映する', () => {
    const socket = useUserNotificationSocket()
    socket.start()

    const store = useNotificationStore()
    expect(store.latestNotification).toBeNull()

    const [, callback] = mockSubscribeRawFn.mock.calls[0] as [string, SubscribeCallback]
    const payload = notificationPayload()
    callback(JSON.stringify(payload))

    expect(store.latestNotification).toEqual(payload)
  })

  it('SPEC-003: start() を複数回呼んでも SUBSCRIBE は1回のみ実行される（多重接続防止）', () => {
    const socket = useUserNotificationSocket()
    socket.start()
    socket.start()
    socket.start()

    expect(mockSubscribeRawFn).toHaveBeenCalledTimes(1)
  })

  it('SPEC-004: stop() で購読解除され、以後 start() すると再購読される', () => {
    const socket = useUserNotificationSocket()
    socket.start()
    expect(mockSubscribeRawFn).toHaveBeenCalledTimes(1)

    socket.stop()
    expect(mockUnsubscribeFn).toHaveBeenCalledTimes(1)

    socket.start()
    expect(mockSubscribeRawFn).toHaveBeenCalledTimes(2)
  })

  it('SPEC-005: 不正フレーム（非 JSON / id 欠落）は store に反映しない', () => {
    const socket = useUserNotificationSocket()
    socket.start()

    const store = useNotificationStore()
    const [, callback] = mockSubscribeRawFn.mock.calls[0] as [string, SubscribeCallback]

    callback('not json')
    expect(store.latestNotification).toBeNull()

    callback(JSON.stringify({ title: 'idなし' }))
    expect(store.latestNotification).toBeNull()
  })
})

describe('parseUserNotificationPayload — 受信本文の境界載せ替え', () => {
  it('SPEC-006a: 正常フレームを型へ載せる', () => {
    const payload = notificationPayload()
    const parsed = parseUserNotificationPayload(JSON.stringify(payload))
    expect(parsed).toEqual(payload)
  })

  it('SPEC-006b: 不正フレームは null にする', () => {
    expect(parseUserNotificationPayload('not json')).toBeNull()
    expect(parseUserNotificationPayload('null')).toBeNull()
    expect(parseUserNotificationPayload('[]')).toBeNull()
    // id 欠落
    expect(parseUserNotificationPayload(JSON.stringify({ title: 'x' }))).toBeNull()
    // id が文字列（数値でない）
    expect(parseUserNotificationPayload(JSON.stringify({ id: '1' }))).toBeNull()
  })
})
