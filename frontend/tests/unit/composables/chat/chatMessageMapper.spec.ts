import { describe, it, expect } from 'vitest'
import {
  mapBeMessage,
  mapBeMessageList,
  mapBeThread,
  aggregateReactions,
  type BeMessageResponse,
  type BeMessageListResponse,
  type BeThreadResponse,
  type BeReaction,
} from '~/composables/chat/chatMessageMapper'

/**
 * チャットメッセージ BE ネスト → FE フラット 単一マッパー UT。
 *
 * 観点:
 * - thread / content / engagement / audit のフラット展開
 * - sender ネスト優先 + senderId フォールバック
 * - reactions[] 集計（reactionSummary / myReactions）
 * - attachments の contentType → mimeType 変換・url 既定
 * - BE 未提供フィールドの既定値合成（isBookmarked / isDeleted / forwardedFrom）
 * - meta 欠落時の合成（search 経路）・hasNext / hasMore 両対応
 */

function baseRaw(overrides: Partial<BeMessageResponse> = {}): BeMessageResponse {
  return {
    id: 1,
    channelId: 10,
    senderId: 99,
    thread: { parentId: null, rootId: null, depth: 0, suggestBoardMigration: false },
    content: {
      body: 'hello',
      forwardedFromId: null,
      isEdited: false,
      isSystem: false,
      scheduledAt: null,
    },
    engagement: {
      replyCount: 2,
      reactionCount: 0,
      isPinned: true,
      attachments: [],
      reactions: [],
    },
    audit: { createdAt: '2026-06-30T00:00:00Z', updatedAt: '2026-06-30T01:00:00Z' },
    ...overrides,
  }
}

describe('mapBeMessage', () => {
  it('ネストを全フラット展開する', () => {
    const result = mapBeMessage(baseRaw())
    expect(result.id).toBe(1)
    expect(result.channelId).toBe(10)
    expect(result.body).toBe('hello')
    expect(result.isPinned).toBe(true)
    expect(result.replyCount).toBe(2)
    expect(result.parentId).toBeNull()
    expect(result.depth).toBe(0)
    expect(result.suggestBoardMigration).toBe(false)
    expect(result.createdAt).toBe('2026-06-30T00:00:00Z')
    expect(result.updatedAt).toBe('2026-06-30T01:00:00Z')
  })

  it('BE 未提供フィールドを既定値で合成する', () => {
    const result = mapBeMessage(baseRaw())
    expect(result.isBookmarked).toBe(false)
    expect(result.isDeleted).toBe(false)
    expect(result.forwardedFrom).toBeNull()
  })

  it('sender ネストを最優先で使う', () => {
    const result = mapBeMessage(
      baseRaw({ sender: { id: 99, displayName: '田中', avatarUrl: 'a.png' } }),
    )
    expect(result.sender).toEqual({ id: 99, displayName: '田中', avatarUrl: 'a.png' })
  })

  it('sender 不在時は senderId からフォールバック合成する（displayName 空文字）', () => {
    const result = mapBeMessage(baseRaw({ sender: undefined }))
    expect(result.sender).toEqual({ id: 99, displayName: '', avatarUrl: null })
  })

  it('senderDisplayName（WS フォールバック）を displayName に使う', () => {
    const result = mapBeMessage(baseRaw({ sender: undefined, senderDisplayName: '佐藤' }))
    expect(result.sender).toEqual({ id: 99, displayName: '佐藤', avatarUrl: null })
  })

  it('senderId が null なら sender は null', () => {
    const result = mapBeMessage(baseRaw({ senderId: null, sender: undefined }))
    expect(result.sender).toBeNull()
  })

  it('reactions[] を集計し myReactions を currentUserId で抽出する', () => {
    const reactions: BeReaction[] = [
      { id: 1, messageId: 1, userId: 7, emoji: '👍', createdAt: '' },
      { id: 2, messageId: 1, userId: 8, emoji: '👍', createdAt: '' },
      { id: 3, messageId: 1, userId: 7, emoji: '🎉', createdAt: '' },
    ]
    const result = mapBeMessage(
      baseRaw({
        engagement: {
          replyCount: 0,
          reactionCount: 3,
          isPinned: false,
          attachments: [],
          reactions,
        },
      }),
      7,
    )
    expect(result.reactionSummary).toEqual({ '👍': 2, '🎉': 1 })
    expect(result.myReactions.sort()).toEqual(['🎉', '👍'])
  })

  it('attachments の contentType を mimeType へ変換し url を既定空文字にする', () => {
    const result = mapBeMessage(
      baseRaw({
        engagement: {
          replyCount: 0,
          reactionCount: 0,
          isPinned: false,
          attachments: [
            {
              id: 5,
              messageId: 1,
              fileKey: 'k',
              fileName: 'f.png',
              fileSize: 123,
              contentType: 'image/png',
              createdAt: '',
            },
          ],
          reactions: [],
        },
      }),
    )
    expect(result.attachments).toEqual([
      { id: 5, fileName: 'f.png', fileKey: 'k', fileSize: 123, mimeType: 'image/png', url: '' },
    ])
  })

  it('ネストが null でもクラッシュせず既定値を返す', () => {
    const raw: BeMessageResponse = {
      id: 2,
      channelId: 10,
      senderId: null,
      thread: null,
      content: null,
      engagement: null,
      audit: null,
    }
    const result = mapBeMessage(raw)
    expect(result.body).toBeNull()
    expect(result.reactionSummary).toEqual({})
    expect(result.myReactions).toEqual([])
    expect(result.attachments).toEqual([])
    expect(result.createdAt).toBe('')
  })
})

describe('aggregateReactions', () => {
  it('currentUserId 未指定なら myReactions は空', () => {
    const { reactionSummary, myReactions } = aggregateReactions([
      { id: 1, messageId: 1, userId: 7, emoji: '👍', createdAt: '' },
    ])
    expect(reactionSummary).toEqual({ '👍': 1 })
    expect(myReactions).toEqual([])
  })
})

describe('mapBeMessageList', () => {
  it('hasNext を hasMore に正規化する', () => {
    const raw: BeMessageListResponse = {
      data: [baseRaw()],
      meta: { nextCursor: 'c1', hasNext: true },
    }
    const result = mapBeMessageList(raw)
    expect(result.data).toHaveLength(1)
    expect(result.meta.hasMore).toBe(true)
    expect(result.meta.nextCursor).toBe('c1')
  })

  it('meta 欠落（search 経路）で nextCursor=null / hasMore=false を合成する', () => {
    const raw: BeMessageListResponse = { data: [baseRaw()] }
    const result = mapBeMessageList(raw)
    expect(result.meta).toEqual({ nextCursor: null, hasMore: false })
  })
})

describe('mapBeThread', () => {
  it('root と messages の各要素をマップする', () => {
    const raw: BeThreadResponse = {
      root: baseRaw({ id: 1 }),
      messages: [baseRaw({ id: 2 }), baseRaw({ id: 3 })],
      totalCount: 2,
      nextCursor: null,
      hasNext: false,
    }
    const result = mapBeThread(raw)
    expect(result.root.id).toBe(1)
    expect(result.messages.map((m) => m.id)).toEqual([2, 3])
    // フラット型として reactionSummary が必ず存在する（描画クラッシュ防止）
    expect(result.root.reactionSummary).toEqual({})
    expect(result.messages[0]!.myReactions).toEqual([])
    expect(result.hasMore).toBe(false)
  })
})
