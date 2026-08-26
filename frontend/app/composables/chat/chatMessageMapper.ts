import type {
  ChatMessageListResponse,
  ChatMessageResponse,
  ChatMessageAttachment,
  ChatThreadResponse,
  ChatUser,
} from '~/types/chat'

// ============================================================================
// BE ネスト形式の内部型（API 実形状）
// ----------------------------------------------------------------------------
// BE `MessageResponse` はネスト設計（thread / content / engagement / audit）で
// JSON を返す。FE 公開型 {@link ChatMessageResponse} はフラット設計のため、
// REST / WebSocket の全取得経路で本モジュールの {@link mapBeMessage} を唯一の
// 通過点として変換する（契約ドリフトの単一根治点）。
//
// 生成型（types/generated）はチャットメッセージについて名前衝突で空スタブのため
// 使用不可。BE 形状はここで手書き interface として明示定義する。
// ============================================================================

/** BE engagement.reactions[] の生要素（集計形ではない） */
export interface BeReaction {
  id: number
  messageId: number
  userId: number
  emoji: string
  createdAt: string
}

/** BE engagement.attachments[] の生要素（contentType・url 無し） */
export interface BeAttachment {
  id: number
  messageId: number
  fileKey: string
  fileName: string
  fileSize: number
  contentType: string
  createdAt: string
}

/** BE sender ネスト（並行 BE PR で新設中・無ければ senderId フォールバック） */
export interface BeSender {
  id: number
  displayName: string
  avatarUrl: string | null
}

/** BE `MessageResponse`（ネスト形状） */
export interface BeMessageResponse {
  id: number
  channelId: number
  senderId: number | null
  /** 並行 BE PR が付与する送信者ネスト。無い間は senderId からフォールバック合成する */
  sender?: BeSender | null
  /** WebSocket ブロードキャストでのみ届く可能性のある送信者表示名（後方互換フォールバック） */
  senderDisplayName?: string | null
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
}

/** BE `CursorPagedResponse` の meta（hasNext / hasMore 両形式に対応） */
export interface BeCursorMeta {
  nextCursor: string | null
  /** BE は CursorPagedResponse で hasNext を返す。FE 互換のため hasMore も両対応 */
  hasNext?: boolean
  hasMore?: boolean
  limit?: number
}

/** BE メッセージ一覧レスポンス（meta は search 等で欠落しうるため optional） */
export interface BeMessageListResponse {
  data: BeMessageResponse[]
  meta?: BeCursorMeta
}

/** BE スレッド取得レスポンス（root / messages は生ネスト形状） */
export interface BeThreadResponse {
  root: BeMessageResponse
  messages: BeMessageResponse[]
  totalCount: number
  nextCursor: string | null
  hasNext?: boolean
  hasMore?: boolean
}

/** BE `BookmarkResponse`（ブックマーク自体・メッセージ本体ではない） */
export interface BeBookmarkResponse {
  id: number
  messageId: number
  userId: number
  note: string | null
  createdAt: string
}

// ============================================================================
// 集計ヘルパー
// ============================================================================

/** リアクション集計結果（FE フラット形状の reactionSummary / myReactions） */
export interface ReactionAggregate {
  reactionSummary: Record<string, number>
  myReactions: string[]
}

/**
 * 生の reactions[] を絵文字別集計（reactionSummary）と自分のリアクション
 * 抽出（myReactions）に変換する。
 *
 * REST の {@link mapBeMessage} と WebSocket の REACTION_UPDATED 反映で共用する
 * （集計ロジックを一箇所に集約し、経路差によるドリフトを防ぐ）。
 *
 * @param reactions     BE engagement.reactions[]（生）
 * @param currentUserId 認証中ユーザー ID（myReactions 抽出用・未認証なら空配列）
 */
