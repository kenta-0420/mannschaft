import type {
  ChatChannelListResponse,
  ChatChannelDetailResponse,
  ChatMessageListResponse,
  ChatMessageResponse,
  ChatChannelResponse,
  CreateChannelRequest,
} from '~/types/chat'

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

  async function getThread(messageId: number, cursor?: string) {
    const qs = buildQuery({ cursor })
    return api<ChatMessageListResponse>(`/api/v1/chat/messages/${messageId}/thread?${qs}`)
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
  }
}
