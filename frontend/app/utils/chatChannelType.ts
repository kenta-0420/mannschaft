import type { ChatChannelType } from '~/types/chat'

/**
 * メンバー行（chat_channel_members）でアクセスを判定する種別か（BE `ChannelType#isMembershipGated` と一致させる）。
 *
 * これらの種別は、本文を読めた時点で利用者がチャンネルのメンバーである（BE の閲覧認可がメンバー行を要求する）。
 * 村ロビー・イベント・大会チャットはメンバー行を持たない横断スペースで、既読 API の対象外である。
 */
const MEMBERSHIP_GATED_TYPES: ReadonlySet<ChatChannelType> = new Set<ChatChannelType>([
  'DM',
  'GROUP_DM',
  'TEAM_PUBLIC',
  'TEAM_PRIVATE',
  'ORG_PUBLIC',
  'ORG_PRIVATE',
])

export function isMembershipGatedChannelType(type: ChatChannelType | null | undefined): boolean {
  return type != null && MEMBERSHIP_GATED_TYPES.has(type)
}
