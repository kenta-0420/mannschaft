import type {
  ChatMessageListResponse,
  ChatMessageResponse,
} from '~/types/chat'
import { buildQuery } from './chatQuery'

/**
 * チャットメッセージ系 API を提供する composable。
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
  async function searchMessages(channelId: number, q: string, cursor?: string) {
    const qs = buildQuery({ q, cursor })
    return api<ChatMessageListResponse>(`/api/v1/chat/channels/${channelId}/messages/search?${qs}`)
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
