import type {
  ChatMessageListResponse,
  ChatMessageResponse,
  ChatBookmark,
} from '~/types/chat'
import { buildQuery } from './chatQuery'
import {
  mapBeMessage,
  mapBeMessageList,
  type BeMessageResponse,
  type BeMessageListResponse,
  type BeBookmarkResponse,
} from './chatMessageMapper'

/**
 * チャットメッセージ系 API を提供する composable。
 *
 * BE `MessageResponse` はネスト形状で返るため、メッセージを返す全 REST 経路で
 * {@link mapBeMessage} / {@link mapBeMessageList} を唯一の通過点として FE フラット型
 * （{@link ChatMessageResponse}）へ変換する。これにより消費側（40+ 箇所）は無改修。
 *
 * 提供する関数:
 * - メッセージ CRUD: getMessages / sendMessage / editMessage / deleteMessage / getMessagesAfter
 * - ピン留め:        togglePin
 * - 既読:            markAsRead
 * - 検索:            searchMessages
 * - ブックマーク:    bookmarkMessage / removeBookmark / getBookmarks
 * - 添付:            getUploadUrl / getDownloadUrl
 * - 転送 / 移行:     forwardMessage / migrateToBoard
 */
export function useChatMessages() {
  const api = useApi()
  // composable 初期化時（setup コンテキスト内）に認証中ユーザー ID を取得する
  // （myReactions 抽出用）。
  const authStore = useAuthStore()
  const currentUserId = computed(() => authStore.user?.id)

  // === Messages ===
  async function getMessages(
    channelId: number,
    cursor?: string,
    limit?: number,
  ): Promise<ChatMessageListResponse> {
    const qs = buildQuery({ cursor, limit })
    const raw = await api<BeMessageListResponse>(
      `/api/v1/chat/channels/${channelId}/messages?${qs}`,
    )
    return mapBeMessageList(raw, currentUserId.value)
  }

  async function sendMessage(
    channelId: number,
    body: string,
    parentId?: number,
    attachmentKeys?: string[],
  ): Promise<{ data: ChatMessageResponse }> {
    const raw = await api<{ data: BeMessageResponse }>(
      `/api/v1/chat/channels/${channelId}/messages`,
      {
        method: 'POST',
        body: { body, parentId, attachmentKeys },
      },
    )
    return { data: mapBeMessage(raw.data, currentUserId.value) }
  }

  async function editMessage(
    messageId: number,
    body: string,
  ): Promise<{ data: ChatMessageResponse }> {
    const raw = await api<{ data: BeMessageResponse }>(`/api/v1/chat/messages/${messageId}`, {
      method: 'PATCH',
      body: { body },
    })
    return { data: mapBeMessage(raw.data, currentUserId.value) }
  }

  async function deleteMessage(messageId: number) {
    return api(`/api/v1/chat/messages/${messageId}`, { method: 'DELETE' })
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
  // BE 検索は `ApiResponse<List<...>>` で meta が欠落しうる。mapBeMessageList が
  // meta 欠落時に nextCursor=null / hasMore=false を合成する。
  async function searchMessages(
    channelId: number,
    q: string,
    cursor?: string,
  ): Promise<ChatMessageListResponse> {
    const qs = buildQuery({ q, cursor })
    const raw = await api<BeMessageListResponse>(
      `/api/v1/chat/channels/${channelId}/messages/search?${qs}`,
    )
    return mapBeMessageList(raw, currentUserId.value)
  }

  // === Bookmark ===
  async function bookmarkMessage(messageId: number) {
    return api('/api/v1/chat/bookmarks', { method: 'POST', body: { messageId } })
  }

  async function removeBookmark(messageId: number) {
    return api(`/api/v1/chat/bookmarks/${messageId}`, { method: 'DELETE' })
  }

  /**
   * ブックマーク一覧を取得する。
   *
   * BE は `BookmarkResponse[]`（ブックマーク自体・メッセージ本体ではない・meta 無し）を
   * 返すため、メッセージリストではなく {@link ChatBookmark} 配列として正しく返す。
   * （従来は ChatMessageListResponse を誤適用していた）
   */
  async function getBookmarks(cursor?: string): Promise<ChatBookmark[]> {
    const qs = buildQuery({ cursor })
    const raw = await api<{ data: BeBookmarkResponse[] }>(`/api/v1/chat/bookmarks?${qs}`)
    return (raw.data ?? []).map((b) => ({
      id: b.id,
      messageId: b.messageId,
      userId: b.userId,
      note: b.note,
      createdAt: b.createdAt,
    }))
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
  async function forwardMessage(
    messageId: number,
    targetChannelId: number,
  ): Promise<{ data: ChatMessageResponse }> {
    const raw = await api<{ data: BeMessageResponse }>(
      `/api/v1/chat/messages/${messageId}/forward`,
      {
        method: 'POST',
        body: { targetChannelId },
      },
    )
    return { data: mapBeMessage(raw.data, currentUserId.value) }
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
    const raw = await api<BeMessageListResponse>(
      `/api/v1/chat/channels/${channelId}/messages`,
      { query: { cursor, direction: 'after', limit } },
    )
    return mapBeMessageList(raw, currentUserId.value)
  }

  return {
    getMessages,
    sendMessage,
    editMessage,
    deleteMessage,
    migrateToBoard,
    togglePin,
    markAsRead,
    searchMessages,
    bookmarkMessage,
    removeBookmark,
    getBookmarks,
    getUploadUrl,
    getDownloadUrl,
    forwardMessage,
    getMessagesAfter,
  }
}
