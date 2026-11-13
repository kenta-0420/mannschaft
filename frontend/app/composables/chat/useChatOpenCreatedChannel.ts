import type { Ref } from 'vue'
import type { ChatChannelResponse } from '~/types/chat'
import { useChatCreatedChannel } from '~/composables/chat/useChatCreatedChannel'

/** 作成ダイアログから開く会話を選ぶために、一覧が公開する操作 */
interface OpenableChannelList {
  refresh: () => Promise<void> | void
  selectIfPresent: (channelId: number) => boolean
}

/**
 * 作成ダイアログ（ChatCreateDialog）が開始した DM・作成した Zimmer を、右ペインに開く（CMP-261105-1507）。
 *
 * 開始・作成の直後は一覧にまだ載っていないため、先に一覧を取り直してから選ぶ。
 * 取り直し中に別の開始・作成が走ったら、古い要求の結果は捨てる（今の操作と違う会話を開かない）。
 * 取り直しても一覧に無い場合（スコープ違い等）は、詳細 API で取り直して開く。失敗は通知される。
 *
 * @param list 一覧コンポーネントの ref
 * @param show 一覧の外で取得した会話を表示する処理（一覧にあれば一覧の select 経由で開くため呼ばれない）
 */
export function useChatOpenCreatedChannel(
  list: Ref<OpenableChannelList | null>,
  show: (channel: ChatChannelResponse) => void,
) {
  const { resolveCreatedChannel } = useChatCreatedChannel()
  let requestSeq = 0

  async function openCreatedChannel(channelId: number): Promise<void> {
    const seq = ++requestSeq
    await list.value?.refresh()
    if (seq !== requestSeq) return
    if (list.value?.selectIfPresent(channelId)) return
    const detail = await resolveCreatedChannel({ id: channelId })
    if (detail && seq === requestSeq) show(detail)
  }

  return { openCreatedChannel }
}
