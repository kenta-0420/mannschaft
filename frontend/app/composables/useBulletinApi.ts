import { useBulletinCategories } from './bulletin/useBulletinCategories'
import { useBulletinThreads } from './bulletin/useBulletinThreads'
import { useBulletinReplies } from './bulletin/useBulletinReplies'
import { useBulletinReactions } from './bulletin/useBulletinReactions'
import { useBulletinArchiveFolders } from './bulletin/useBulletinArchiveFolders'
import { useBulletinAttachments } from './bulletin/useBulletinAttachments'

/**
 * 掲示板 API ファサード。
 *
 * リファクタリング第10弾（2026-05-17）でドメイン別 4 ファイルに分割した。
 * F05.1 保管庫フォルダ機能で `useBulletinArchiveFolders` を追加（2026-05-26）。
 * 公開関数のシグネチャは分割前と完全互換。
 *
 * - `useBulletinCategories` — カテゴリ（グローバル / スコープ別）
 * - `useBulletinThreads` — スレッド + 既読状態（グローバル / スコープ別）
 * - `useBulletinReplies` — 返信（グローバル / スコープ別）
 * - `useBulletinReactions` — リアクション
 * - `useBulletinArchiveFolders` — 保管庫（アーカイブ）フォルダ CRUD・スレッド振り分け
 */
export function useBulletinApi() {
  const categories = useBulletinCategories()
  const threads = useBulletinThreads()
  const replies = useBulletinReplies()
  const reactions = useBulletinReactions()
  const archiveFolders = useBulletinArchiveFolders()
  const attachments = useBulletinAttachments()

  return {
    getCategories: categories.getCategories,
    createCategory: categories.createCategory,
    updateCategory: categories.updateCategory,
    deleteCategory: categories.deleteCategory,
    getThreads: threads.getThreads,
    getThread: threads.getThread,
    createThread: threads.createThread,
    updateThread: threads.updateThread,
    deleteThread: threads.deleteThread,
    changePriority: threads.changePriority,
    markRead: threads.markRead,
    getReaders: threads.getReaders,
    togglePin: threads.togglePin,
    toggleLock: threads.toggleLock,
    toggleArchive: threads.toggleArchive,
    readAll: threads.readAll,
    getReplies: replies.getReplies,
    createReply: replies.createReply,
    createNestedReply: replies.createNestedReply,
    updateReply: replies.updateReply,
    deleteReply: replies.deleteReply,
    getScopedCategories: categories.getScopedCategories,
    createScopedCategory: categories.createScopedCategory,
    getScopedCategory: categories.getScopedCategory,
    updateScopedCategory: categories.updateScopedCategory,
    deleteScopedCategory: categories.deleteScopedCategory,
    getScopedThreads: threads.getScopedThreads,
    searchScopedThreads: threads.searchScopedThreads,
    createScopedThread: threads.createScopedThread,
    getScopedThread: threads.getScopedThread,
    updateScopedThread: threads.updateScopedThread,
    deleteScopedThread: threads.deleteScopedThread,
    archiveScopedThread: threads.archiveScopedThread,
    lockScopedThread: threads.lockScopedThread,
    pinScopedThread: threads.pinScopedThread,
    getReadStatus: threads.getReadStatus,
    markReadStatus: threads.markReadStatus,
    getScopedReplies: replies.getScopedReplies,
    createScopedReply: replies.createScopedReply,
    updateScopedReply: replies.updateScopedReply,
    deleteScopedReply: replies.deleteScopedReply,
    getReactions: reactions.getReactions,
    addReaction: reactions.addReaction,
    removeReaction: reactions.removeReaction,
    getReactionSummary: reactions.getReactionSummary,
    // 保管庫（アーカイブ）フォルダ（F05.1）
    getArchiveFolderTree: archiveFolders.getFolderTree,
    createArchiveFolder: archiveFolders.createFolder,
    updateArchiveFolder: archiveFolders.updateFolder,
    deleteArchiveFolder: archiveFolders.deleteFolder,
    getArchiveThreads: archiveFolders.getArchiveThreads,
    moveThreadToFolder: archiveFolders.moveThreadToFolder,
    // 添付ファイル（F05.1 §6 presigned URL 方式 A）
    presignAttachment: attachments.presign,
    uploadAttachmentToR2: attachments.uploadToR2,
    confirmAttachment: attachments.confirm,
    listThreadAttachments: attachments.listThreadAttachments,
    listReplyAttachments: attachments.listReplyAttachments,
    getAttachmentDownloadUrl: attachments.getDownloadUrl,
    deleteAttachment: attachments.remove,
    uploadAttachmentFile: attachments.uploadFile,
  }
}
