import type {
  WebhookDelivery,
  IncomingWebhook,
  ApiKeyResponse,
  ApiKeyIssueResult,
  CreateEndpointBody,
  UpdateEndpointBody,
  CreateIncomingWebhookBody,
  IssueApiKeyBody,
} from '~/types/webhook'

export interface WebhookEndpointResponse {
  id: number
  scopeType: string
  scopeId: number
  name: string
  url: string
  isActive: boolean
  description: string | null
  timeoutMs: number | null
  eventTypes: string[]
  createdAt: string
}

export interface WebhookEndpointCreatedResponse extends WebhookEndpointResponse {
  signingSecret: string
}

export function useWebhookApi() {
  const api = useApi()

  // === Outgoing Webhook Endpoints ===

  async function getEndpoints(scopeType: string, scopeId: number) {
    return api<{ data: WebhookEndpointResponse[] }>(
      `/api/webhooks/endpoints?scopeType=${scopeType}&scopeId=${scopeId}`,
    )
  }

  async function createEndpoint(body: CreateEndpointBody) {
    return api<{ data: WebhookEndpointCreatedResponse }>('/api/webhooks/endpoints', {
      method: 'POST',
      body,
    })
  }

  async function getEndpoint(id: number) {
    return api<{ data: WebhookEndpointResponse }>(`/api/webhooks/endpoints/${id}`)
  }

  async function updateEndpoint(id: number, body: UpdateEndpointBody) {
    return api<{ data: WebhookEndpointResponse }>(`/api/webhooks/endpoints/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteEndpoint(id: number) {
    return api(`/api/webhooks/endpoints/${id}`, { method: 'DELETE' })
  }

  // === Delivery Logs ===

  async function getDeliveries(endpointId: number) {
    return api<{ data: WebhookDelivery[] }>(
      `/api/webhooks/endpoints/${endpointId}/deliveries`,
    )
  }

  async function retryDelivery(deliveryId: number) {
    return api(`/api/webhooks/deliveries/${deliveryId}/retry`, { method: 'POST' })
  }

  // === Incoming Webhooks ===

  async function getIncomingWebhooks(scopeType: string, scopeId: number) {
    return api<{ data: IncomingWebhook[] }>(
      `/api/webhooks/incoming?scopeType=${scopeType}&scopeId=${scopeId}`,
    )
  }

  async function createIncomingWebhook(body: CreateIncomingWebhookBody) {
    return api<{ data: IncomingWebhook }>('/api/webhooks/incoming', {
      method: 'POST',
      body,
    })
  }

  async function deleteIncomingWebhook(id: number) {
    return api(`/api/webhooks/incoming/${id}`, { method: 'DELETE' })
  }

  // === API Keys ===

  async function getApiKeys(scopeType: string, scopeId: number) {
    return api<{ data: ApiKeyResponse[] }>(
      `/api/api-keys?scopeType=${scopeType}&scopeId=${scopeId}`,
    )
  }

  async function issueApiKey(body: IssueApiKeyBody) {
    return api<ApiKeyIssueResult>('/api/api-keys', {
      method: 'POST',
      body,
    })
  }

  async function deleteApiKey(id: number) {
    return api(`/api/api-keys/${id}`, { method: 'DELETE' })
  }

  return {
    getEndpoints,
    createEndpoint,
    getEndpoint,
    updateEndpoint,
    deleteEndpoint,
    getDeliveries,
    retryDelivery,
    getIncomingWebhooks,
    createIncomingWebhook,
    deleteIncomingWebhook,
    getApiKeys,
    issueApiKey,
    deleteApiKey,
  }
}
