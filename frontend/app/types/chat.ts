export type ChatChannelType = 'TEAM' | 'ORGANIZATION' | 'CROSS_TEAM' | 'DIRECT'
export type ChatMemberRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface ChatUser {
  id: number
  displayName: string
  avatarUrl?: string | null
}

export interface ChatChannelResponse {
  id: number
  channelType: ChatChannelType
  team: { id: number; name: string } | null
  organization: { id: number; name: string } | null
  name: string | null
  iconUrl: string | null
  description: string | null
  isPrivate: boolean
  isArchived: boolean
  lastMessageAt: string | null
  lastMessagePreview: string | null
  unreadCount: number
  isMuted: boolean
  isPinned: boolean
  memberCount: number
  dmPartner: ChatUser | null
  sourceType: string | null
  sourceId: number | null
}

export interface ChatChannelDetailResponse {
  data: ChatChannelResponse & {
    createdBy: ChatUser | null
    members: ChatMember[]
    pinnedMessages: ChatMessageResponse[]
    sourceData: Record<string, unknown> | null
  }
}

export interface ChatMember {
  user: ChatUser
  role: ChatMemberRole
  joinedAt: string
}

export interface ChatMessageAttachment {
  id: number
  fileName: string
  fileKey: string
  fileSize: number
  mimeType: string
  url: string
}

export interface ChatMessageResponse {
  id: number
  channelId: number
  sender: ChatUser | null
  parentId: number | null
  body: string | null
  isEdited: boolean
  isSystem: boolean
  isPinned: boolean
  replyCount: number
  reactionCount: number
  reactionSummary: Record<string, number>
  myReactions: string[]
  attachments: ChatMessageAttachment[]
  isBookmarked: boolean
  forwardedFrom: {
    id: number
    body: string
    sender: ChatUser | null
    channelName: string | null
  } | null
  isDeleted: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateChannelRequest {
  channelType: ChatChannelType
  teamId?: number
  organizationId?: number
  name?: string
  description?: string
  isPrivate?: boolean
  memberIds?: number[]
}

export interface SendMessageRequest {
  body: string
  parentId?: number
  attachmentKeys?: string[]
}

export interface ChatChannelListResponse {
  data: ChatChannelResponse[]
  meta: {
    nextCursor: string | null
    hasMore: boolean
  }
}

export interface ChatMessageListResponse {
  data: ChatMessageResponse[]
  meta: {
    nextCursor: string | null
    hasMore: boolean
  }
}

/** チャットマルチタブUI — タブ1件分の状態 (F04.2.1) */
export interface ChatTab {
  /** UUID v4（重複タブ区別用タブ固有ID） */
  id: string
  /** チャンネルID */
  channelId: number
  /** 表示用スナップショット */
  channel: ChatChannelResponse
  /** 作成日時（ms） */
  createdAt: number
}
