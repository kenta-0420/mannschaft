import { useChatChannels } from './chat/useChatChannels'
import { useChatMessages } from './chat/useChatMessages'
import { useChatThreads } from './chat/useChatThreads'
import { useChatReactions } from './chat/useChatReactions'
import { useChatWebSocket } from './chat/useChatWebSocket'

/**
 * チャット機能の API ファサード composable。
 *
 * 内部はドメイン別の composable に分割されている:
 * - {@link useChatChannels}    — チャネル / メンバー / DM / チャネル設定
 * - {@link useChatMessages}    — メッセージ CRUD / ピン / 既読 / 検索 / ブックマーク / 添付 / 転送
 * - {@link useChatThreads}     — スレッド一覧 / 返信
 * - {@link useChatReactions}   — リアクション付与・解除
 * - {@link useChatWebSocket}   — STOMP 接続 / 購読 / タイピング / 再接続管理
 *
 * 呼び出し側との互換性のため、戻り値の公開関数群は分割前と完全一致させている。
 * 新規実装では、必要なドメインのみを直接 import することも推奨する。
 */
export function useChatApi() {
  const channels = useChatChannels()
  const messages = useChatMessages()
  const threads = useChatThreads()
  const reactions = useChatReactions()
  const ws = useChatWebSocket()

  return {
    // === Channels / Members / DM / Settings ===
    getChannels: channels.getChannels,
    getChannel: channels.getChannel,
    createChannel: channels.createChannel,
    updateChannel: channels.updateChannel,
    deleteChannel: channels.deleteChannel,
    archiveChannel: channels.archiveChannel,
    addMembers: channels.addMembers,
    removeMember: channels.removeMember,
    joinChannel: channels.joinChannel,
    changeMemberRole: channels.changeMemberRole,
    updateMySettings: channels.updateMySettings,
    getOrCreateDm: channels.getOrCreateDm,
    inviteToZimmer: channels.inviteToZimmer,
    updateChannelSettings: channels.updateChannelSettings,
    // === Messages ===
    getMessages: messages.getMessages,
    sendMessage: messages.sendMessage,
    editMessage: messages.editMessage,
    deleteMessage: messages.deleteMessage,
    migrateToBoard: messages.migrateToBoard,
    togglePin: messages.togglePin,
    markAsRead: messages.markAsRead,
    searchMessages: messages.searchMessages,
    bookmarkMessage: messages.bookmarkMessage,
    removeBookmark: messages.removeBookmark,
    getBookmarks: messages.getBookmarks,
    getUploadUrl: messages.getUploadUrl,
    getDownloadUrl: messages.getDownloadUrl,
    forwardMessage: messages.forwardMessage,
    getMessagesAfter: messages.getMessagesAfter,
    // === Threads ===
    getThread: threads.getThread,
    getActiveThreads: threads.getActiveThreads,
    // === Reactions ===
    addReaction: reactions.addReaction,
    removeReaction: reactions.removeReaction,
    // === WebSocket ===
    sendTyping: ws.sendTyping,
    subscribeChannel: ws.subscribeChannel,
    unsubscribeChannel: ws.unsubscribeChannel,
    subscribeChannelEvents: ws.subscribeChannelEvents,
    unsubscribeChannelEvents: ws.unsubscribeChannelEvents,
    wsConnectionFailed: ws.wsConnectionFailed,
  }
}
