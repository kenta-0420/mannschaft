export type BulletinScopeType = 'TEAM' | 'ORGANIZATION'
export type BulletinPriority = 'CRITICAL' | 'IMPORTANT' | 'WARNING' | 'INFO' | 'LOW'
export type ReadTrackingMode = 'NONE' | 'COUNT_ONLY' | 'SHOW_READERS'

export interface BulletinCategory {
  id: number
  scopeType: BulletinScopeType
  scopeId: number
  name: string
  description: string | null
  displayOrder: number
  color: string | null
  postMinRole: string
}

export interface BulletinThreadResponse {
  id: number
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  scopeType: BulletinScopeType
  scopeId: number
  author: { id: number; displayName: string; avatarUrl: string | null }
  title: string
  body: string
  priority: BulletinPriority
  readTrackingMode: ReadTrackingMode
  isPinned: boolean
  isLocked: boolean
  isArchived: boolean
  replyCount: number
  readCount: number
  isRead: boolean
  reactionSummary: Record<string, number>
  myReactions: string[]
  lastRepliedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface BulletinReplyResponse {
  id: number
  threadId: number
  parentReplyId: number | null
  author: { id: number; displayName: string; avatarUrl: string | null }
  body: string
  depth: number
  reactionSummary: Record<string, number>
  myReactions: string[]
  children: BulletinReplyResponse[]
  createdAt: string
  updatedAt: string
}

export interface CreateBulletinThreadRequest {
  categoryId?: number
  title: string
  body: string
  priority?: BulletinPriority
  readTrackingMode?: ReadTrackingMode
}

export interface CreateBulletinReplyRequest {
  body: string
}

export interface BulletinReader {
  userId: number
  displayName: string
  avatarUrl: string | null
  readAt: string
}

export interface BulletinReadStatus {
  threadId: number
  isRead: boolean
  readAt: string | null
  totalReaders: number
  readCount: number
}

export interface BulletinReactionSummary {
  targetType: string
  targetId: number
  reactions: Record<string, number>
  myReactions: string[]
}

export interface CreateBulletinReactionRequest {
  targetType: string
  targetId: number
  emoji: string
}

export interface BulletinThreadSearchParams {
  keyword: string
  page?: number
  size?: number
}
