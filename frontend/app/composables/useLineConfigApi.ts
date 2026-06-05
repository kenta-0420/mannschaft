export interface LineBotConfigResponse {
  id: number
  scopeType: string
  scopeId: string
  channelId: string
  webhookSecret: string | null
  botUserId: string | null
  isActive: boolean
  notificationEnabled: boolean
  configuredBy: number
  createdAt: string
  updatedAt: string
}

export interface CreateLineBotConfigRequest {
  channelId?: string
  channelSecret: string
  channelAccessToken: string
  webhookSecret?: string
  botUserId?: string
  notificationEnabled?: boolean
}

export interface UpdateLineBotConfigRequest {
  channelId?: string
  channelSecret?: string
  channelAccessToken?: string
  webhookSecret?: string
  botUserId?: string
  notificationEnabled?: boolean
}

export function useLineConfigApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}/line/config`
      : `/api/v1/organizations/${scopeId}/line/config`
  }

  async function getConfig(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: LineBotConfigResponse }>(buildBase(scopeType, scopeId))
  }

  async function createConfig(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: CreateLineBotConfigRequest,
  ) {
    return api<{ data: LineBotConfigResponse }>(buildBase(scopeType, scopeId), {
      method: 'POST',
      body,
    })
  }

  async function updateConfig(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: UpdateLineBotConfigRequest,
  ) {
    return api<{ data: LineBotConfigResponse }>(buildBase(scopeType, scopeId), {
      method: 'PUT',
      body,
    })
  }

  async function deleteConfig(scopeType: 'team' | 'organization', scopeId: string) {
    return api(buildBase(scopeType, scopeId), { method: 'DELETE' })
  }

  return {
    getConfig,
    createConfig,
    updateConfig,
    deleteConfig,
  }
}
