import type { Client, IFrame, StompSubscription } from '@stomp/stompjs'
import { Client as StompClient } from '@stomp/stompjs'
import { useEventBus } from '@vueuse/core'
import type {
  ChatChannelListResponse,
  ChatChannelDetailResponse,
  ChatMessageListResponse,
  ChatMessageResponse,
  ChatChannelResponse,
  CreateChannelRequest,
  ChatChannelEvent,
  ChatThreadResponse,
  ChatActiveThreadItem,
} from '~/types/chat'

// ============================================================
// モジュールレベルのシングルトン状態（composable再呼び出しを跨いで維持）
// ============================================================

const _subscriptionCounts = new Map<number, number>()
const _stompSubscriptions = new Map<number, StompSubscription>()
const _eventSubscriptionCounts = new Map<number, number>()
const _eventStompSubscriptions = new Map<number, StompSubscription>()
let _stompClient: Client | null = null
/** 再接続後に再サブスクリプションするチャンネルのセット */
const _subscribedChannels = new Set<number>()
/** 各チャンネルで最後に受信した MESSAGE_CREATED の messageId（キャッチアップ用） */
const _lastMessageIdByChannel = new Map<number, number>()
/** 指数バックオフの試行回数 */
let _reconnectAttempts = 0
/** 初回接続かどうか（再接続後の再サブスクリプション判定用） */
let _isFirstConnect = true

interface ChannelListParams {
  teamId?: number
  organizationId?: number
  channelType?: string
  isArchived?: boolean
  cursor?: string
  limit?: number
}

