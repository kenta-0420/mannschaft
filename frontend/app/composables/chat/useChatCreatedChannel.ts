import type { ChatChannelResponse } from '~/types/chat'

/**
 * 作成系 API（Zimmer 招待・会話開始・チャンネル作成など）の応答を、表示中のチャンネルにする前に
 * 詳細 API（`GET /chat/channels/{id}`）で取り直す（CMP-261105-1507）。
 *
 * BE の作成系の応答は呼出ユーザーごとの拡張（viewer・memberCount・dmPartner）を持たない。
 * そのまま表示中のチャンネルにすると、作成者（OWNER）なのに viewer.role が無く、
 * 他人の投稿の削除などの操作が出ない。詳細 API は呼出ユーザーのメンバー行を含めて返す。
 *
 * 取り直しに失敗したら通知し、null を返す（呼び出し側は表示中のチャンネルを差し替えない）。
 */
export function useChatCreatedChannel() {
  const { getChannel } = useChatApi()
  const { showError } = useNotification()
  const { t } = useI18n()

  async function resolveCreatedChannel(created: Pick<ChatChannelResponse, 'id'>): Promise<ChatChannelResponse | null> {
    try {
      const res = await getChannel(created.id)
      return res.data
    } catch {
      showError(t('chat.channelList.detailLoadFailed'))
      return null
    }
  }

  return { resolveCreatedChannel }
}
