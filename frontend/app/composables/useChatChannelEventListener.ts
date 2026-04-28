/**
 * F04.2.1 Phase10 — チャンネルイベントリスナー composable
 *
 * /topic/channels/{channelId}/events を購読し、受信したイベントに応じて
 * 該当チャンネルのタブを自動クローズする。
 *
 * - MEMBER_KICKED: 自分自身が kick された場合のみタブを閉じる（他メンバーの kick は無視）
 * - CHANNEL_DELETED: 必ずタブを閉じる
 * - CHANNEL_ARCHIVED: 必ずタブを閉じる
 * - CHANNEL_UNARCHIVED: 何もしない（BE 未配信、将来対応用）
 *
 * 起動箇所は ChatMessagePanel.vue（タブ生成・破棄に同期）。
 */
import type { ChatChannelEvent } from '~/types/chat'

export function useChatChannelEventListener(channelId: number): void {
  const { subscribeChannelEvents, unsubscribeChannelEvents } = useChatApi()
  const tabsStore = useChatTabsStore()
  const authStore = useAuthStore()
  const { t } = useI18n()
  const { info } = useNotification()

  const bus = useEventBus<{ channelId: number; event: ChatChannelEvent }>('chat:channel:event')

  const unsubBus = bus.on(({ channelId: cid, event }) => {
    if (cid !== channelId) return

    let messageKey: string | null = null
    let shouldClose = false

    switch (event.type) {
      case 'MEMBER_KICKED':
        // 自分自身が kick された場合のみタブを閉じる
        if (event.userId === authStore.user?.id) {
          messageKey = 'chat.tab.closed_kicked'
          shouldClose = true
        }
        break
      case 'CHANNEL_DELETED':
        messageKey = 'chat.tab.closed_deleted'
        shouldClose = true
        break
      case 'CHANNEL_ARCHIVED':
        messageKey = 'chat.tab.closed_archived'
        shouldClose = true
        break
      case 'CHANNEL_UNARCHIVED':
        // unarchive はタブを閉じない（BE 側未配信だが将来対応）
        return
    }

    if (shouldClose) {
      tabsStore.closeTabsByChannelId(channelId)
      if (messageKey !== null) {
        info(t(messageKey))
      }
    }
  })

  onMounted(() => subscribeChannelEvents(channelId))
  onUnmounted(() => {
    unsubscribeChannelEvents(channelId)
    unsubBus()
  })
}