export function useChatApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        query.set(key, String(value))
      }
    }
    return query.toString()
  }

  // === Channels ===
  async function getChannels(params?: ChannelListParams) {
    const qs = buildQuery({
      team_id: params?.teamId,
      organization_id: params?.organizationId,
      channel_type: params?.channelType,
      is_archived: params?.isArchived,
      cursor: params?.cursor,
      limit: params?.limit,
    })
    return api<ChatChannelListResponse>(`/api/v1/chat/channels?${qs}`)
  }

  async function getChannel(channelId: number) {
    return api<ChatChannelDetailResponse>(`/api/v1/chat/channels/${channelId}`)
  }

  async function createChannel(body: CreateChannelRequest) {
    return api<{ data: ChatChannelResponse }>('/api/v1/chat/channels', {
      method: 'POST',
      body,
    })
  }

  async function updateChannel(channelId: number, body: Record<string, unknown>) {
    return api<{ data: ChatChannelResponse }>(`/api/v1/chat/channels/${channelId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteChannel(channelId: number) {
    return api(`/api/v1/chat/channels/${channelId}`, { method: 'DELETE' })
  }

  async function archiveChannel(channelId: number, archived: boolean) {
    return api(`/api/v1/chat/channels/${channelId}/archive`, {
      method: 'POST',
      body: { archived },
    })
  }

  // === Members ===
  async function addMembers(channelId: number, userIds: number[]) {
    return api(`/api/v1/chat/channels/${channelId}/members`, {
      method: 'POST',
      body: { userIds },
    })
  }

  async function removeMember(channelId: number, userId: number) {
    return api(`/api/v1/chat/channels/${channelId}/members/${userId}`, {
      method: 'DELETE',
    })
  }

  async function joinChannel(channelId: number) {
    return api(`/api/v1/chat/channels/${channelId}/join`, { method: 'POST' })
  }

  async function changeMemberRole(channelId: number, userId: number, role: string) {
    return api(`/api/v1/chat/channels/${channelId}/members/${userId}/role`, {
      method: 'PATCH',
      body: { role },
    })
  }

  async function updateMySettings(channelId: number, settings: Record<string, unknown>) {
    return api(`/api/v1/chat/channels/${channelId}/members/me`, {
      method: 'PATCH',
      body: settings,
    })
  }

  // === Messages ===
  async function getMessages(channelId: number, cursor?: string, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<ChatMessageListResponse>(`/api/v1/chat/channels/${channelId}/messages?${qs}`)
  }

  async function sendMessage(
    channelId: number,
    body: string,
    parentId?: number,
    attachmentKeys?: string[],
  ) {
    return api<{ data: ChatMessageResponse }>(`/api/v1/chat/channels/${channelId}/messages`, {
      method: 'POST',
      body: { body, parentId, attachmentKeys },
    })
  }

  async function editMessage(messageId: number, body: string) {
    return api<{ data: ChatMessageResponse }>(`/api/v1/chat/messages/${messageId}`, {
      method: 'PATCH',
      body: { body },
    })
  }

  async function deleteMessage(messageId: number) {
    return api(`/api/v1/chat/messages/${messageId}`, { method: 'DELETE' })
  }

  async function getThread(messageId: number, cursor?: string, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<{ data: ChatThreadResponse }>(`/api/v1/chat/messages/${messageId}/thread?${qs}`)
  }

  async function getActiveThreads(channelId: number, cursor?: string, limit?: number) {
    const qs = buildQuery({ cursor, limit })
    return api<{
      data: ChatActiveThreadItem[]
      meta: { nextCursor: string | null; hasMore: boolean }
    }>(`/api/v1/chat/channels/${channelId}/active-threads?${qs}`)
  }

  async function migrateToBoard(
    messageId: number,
    boardId: number,
    title: string,
    copyHistory: boolean,
  ) {
    return api<{ data: { bulletinThreadId: string; bulletinThreadUrl: string } }>(
      `/api/v1/chat/messages/${messageId}/migrate-to-board`,
      {
        method: 'POST',
        body: { boardId, title, copyHistory },
      },
    )
  }

  // === Reactions ===
  async function addReaction(messageId: number, emoji: string) {
    return api(`/api/v1/chat/messages/${messageId}/reactions`, {
      method: 'POST',
      body: { emoji },
    })
  }

  async function removeReaction(messageId: number, emoji: string) {
    return api(`/api/v1/chat/messages/${messageId}/reactions/${encodeURIComponent(emoji)}`, {
      method: 'DELETE',
    })
  }

  // === Pin ===
  async function togglePin(messageId: number, pinned: boolean) {
    return api(`/api/v1/chat/messages/${messageId}/pin`, {
      method: 'POST',
      body: { pinned },
    })
  }

  // === Read ===
  async function markAsRead(channelId: number) {
    return api(`/api/v1/chat/channels/${channelId}/read`, { method: 'POST' })
  }

  // === Search ===
  async function searchMessages(channelId: number, q: string, cursor?: string) {
    const qs = buildQuery({ q, cursor })
    return api<ChatMessageListResponse>(`/api/v1/chat/channels/${channelId}/messages/search?${qs}`)
  }

  // === DM ===
  async function getOrCreateDm(userId: number) {
    return api<{ data: ChatChannelResponse }>('/api/v1/chat/channels/dm', {
      method: 'POST',
      body: { userId },
    })
  }

  async function inviteToZimmer(
    channelId: number,
    body: { userIds: number[]; shareHistory: boolean },
  ) {
    return api<{ data: ChatChannelResponse }>(
      `/api/v1/chat/channels/${channelId}/invite-to-zimmer`,
      {
        method: 'POST',
        body,
      },
    )
  }

  // === Bookmark ===
  async function bookmarkMessage(messageId: number) {
    return api('/api/v1/chat/bookmarks', { method: 'POST', body: { messageId } })
  }

  async function removeBookmark(messageId: number) {
    return api(`/api/v1/chat/bookmarks/${messageId}`, { method: 'DELETE' })
  }

  async function getBookmarks(cursor?: string) {
    const qs = buildQuery({ cursor })
    return api<ChatMessageListResponse>(`/api/v1/chat/bookmarks?${qs}`)
  }

  // === Channel Settings ===
  async function updateChannelSettings(
    channelId: number,
    settings: { isMuted?: boolean; isPinned?: boolean; category?: string },
  ) {
    return api(`/api/v1/chat/channels/${channelId}/settings`, {
      method: 'PATCH',
      body: settings,
    })
  }

  // === Upload / Download ===
  async function getUploadUrl(fileName: string, contentType: string) {
    return api<{ data: { uploadUrl: string; fileKey: string } }>('/api/v1/chat/files/upload-url', {
      method: 'POST',
      body: { fileName, contentType },
    })
  }

  async function getDownloadUrl(fileKey: string) {
    return api<{ data: { downloadUrl: string } }>(
      `/api/v1/chat/files/${encodeURIComponent(fileKey)}/download-url`,
    )
  }

  // === Forward ===
  async function forwardMessage(messageId: number, targetChannelId: number) {
    return api<{ data: ChatMessageResponse }>(`/api/v1/chat/messages/${messageId}/forward`, {
      method: 'POST',
      body: { targetChannelId },
    })
  }

  // ============================================================
  // WebSocket STOMP 購読管理（参照カウント方式）
  // ============================================================

  /**
   * STOMP クライアントが未接続なら接続する。
   * 接続済みの場合は即座に resolve する。
   *
   * F04.2.1 Phase10:
   * - CONNECT フレームに Authorization ヘッダー（Bearer トークン）を付与する
   * - beforeConnect で再接続時に最新トークンへ差し替える（リフレッシュ対応）
   */
  function ensureConnected(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      if (_stompClient !== null && _stompClient.connected) {
        resolve()
        return
      }

      const auth = useAuthStore()
      const client = new StompClient({
        webSocketFactory: () => new WebSocket('/ws'),
        connectHeaders: { Authorization: `Bearer ${auth.accessToken ?? ''}` },
        beforeConnect: () => {
          // 再接続時に最新トークンを差し替える（リフレッシュ後の再接続でも有効なトークンを使う）
          if (_stompClient !== null) {
            _stompClient.connectHeaders = {
              Authorization: `Bearer ${useAuthStore().accessToken ?? ''}`,
            }
          }
        },
        reconnectDelay: 1000,
        onConnect: () => {
          if (!_isFirstConnect) {
            // 再接続時: 全チャンネルを再サブスクリプション
            for (const channelId of _subscribedChannels) {
              _resubscribeChannel(channelId)
            }
            // 切断中に届いたメッセージをキャッチアップ
            for (const [channelId, lastId] of _lastMessageIdByChannel) {
              _catchupMessages(channelId, lastId)
            }
          }
          _isFirstConnect = false
          _reconnectAttempts = 0
          resolve()
        },
        onDisconnect: () => {
          // 指数バックオフ: 1s → 2s → 4s → 8s → 16s → 最大30s
          const delays = [1000, 2000, 4000, 8000, 16000, 30000]
          _reconnectAttempts = Math.min(_reconnectAttempts + 1, delays.length - 1)
          if (_stompClient !== null) {
            _stompClient.reconnectDelay = delays[_reconnectAttempts] ?? 30000
          }
        },
        onStompError: (frame: IFrame) => {
          reject(new Error(`STOMP エラー: ${frame.headers['message'] ?? 'unknown'}`))
        },
      })
      _stompClient = client
      client.activate()
    })
  }

  /** 再接続後に指定チャンネルを再サブスクリプションする（内部用）。 */
  function _resubscribeChannel(channelId: number): void {
    if (_stompClient === null || !_stompClient.connected) return
    // 古いサブスクリプションを破棄
    _stompSubscriptions.get(channelId)?.unsubscribe()
    _stompSubscriptions.delete(channelId)
    // 新しいサブスクリプションを登録
    const subscription = _stompClient.subscribe(
      `/topic/channels/${channelId}`,
      (frame: IFrame) => {
        try {
          const payload = JSON.parse(frame.body) as { type: string; data: unknown }
          // MESSAGE_CREATED 受信時に最終受信IDを更新
          if (payload.type === 'MESSAGE_CREATED') {
            const msg = payload.data as { id: number }
            if (msg.id) {
              _lastMessageIdByChannel.set(channelId, msg.id)
            }
          }
          useEventBus<{ type: string; data: unknown }>('chat:ws:event').emit({
            type: payload.type,
            data: payload.data,
          })
        } catch (err: unknown) {
          console.error(
            `[useChatApi] チャンネル ${channelId} の受信メッセージのパースに失敗しました:`,
            err,
          )
        }
      },
    )
    _stompSubscriptions.set(channelId, subscription)
  }

  /** 切断中に届いたメッセージを REST API でフェッチしてEventBusに流す（キャッチアップ用）。 */
  async function _catchupMessages(channelId: number, lastMessageId: number): Promise<void> {
    try {
      const res = await api<ChatMessageListResponse>(
        `/api/v1/chat/channels/${channelId}/messages`,
        { query: { cursor: lastMessageId, direction: 'after', limit: 100 } },
      )
      for (const msg of res.data) {
        useEventBus<{ type: string; data: unknown }>('chat:ws:event').emit({
          type: 'MESSAGE_CREATED',
          data: msg,
        })
      }
    } catch {
      // キャッチアップ失敗はサイレント（次回の WS 受信で補完される）
    }
  }

  /**
   * cursor（messageId）より新しいメッセージを取得する（WebSocket再接続後のキャッチアップ用）。
   * バックエンドの GET /channels/{channelId}/messages?direction=after に対応。
   *
   * @param channelId 対象チャンネル ID
   * @param cursor    最後に受信した messageId
   * @param limit     取得上限（デフォルト100）
   */
  async function getMessagesAfter(
    channelId: number,
    cursor: number,
    limit = 100,
  ): Promise<ChatMessageListResponse> {
    return api<ChatMessageListResponse>(
      `/api/v1/chat/channels/${channelId}/messages`,
      { query: { cursor, direction: 'after', limit } },
    )
  }

  /**
   * タイピングインジケーターを STOMP で送信する（デバウンス用・2秒に1回を推奨）。
   * タイピング通知は非クリティカルなため、送信失敗はサイレントに処理する。
   *
   * @param channelId 送信先チャンネル ID
   */
  function sendTyping(channelId: number): void {
    ensureConnected()
      .then(() => {
        if (_stompClient === null || !_stompClient.connected) return
        _stompClient.publish({
          destination: '/app/chat.typing',
          body: JSON.stringify({ channelId }),
        })
      })
      .catch(() => {}) // サイレント失敗（タイピング通知は非クリティカル）
  }

  /**
   * 指定チャンネルの STOMP 購読を開始する（参照カウント方式）。
   * 同一 channelId を複数回呼んでも SUBSCRIBE は1回のみ実行される。
   *
   * @param channelId 購読対象のチャンネル ID
   */
  function subscribeChannel(channelId: number): void {
    const count = _subscriptionCounts.get(channelId) ?? 0
    _subscriptionCounts.set(channelId, count + 1)

    if (count === 0) {
      _subscribedChannels.add(channelId)
      // 初回のみ STOMP SUBSCRIBE を実行
      ensureConnected()
        .then(() => {
          if (_stompClient === null) return

          const subscription = _stompClient.subscribe(
            `/topic/channels/${channelId}`,
            (frame: IFrame) => {
              try {
                const payload = JSON.parse(frame.body) as { type: string; data: unknown }
                // MESSAGE_CREATED 受信時に最終受信IDを更新
                if (payload.type === 'MESSAGE_CREATED') {
                  const msg = payload.data as { id: number }
                  if (msg.id) {
                    _lastMessageIdByChannel.set(channelId, msg.id)
                  }
                }
                useEventBus<{ type: string; data: unknown }>('chat:ws:event').emit({
                  type: payload.type,
                  data: payload.data,
                })
              } catch (err: unknown) {
                console.error(
                  `[useChatApi] チャンネル ${channelId} の受信メッセージのパースに失敗しました:`,
                  err,
                )
              }
            },
          )
          _stompSubscriptions.set(channelId, subscription)
        })
        .catch((err: unknown) => {
          console.error(`[useChatApi] チャンネル ${channelId} の購読接続に失敗しました:`, err)
        })
    }
  }

  /**
   * 指定チャンネルの STOMP 購読参照カウントをデクリメントする。
   * カウントが 0 になったら STOMP UNSUBSCRIBE を実行する。
   *
   * @param channelId 購読解除対象のチャンネル ID
   */
  function unsubscribeChannel(channelId: number): void {
    const count = _subscriptionCounts.get(channelId) ?? 0
    if (count <= 0) {
      return
    }

    const newCount = count - 1
    _subscriptionCounts.set(channelId, newCount)

    if (newCount === 0) {
      _stompSubscriptions.get(channelId)?.unsubscribe()
      _stompSubscriptions.delete(channelId)
      _subscriptionCounts.delete(channelId)
      _subscribedChannels.delete(channelId)
      _lastMessageIdByChannel.delete(channelId)
    }
  }

  /**
   * 指定チャンネルのイベントトピック（/topic/channels/{id}/events）を STOMP 購読する。
   * 参照カウント方式で、同一 channelId を複数回呼んでも SUBSCRIBE は 1 回のみ実行される。
   *
   * 受信したイベントは {@code useEventBus<{ channelId, event }>('chat:channel:event')} で配信される。
   *
   * @param channelId 購読対象のチャンネル ID
   */
  function subscribeChannelEvents(channelId: number): void {
    const count = _eventSubscriptionCounts.get(channelId) ?? 0
    _eventSubscriptionCounts.set(channelId, count + 1)

    if (count === 0) {
      ensureConnected()
        .then(() => {
          if (_stompClient === null) return

          const subscription = _stompClient.subscribe(
            `/topic/channels/${channelId}/events`,
            (frame: IFrame) => {
              try {
                const event = JSON.parse(frame.body) as ChatChannelEvent
                useEventBus<{ channelId: number; event: ChatChannelEvent }>(
                  'chat:channel:event',
                ).emit({ channelId, event })
              } catch (err: unknown) {
                console.error(
                  `[useChatApi] チャンネル ${channelId} のイベントパースに失敗しました:`,
                  err,
                )
              }
            },
          )
          _eventStompSubscriptions.set(channelId, subscription)
        })
        .catch((err: unknown) => {
          console.error(
            `[useChatApi] チャンネル ${channelId} のイベント購読接続に失敗しました:`,
            err,
          )
        })
    }
  }

  /**
   * 指定チャンネルのイベント購読参照カウントをデクリメントする。
   * カウントが 0 になったら STOMP UNSUBSCRIBE を実行する。
   *
   * @param channelId 購読解除対象のチャンネル ID
   */
  function unsubscribeChannelEvents(channelId: number): void {
    const count = _eventSubscriptionCounts.get(channelId) ?? 0
    if (count <= 0) {
      return
    }

    const newCount = count - 1
    _eventSubscriptionCounts.set(channelId, newCount)

    if (newCount === 0) {
      _eventStompSubscriptions.get(channelId)?.unsubscribe()
      _eventStompSubscriptions.delete(channelId)
      _eventSubscriptionCounts.delete(channelId)
    }
  }

  return {
    getChannels,
    getChannel,
    createChannel,
    updateChannel,
    deleteChannel,
    archiveChannel,
    addMembers,
    removeMember,
    joinChannel,
    changeMemberRole,
    updateMySettings,
    getMessages,
    sendMessage,
    editMessage,
    deleteMessage,
    getThread,
    getActiveThreads,
    migrateToBoard,
    addReaction,
    removeReaction,
    togglePin,
    markAsRead,
    searchMessages,
    getOrCreateDm,
    inviteToZimmer,
    bookmarkMessage,
    removeBookmark,
    getBookmarks,
    getUploadUrl,
    getDownloadUrl,
    forwardMessage,
    updateChannelSettings,
    getMessagesAfter,
    sendTyping,
    subscribeChannel,
    unsubscribeChannel,
    subscribeChannelEvents,
    unsubscribeChannelEvents,
  }
}

// ============================================================
// Visibility API 連携（モジュールレベル・SSRガード付き）
// ============================================================

/** Visibility API でタブ状態に応じて reconnectDelay を調整する。 */
if (import.meta.client) {
  document.addEventListener('visibilitychange', () => {
    if (_stompClient === null) return
    if (document.hidden) {
      // バックグラウンドタブ: 再接続間隔を延長
      _stompClient.reconnectDelay = 60_000
    } else {
      // フォアグラウンド復帰: 即再接続
      _stompClient.reconnectDelay = 1000
      if (!_stompClient.connected) {
        _stompClient.activate()
      }
    }
  })
}
