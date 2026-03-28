import type { PromotionResponse, CouponResponse, SegmentPreset } from '~/types/promotion'

export function usePromotionApi() {
  const api = useApi()

  async function getPromotions(params?: Record<string, unknown>) {
    const q = new URLSearchParams(); if (params) for (const [k, v] of Object.entries(params)) { if (v != null) q.set(k, String(v)) }
    return api<{ data: PromotionResponse[] }>(`/api/v1/promotions?${q}`)
  }
  async function createPromotion(body: Record<string, unknown>) { return api<{ data: PromotionResponse }>('/api/v1/promotions', { method: 'POST', body }) }
  async function updatePromotion(id: number, body: Record<string, unknown>) { return api(`/api/v1/promotions/${id}`, { method: 'PUT', body }) }
  async function deletePromotion(id: number) { return api(`/api/v1/promotions/${id}`, { method: 'DELETE' }) }
  async function publishPromotion(id: number) { return api(`/api/v1/promotions/${id}/publish`, { method: 'PATCH' }) }
  async function cancelPromotion(id: number) { return api(`/api/v1/promotions/${id}/cancel`, { method: 'PATCH' }) }
  async function getCoupons(params?: Record<string, unknown>) {
    const q = new URLSearchParams(); if (params) for (const [k, v] of Object.entries(params)) { if (v != null) q.set(k, String(v)) }
    return api<{ data: CouponResponse[] }>(`/api/v1/coupons?${q}`)
  }
  async function createCoupon(body: Record<string, unknown>) { return api('/api/v1/coupons', { method: 'POST', body }) }
  async function redeemCoupon(id: number) { return api(`/api/v1/coupons/${id}/redeem`, { method: 'POST' }) }
  async function getSegmentPresets() { return api<{ data: SegmentPreset[] }>('/api/v1/segment-presets') }

  return { getPromotions, createPromotion, updatePromotion, deletePromotion, publishPromotion, cancelPromotion, getCoupons, createCoupon, redeemCoupon, getSegmentPresets }
}
