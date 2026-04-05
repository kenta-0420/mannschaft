import type {
  DiscountCampaignResponse,
  CreateCampaignRequest,
  UpdateCampaignRequest,
  CampaignCouponResponse,
  CreateCampaignCouponRequest,
  CouponUsageResponse,
} from '~/types/campaign'

const BASE = '/api/v1/system-admin/discount-campaigns'

export function useCampaignApi() {
  const api = useApi()

  async function getCampaigns(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: DiscountCampaignResponse[] }>(`${BASE}?${query}`)
  }

  async function getCampaign(id: number) {
    return api<{ data: DiscountCampaignResponse }>(`${BASE}/${id}`)
  }

  async function createCampaign(body: CreateCampaignRequest) {
    return api<{ data: DiscountCampaignResponse }>(`${BASE}`, { method: 'POST', body })
  }

  async function updateCampaign(id: number, body: UpdateCampaignRequest) {
    return api<{ data: DiscountCampaignResponse }>(`${BASE}/${id}`, { method: 'PUT', body })
  }

  async function deleteCampaign(id: number) {
    return api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  async function getCampaignCoupons(campaignId: number) {
    return api<{ data: CampaignCouponResponse[] }>(`${BASE}/${campaignId}/coupons`)
  }

  async function createCampaignCoupon(campaignId: number, body: CreateCampaignCouponRequest) {
    return api<{ data: CampaignCouponResponse }>(`${BASE}/${campaignId}/coupons`, {
      method: 'POST',
      body,
    })
  }

  async function getCouponUsage(campaignId: number, couponId: number) {
    return api<{ data: CouponUsageResponse[] }>(
      `${BASE}/${campaignId}/coupons/${couponId}/usage`,
    )
  }

  return {
    getCampaigns,
    getCampaign,
    createCampaign,
    updateCampaign,
    deleteCampaign,
    getCampaignCoupons,
    createCampaignCoupon,
    getCouponUsage,
  }
}
