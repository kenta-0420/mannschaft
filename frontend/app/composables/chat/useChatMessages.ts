import type {
  ChatMessageListResponse,
  ChatMessageResponse,
  ChatMessageAttachment,
} from '~/types/chat'
import { buildQuery } from './chatQuery'

// ─── BE ネスト形式の内部型（API 実形状） ────────────────────────────────────────
// BE MessageResponse.java がネスト設計で返す JSON に対応する型。
// FE 公開型 ChatMessageResponse はフラット設計のため、getMessages/sendMessage 後にマッパーで変換する。

interface BeReaction {
  id: number
  messageId: number
  userId: number
  emoji: string
  createdAt: string
}

interface BeAttachment {
  id: number
  messageId: number
  fileKey: string
  fileName: string
  fileSize: number
  contentType: string
  createdAt: string
}

interface BeMessageResponse {
  id: number
  channelId: number
  senderId: number | null
  thread: {
    parentId: number | null
    rootId: number | null
    depth: number
    suggestBoardMigration: boolean
  } | null
  content: {
    body: string | null
    forwardedFromId: number | null
    isEdited: boolean
    isSystem: boolean
    scheduledAt: string | null
  } | null
  engagement: {
    replyCount: number
    reactionCount: number
    isPinned: boolean
    attachments: BeAttachment[]
    reactions: BeReaction[]
  } | null
  audit: {
    createdAt: string
    updatedAt: string
  } | null
  // WebSocket ブロードキャスト経由でのみ返ってくる可能性のある追加フィールド
  senderDisplayName?: string | null
}

interface BeMessageListResponse {
  data: BeMessageResponse[]
  meta: {
    nextCursor: string | null
    /** BE は hasNext / hasMore どちらも使用。両フィールドを考慮する */
    hasMore?: boolean
    hasNext?: boolean
    limit?: number
  }
}

/**
 * BE ネスト形式の MessageResponse を FE フラット形式の ChatMessageResponse に変換する。
 *
 * 変換方針:
 * - content.* / thread.* / engagement.* / audit.* → フラット展開
 * - senderId のみのため sender.displayName は空文字（WS ブロードキャスト時は senderDisplayName を利用）
 * - myReactions は engagement.reactions から currentUserId で自身のリアクションを抽出
 * - reactionSummary は engagement.reactions から絵文字別集計
 * - isBookmarked / isDeleted は BE 未提供（デフォルト false）
 * - forwardedFrom は BE から forwardedFromId のみ返るため null（詳細取得は別実装）
 *
 * @param raw           BE から返ってきたネスト形式のメッセージ
 * @param currentUserId 認証中ユーザーID（myReactions 抽出用）
 */
function mapBeMessage(raw: BeMessageResponse, currentUserId?: number): ChatMessageResponse {
  const reactions = raw.engagement?.reactions ?? []

  const reactionSummary: Record<string, number> = {}
  for (const r of reactions) {
    reactionSummary[r.emoji] = (reactionSummary[r.emoji] ?? 0) + 1
  }

  const myReactions: string[] = currentUserId
    ? reactions.filter((r) => r.userId === currentUserId).map((r) => r.emoji)
    : []

  const attachments: ChatMessageAttachment[] = (raw.engagement?.attachments ?? []).map((att) => ({
    id: att.id,
    fileName: att.fileName,
    fileKey: att.fileKey,
    fileSize: att.fileSize,
    // mimeType は contentType にマッピング
    mimeType: att.contentType ?? '',
    url: '', // 個別の presigned URL は getDownloadUrl で取得
  }))

  const senderDisplayName = raw.senderDisplayName ?? ''

  return {
    id: raw.id,
    channelId: raw.channelId,
    sender: raw.senderId != null
      ? { id: raw.senderId, displayName: senderDisplayName, avatarUrl: null }
      : null,
    parentId: raw.thread?.parentId ?? null,
    body: raw.content?.body ?? null,
    isEdited: raw.content?.isEdited ?? false,
    isSystem: raw.content?.isSystem ?? false,
    isPinned: raw.engagement?.isPinned ?? false,
    replyCount: raw.engagement?.replyCount ?? 0,
    reactionCount: raw.engagement?.reactionCount ?? 0,
    reactionSummary,
    myReactions,
    attachments,
    isBookmarked: false,
    forwardedFrom: null,
    isDeleted: false,
    createdAt: raw.audit?.createdAt ?? '',
    updatedAt: raw.audit?.updatedAt ?? '',
    rootId: raw.thread?.rootId ?? null,
    depth: raw.thread?.depth ?? 0,
    suggestBoardMigration: raw.thread?.suggestBoardMigration ?? false,
  }
}

/**
 * BE ネスト形式のメッセージリストレスポンスを FE フラット形式に変換する。
 */
function mapBeMessageList(raw: BeMessageListResponse, currentUserId?: number): ChatMessageListResponse {
  return {
    data: raw.data.map((msg) => mapBeMessage(msg, currentUserId)),
    meta: {
      nextCursor: raw.meta.nextCursor,
      // BE は hasNext / hasMore 両形式を使用するため両方を考慮
      hasMore: raw.meta.hasMore ?? raw.meta.hasNext ?? false,
    },
  }
}

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
  // composable 初期化時（setup コンテキスト内）に currentUserId を取得する
  const authStore = useAuthStore()
  const currentUserId = computed(() => authStore.user?.id)

  // === Messages ===
  async function getMessages(channelId: number, cursor?: string, limit?: number): Promise<ChatMessageListResponse> {
    const qs = buildQuery({ cursor, limit })
    const raw = await api<BeMessageListResponse>(`/api/v1/chat/channels/${channelId}/messages?${qs}`)
    return mapBeMessageList(raw, currentUserId.value)
  }

  async function sendMessage(
    channelId: number,
    body: string,
    parentId?: number,
    attachmentKeys?: string[],
  ): Promise<{ data: ChatMessageResponse }> {
    const raw = await api<{ data: BeMessageResponse }>(`/api/v1/chat/channels/${channelId}/messages`, {
      method: 'POST',
      body: { body, parentId, attachmentKeys },
    })
    return { data: mapBeMessage(raw.data, currentUserId.value) }
  }

  async function editMessage(messageId: number, body: string): Promise<{ data: ChatMessageResponse }> {
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
  async function searchMessages(channelId: number, q: string, cursor?: string): Promise<ChatMessageListResponse> {
    const qs = buildQuery({ q, cursor })
    const raw = await api<BeMessageListResponse>(`/api/v1/chat/channels/${channelId}/messages/search?${qs}`)
    return mapBeMessageList(raw, currentUserId.value)
  }

  // === Bookmark ===
  async function bookmarkMessage(messageId: number) {
    return api('/api/v1/chat/bookmarks', { method: 'POST', body: { messageId } })
  }

  async function removeBookmark(messageId: number) {
    return api(`/api/v1/chat/bookmarks/${messageId}`, { method: 'DELETE' })
  }

  async function getBookmarks(cursor?: string): Promise<ChatMessageListResponse> {
    const qs = buildQuery({ cursor })
    const raw = await api<BeMessageListResponse>(`/api/v1/chat/bookmarks?${qs}`)
    return mapBeMessageList(raw, currentUserId.value)
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
  async function forwardMessage(messageId: number, targetChannelId: number): Promise<{ data: ChatMessageResponse }> {
    const raw = await api<{ data: BeMessageResponse }>(`/api/v1/chat/messages/${messageId}/forward`, {
      method: 'POST',
      body: { targetChannelId },
    })
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
