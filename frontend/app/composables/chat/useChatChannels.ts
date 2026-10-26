import type {
  ChatChannelListResponse,
  ChatChannelDetailResponse,
  ChatChannelResponse,
  CreateChannelRequest,
} from '~/types/chat'
import { buildQuery } from './chatQuery'

interface ChannelListParams {
  teamId?: number
  organizationId?: number
  channelType?: string
  isArchived?: boolean
  cursor?: string
  limit?: number
}

/**
 * チャットのチャネル / メンバー関連 API を提供する composable。
 *
 * 提供する関数:
 * - チャネル: getChannels / getChannel / createChannel / updateChannel / deleteChannel / archiveChannel
 * - メンバー: addMembers / removeMember / joinChannel / changeMemberRole / updateMySettings
 * - DM:       getOrCreateDm / inviteToZimmer
 * - 設定:     updateChannelSettings
 */
export function useChatChannels() {
  const api = useApi()

  // === Channels ===
  async function getChannels(params?: ChannelListParams) {
    const qs = buildQuery({
      team_id: params?.teamId,
      organization_id: params?.organizationId,
      channel_type: params?.channelType,
      is_archived: params?.isArchived,
      cursor: params?.cursor,
      limit: params?.limit,
    })
    return api<ChatChannelListResponse>(`/api/v1/chat/channels?${qs}`)
  }

  async function getChannel(channelId: number) {
    return api<ChatChannelDetailResponse>(`/api/v1/chat/channels/${channelId}`)
  }

  async function createChannel(body: CreateChannelRequest) {
    return api<{ data: ChatChannelResponse }>('/api/v1/chat/channels', {
      method: 'POST',
      body,
    })
  }

  async function updateChannel(channelId: number, body: Record<string, unknown>) {
    return api<{ data: ChatChannelResponse }>(`/api/v1/chat/channels/${channelId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteChannel(channelId: number) {
    return api(`/api/v1/chat/channels/${channelId}`, { method: 'DELETE' })
  }

  async function archiveChannel(channelId: number, archived: boolean) {
    return api(`/api/v1/chat/channels/${channelId}/archive`, {
      method: 'POST',
      body: { archived },
    })
  }

  // === Members ===
  async function addMembers(channelId: number, userIds: number[]) {
    return api(`/api/v1/chat/channels/${channelId}/members`, {
      method: 'POST',
      body: { userIds },
    })
  }

  async function removeMember(channelId: number, userId: number) {
    return api(`/api/v1/chat/channels/${channelId}/members/${userId}`, {
      method: 'DELETE',
    })
  }

  async function joinChannel(channelId: number) {
    return api(`/api/v1/chat/channels/${channelId}/join`, { method: 'POST' })
  }

  async function changeMemberRole(channelId: number, userId: number, role: string) {
    return api(`/api/v1/chat/channels/${channelId}/members/${userId}/role`, {
      method: 'PATCH',
      body: { role },
    })
  }

  async function updateMySettings(channelId: number, settings: Record<string, unknown>) {
    return api(`/api/v1/chat/channels/${channelId}/members/me`, {
      method: 'PATCH',
      body: settings,
    })
  }

  // === DM ===
  async function getOrCreateDm(userId: number) {
    return api<{ data: ChatChannelResponse }>('/api/v1/chat/channels/dm', {
      method: 'POST',
      body: { userId },
    })
  }

  async function inviteToZimmer(
    channelId: number,
    body: { userIds: number[]; shareHistory: boolean },
  ) {
    return api<{ data: ChatChannelResponse }>(
      `/api/v1/chat/channels/${channelId}/invite-to-zimmer`,
      {
        method: 'POST',
        body,
      },
    )
  }

  // === Channel Settings ===
  async function updateChannelSettings(
    channelId: number,
    settings: { isMuted?: boolean; isPinned?: boolean; category?: string },
  ) {
    return api(`/api/v1/chat/channels/${channelId}/settings`, {
      method: 'PATCH',
      body: settings,
    })
  }

  return {
    getChannels,
    getChannel,
    createChannel,
    updateChannel,
    deleteChannel,
    archiveChannel,
    addMembers,
    removeMember,
    joinChannel,
    changeMemberRole,
    updateMySettings,
    getOrCreateDm,
    inviteToZimmer,
    updateChannelSettings,
  }
}

export type { ChannelListParams }
