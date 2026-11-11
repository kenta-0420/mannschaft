import { useEventBus } from '@vueuse/core'

/** チャンネルの内容変更（投稿・編集・削除）を一覧へ伝えるイベントバスのキー（payload はチャンネル ID）。 */
const CHAT_CHANNEL_CONTENT_CHANGED_EVENT = 'chat:channel-content-changed'

/**
 * 開いている会話で起きた投稿・編集・削除を、部屋一覧へ伝える composable（CMP-261105-1507）。
 *
 * 一覧は onMounted で一度しか取得しないため、編集・削除でサーバー側のプレビュー・未読数が変わっても
 * 通知しないと古いまま残る。購読者（ChatChannelList）は一覧を取り直す。
 */
export function useChatListSync() {
  const bus = useEventBus<number>(CHAT_CHANNEL_CONTENT_CHANGED_EVENT)

  function notifyChannelContentChanged(channelId: number): void {
    bus.emit(channelId)
  }

  /** 内容変更の通知を購読する。戻り値は購読解除関数。 */
  function onChannelContentChanged(handler: (channelId: number) => void): () => void {
    return bus.on(handler)
  }

  return { notifyChannelContentChanged, onChannelContentChanged }
}
