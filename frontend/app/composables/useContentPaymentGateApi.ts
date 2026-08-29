import type { ContentGateType, ContentPaymentGateResponse } from '~/types/payment'
import type { components } from '~/types/generated/index'

type GeneratedContentPaymentGateRequest = components['schemas']['ContentPaymentGateRequest']

export type ContentPaymentGateRequest = Omit<
  GeneratedContentPaymentGateRequest,
  'contentType' | 'gates'
> & {
  contentType: ContentGateType
  contentId: number
  gates: Array<{ paymentItemId: number; isTitleHidden: boolean }>
}

export function useContentPaymentGateApi() {
  const api = useApi()

  function base(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}/content-payment-gates`
      : `/api/v1/organizations/${scopeId}/content-payment-gates`
  }

  async function getContentPaymentGates(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{
      data: ContentPaymentGateResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(base(scopeType, scopeId))
  }

  async function updateContentPaymentGates(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: ContentPaymentGateRequest,
  ) {
    return api(base(scopeType, scopeId), { method: 'PUT', body })
  }

  return { getContentPaymentGates, updateContentPaymentGates }
}
