import { useEventBus } from '@vueuse/core'
import { useChatTabsStore } from '~/stores/useChatTabsStore'

/** 既読成功を一覧・タブへ伝えるイベントバスのキー（payload はチャンネル ID）。 */
const CHAT_CHANNEL_READ_EVENT = 'chat:channel-read'

/**
 * チャンネルの既読化と、その結果の画面内同期を担う composable（CMP-261105-1507）。
 *
 * 既読 API（`POST /chat/channels/{id}/read`）の完了を待ってから、
 * - チャンネル一覧（{@link onChannelRead} の購読者）へ既読になったチャンネル ID を通知し、
 * - 同じチャンネルを開いているタブの未読数を 0 にする。
 *
 * 失敗は例外のまま呼び出し側へ返す（握りつぶさない）。失敗時は既読扱いにしない。
 */
export function useChatReadSync() {
  const { markAsRead } = useChatApi()
  const tabsStore = useChatTabsStore()
  const bus = useEventBus<number>(CHAT_CHANNEL_READ_EVENT)

  async function markChannelRead(channelId: number): Promise<void> {
    await markAsRead(channelId)
    tabsStore.markChannelReadLocally(channelId)
    bus.emit(channelId)
  }

  /** 既読成功の通知を購読する。戻り値は購読解除関数。 */
  function onChannelRead(handler: (channelId: number) => void): () => void {
    return bus.on(handler)
  }

  return { markChannelRead, onChannelRead }
}
