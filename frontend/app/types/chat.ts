/**
 * チャットチャンネルの種別。
 *
 * BE {@code com.mannschaft.app.chat.ChannelType} と一致させること。
 * - DM: 1対1ダイレクトメッセージ
 * - GROUP_DM: 複数人グループDM（Zimmer）
 * - TEAM_PUBLIC / TEAM_PRIVATE: チーム公開 / 非公開チャンネル
 * - ORG_PUBLIC / ORG_PRIVATE: 組織公開 / 非公開チャンネル
 * - VILLAGE_LOBBY: 村ロビー（井戸端会議）
 * - EVENT_CHAT: イベント専用チャット
 * - TOURNAMENT_CHAT / TOURNAMENT_DIVISION_CHAT: 大会 / ディビジョン連絡チャット
 */
export type ChatChannelType =
  | 'DM'
  | 'GROUP_DM'
  | 'TEAM_PUBLIC'
  | 'TEAM_PRIVATE'
  | 'ORG_PUBLIC'
  | 'ORG_PRIVATE'
  | 'VILLAGE_LOBBY'
  | 'EVENT_CHAT'
  | 'TOURNAMENT_CHAT'
  | 'TOURNAMENT_DIVISION_CHAT'

export type ChatMemberRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface ChatUser {
  id: number
  displayName: string
  avatarUrl?: string | null
}

/** チャンネルの所属・種別情報（BE ChannelIdentityDto） */
export interface ChatChannelIdentity {
  channelType: ChatChannelType
  teamId: number | null
  organizationId: number | null
}

/** チャンネルの表示メタ情報（BE ChannelMetaDto） */
export interface ChatChannelMeta {
  name: string | null
  iconKey: string | null
  description: string | null
}

/** チャンネル設定（BE ChannelSettingsDto） */
export interface ChatChannelSettings {
  isPrivate: boolean
  isInquiryChannel: boolean
  isArchived: boolean
  version: number | null
}

/** 最新メッセージサマリ（BE ChannelLastMessageDto） */
export interface ChatChannelLastMessage {
  lastMessageAt: string | null
  lastMessagePreview: string | null
}

/** チャンネルの紐付け元（BE ChannelSourceDto） */
export interface ChatChannelSource {
  sourceType: string | null
  sourceId: number | null
}

/** 監査情報（BE ChannelAuditDto） */
export interface ChatChannelAudit {
  createdBy: number | null
  createdAt: string | null
  updatedAt: string | null
}

/** DM 相手の情報（BE DmPartnerDto）。DM チャンネル以外では null */
export interface ChatDmPartner {
  userId: number
  displayName: string
  avatarUrl: string | null
}

/** 閲覧者ごとのチャンネル状態（BE ViewerStateDto）。非メンバー閲覧時は null */
export interface ChatViewerState {
  unreadCount: number
  isMuted: boolean
  isPinned: boolean
  category: string | null
  role: string | null
}

/**
 * チャンネルレスポンス（BE ChannelResponse のネスト正準形）。
 *
 * BE が identity / meta / settings / lastMessage / source / audit のネスト構造で返すため、
 * フラットなフィールド（旧 name / channelType / isPrivate 等）は存在しない。
 * 表示名は DM なら {@code dmPartner.displayName}、それ以外は {@code meta.name} を使う。
 */
export interface ChatChannelResponse {
  id: number
  identity: ChatChannelIdentity
  meta: ChatChannelMeta
  settings: ChatChannelSettings
  lastMessage: ChatChannelLastMessage | null
  source: ChatChannelSource | null
  audit: ChatChannelAudit
  memberCount: number
  dmPartner: ChatDmPartner | null
  viewer: ChatViewerState | null
}

/**
 * チャンネル詳細レスポンス。
 *
 * BE は素の {@link ChatChannelResponse}（ネスト）を {@code data} で返す。
 * 旧版の members / pinnedMessages / sourceData は BE 未提供のため持たない
 * （作成者は {@code data.audit.createdBy} を参照すること）。
 */
export interface ChatChannelDetailResponse {
  data: ChatChannelResponse
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
  /** スレッドルートメッセージID（null = 自身がルート） */
  rootId: number | null
  /** ネスト深度（0 = トップレベル） */
  depth: number
  /** depth >= 10 時に true（掲示板移行推奨） */
  suggestBoardMigration: boolean
}

/** スレッド取得レスポンス (F04.2) */
export interface ChatThreadResponse {
  root: ChatMessageResponse
  messages: ChatMessageResponse[]
  totalCount: number
  nextCursor: string | null
  hasMore: boolean
}

/** アクティブスレッド一覧アイテム (F04.2) */
export interface ChatActiveThreadItem {
  id: number
  body: string
  replyCount: number
  lastReplyAt: string | null
  lastReplyPreview: string | null
  createdAt: string
}

export interface CreateChannelRequest {
  channelType: ChatChannelType
  teamId?: string
  organizationId?: string
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

/**
 * チャンネル一覧レスポンス。
 *
 * BE 実形状は {@code { data: ChannelResponse[] }} のみ（ページング meta は返らない）。
 */
export interface ChatChannelListResponse {
  data: ChatChannelResponse[]
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

/**
 * チャンネルイベントペイロード（F04.2.1 Phase10）
 *
 * BE 側の {@code ChatChannelEventPayload} record に対応。
 * /topic/channels/{channelId}/events を通じて配信される。
 *
 * - MEMBER_KICKED: 特定メンバーが kick されたとき。userId 必須
 * - CHANNEL_DELETED: チャンネルが削除されたとき
 * - CHANNEL_ARCHIVED: チャンネルがアーカイブされたとき
 * - CHANNEL_UNARCHIVED: チャンネルがアーカイブ解除されたとき（BE 未配信、将来対応用）
 */
export type ChatChannelEvent =
  | { type: 'MEMBER_KICKED'; userId: number }
  | { type: 'CHANNEL_DELETED' }
  | { type: 'CHANNEL_ARCHIVED' }
  | { type: 'CHANNEL_UNARCHIVED' }
