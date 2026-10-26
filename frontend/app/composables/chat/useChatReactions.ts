/**
 * チャットメッセージのリアクション API を提供する composable。
 *
 * 提供する関数:
 * - addReaction:    指定の絵文字でリアクション付与
 * - removeReaction: 指定の絵文字のリアクション解除
 */
export function useChatReactions() {
  const api = useApi()

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

  return {
    addReaction,
    removeReaction,
  }
}