export function aggregateReactions(
  reactions: BeReaction[],
  currentUserId?: number,
): ReactionAggregate {
  const reactionSummary: Record<string, number> = {}
  for (const r of reactions) {
    reactionSummary[r.emoji] = (reactionSummary[r.emoji] ?? 0) + 1
  }

  const myReactions: string[] = currentUserId != null
    ? reactions.filter((r) => r.userId === currentUserId).map((r) => r.emoji)
    : []

  return { reactionSummary, myReactions }
}

// ============================================================================
// メッセージマッパー（全取得経路の唯一の通過点）
// ============================================================================

/**
 * BE ネスト形式の `MessageResponse` を FE フラット形式の {@link ChatMessageResponse}
 * に変換する。REST / WebSocket（MESSAGE_CREATED / UPDATED）の全経路でこの関数を通す。
 *
 * 変換方針:
 * - thread.* / content.* / engagement.* / audit.* → フラット展開
 * - sender = raw.sender（並行 BE PR）。無ければ senderId からフォールバック合成
 *   （displayName は senderDisplayName か空文字、avatarUrl は null）
 * - reactionSummary / myReactions = reactions[] を {@link aggregateReactions} で集計
 * - attachments = contentType → mimeType 変換・url は既定 ''（presigned は別取得）
 * - isBookmarked / isDeleted / forwardedFrom = BE 未提供のため既定値で合成
 *
 * @param raw           BE ネスト形式のメッセージ
 * @param currentUserId 認証中ユーザー ID（myReactions 抽出用）
 */
export function mapBeMessage(
  raw: BeMessageResponse,
  currentUserId?: number,
): ChatMessageResponse {
  const reactions = raw.engagement?.reactions ?? []
  const { reactionSummary, myReactions } = aggregateReactions(reactions, currentUserId)

  const attachments: ChatMessageAttachment[] = (raw.engagement?.attachments ?? []).map(
    (att) => ({
      id: att.id,
      fileName: att.fileName,
      fileKey: att.fileKey,
      fileSize: att.fileSize,
      // BE contentType → FE mimeType に変換
      mimeType: att.contentType ?? '',
      // 個別の presigned URL は getDownloadUrl で別途取得する
      url: '',
    }),
  )

  // 送信者解決: 並行 BE PR の sender ネストを最優先。無ければ senderId フォールバック。
  const senderId = raw.sender?.id ?? raw.senderId ?? null
  const sender: ChatUser | null = senderId != null
    ? {
        id: senderId,
        displayName: raw.sender?.displayName ?? raw.senderDisplayName ?? '',
        avatarUrl: raw.sender?.avatarUrl ?? null,
      }
    : null

  return {
    id: raw.id,
    channelId: raw.channelId,
    sender,
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
    // BE 未提供フィールドは既定値で合成
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
 * BE メッセージ一覧レスポンスを FE フラット形式に変換する。
 *
 * meta は search 等の `ApiResponse<List<...>>` 経路で欠落しうるため、欠落時は
 * nextCursor=null / hasMore=false を合成する。hasNext / hasMore 両形式に対応。
 */
export function mapBeMessageList(
  raw: BeMessageListResponse,
  currentUserId?: number,
): ChatMessageListResponse {
  return {
    data: (raw.data ?? []).map((msg) => mapBeMessage(msg, currentUserId)),
    meta: {
      nextCursor: raw.meta?.nextCursor ?? null,
      hasMore: raw.meta?.hasMore ?? raw.meta?.hasNext ?? false,
    },
  }
}

/**
 * BE スレッド取得レスポンスを FE フラット形式に変換する。
 * root と messages の各要素を {@link mapBeMessage} で変換する。
 */
export function mapBeThread(
  raw: BeThreadResponse,
  currentUserId?: number,
): ChatThreadResponse {
  return {
    root: mapBeMessage(raw.root, currentUserId),
    messages: (raw.messages ?? []).map((msg) => mapBeMessage(msg, currentUserId)),
    totalCount: raw.totalCount,
    nextCursor: raw.nextCursor,
    hasMore: raw.hasMore ?? raw.hasNext ?? false,
  }
}
