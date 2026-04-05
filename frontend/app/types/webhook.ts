export interface WebhookEndpoint {
  id: number
  teamId: number
  name: string
  url: string
  description: string | null
  timeoutMs: number
  events: string[]
  eventTypes: string[]
  secret: string
  isActive: boolean
  lastDeliveredAt: string | null
  failureCount: number
  createdAt: string
}

export interface WebhookDelivery {
  id: number
  endpointId: number
  event: string
  requestBody: string
  responseStatus: number | null
  responseBody: string | null
  deliveredAt: string
  success: boolean
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
  description: string | null
  token: string
  allowedIps: string[]
  targetChannel: string | null
  isActive: boolean
  createdAt: string
}

export interface ApiKeyResponse {
  id: number
  teamId: number
  name: string
  description: string | null
  keyPrefix: string
  scopes: string[]
  permissions: string[]
  expiresAt: string | null
  lastUsedAt: string | null
  isActive: boolean
  createdAt: string
}

export interface ApiKeyIssueResult {
  apiKey: ApiKeyResponse
  fullKey: string
}

export interface CreateEndpointBody {
  scopeType: string
  scopeId: number
  name: string
  url: string
  description?: string
  timeoutMs?: number
  eventTypes: string[]
}

export interface UpdateEndpointBody {
  name?: string
  url?: string
  description?: string
  timeoutMs?: number
  isActive?: boolean
  eventTypes?: string[]
}

export interface CreateIncomingWebhookBody {
  scopeType: string
  scopeId: number
  name: string
  description?: string
  allowedIps?: string[]
}

export interface IssueApiKeyBody {
  scopeType: string
  scopeId: number
  name: string
  description?: string
  permissions: string[]
  expiresAt?: string
}
