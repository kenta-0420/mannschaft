export interface WebhookEndpoint {
  id: number
  teamId: number
  url: string
  events: string[]
  secret: string
  isActive: boolean
  lastDeliveredAt: string | null
  failureCount: number
  createdAt: string
}

export interface WebhookLog {
  id: number
  endpointId: number
  event: string
  requestBody: string
  responseStatus: number | null
  responseBody: string | null
  deliveredAt: string
  success: boolean
}

export interface IncomingWebhook {
  id: number
  teamId: number
  name: string
  token: string
  targetChannel: string | null
  isActive: boolean
  createdAt: string
}

export interface ApiKeyResponse {
  id: number
  teamId: number
  name: string
  keyPrefix: string
  scopes: string[]
  expiresAt: string | null
  lastUsedAt: string | null
  isActive: boolean
  createdAt: string
}
