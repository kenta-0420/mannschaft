import type { BulletinReactionSummary } from '~/types/bulletin'

/**
 * 掲示板リアクション関連 API（スレッド・返信両対応）。
 *
 * - 一覧: `/api/v1/bulletin/reactions?targetType=&targetId=`
 * - サマリ: `/api/v1/bulletin/reactions/summary`
 */
export function useBulletinReactions() {
  const api = useApi()

  async function getReactions(targetType: string, targetId: number) {
    return api<{ data: unknown[] }>(
      `/api/v1/bulletin/reactions?targetType=${targetType}&targetId=${targetId}`,
    )
  }

  async function addReaction(targetType: 'thread' | 'reply', targetId: number, emoji: string) {
    return api(`/api/v1/bulletin/reactions`, {
      method: 'POST',
      body: { targetType, targetId, emoji },
    })
  }

  async function removeReaction(targetType: 'thread' | 'reply', targetId: number, emoji: string) {
    return api(
      `/api/v1/bulletin/reactions?targetType=${targetType}&targetId=${targetId}&emoji=${encodeURIComponent(emoji)}`,
      {
        method: 'DELETE',
      },
    )
  }

  async function getReactionSummary(targetType: string, targetId: number) {
    return api<{ data: BulletinReactionSummary }>(
      `/api/v1/bulletin/reactions/summary?targetType=${targetType}&targetId=${targetId}`,
    )
  }

  return {
    getReactions,
    addReaction,
    removeReaction,
    getReactionSummary,
  }
}
