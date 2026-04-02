import type { PromotionResponse, CouponResponse, SegmentPreset } from '~/types/promotion'

export function usePromotionApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  function buildQuery(params?: Record<string, unknown>): string {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    return q.toString()
  }

  // === Promotions ===
  async function getPromotions(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: Record<string, unknown>,
  ) {
    const qs = buildQuery(params)
    return api<{ data: PromotionResponse[] }>(`${buildBase(scopeType, scopeId)}/promotions?${qs}`)
  }

  async function getPromotion(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api<{ data: PromotionResponse }>(`${buildBase(scopeType, scopeId)}/promotions/${id}`)
  }

  async function createPromotion(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: PromotionResponse }>(`${buildBase(scopeType, scopeId)}/promotions`, {
      method: 'POST',
      body,
    })
  }

  async function updatePromotion(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/promotions/${id}`, { method: 'PUT', body })
  }

  async function deletePromotion(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/promotions/${id}`, { method: 'DELETE' })
  }

  async function approvePromotion(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/promotions/${id}/approve`, { method: 'POST' })
  }

  async function publishPromotion(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/promotions/${id}/publish`, { method: 'POST' })
  }

  async function cancelPromotion(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/promotions/${id}/cancel`, { method: 'POST' })
  }

  async function schedulePromotion(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/promotions/${id}/schedule`, {
      method: 'POST',
      body,
    })
  }

  async function getPromotionStats(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/promotions/${id}/stats`)
  }

  async function estimateAudience(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/promotions/estimate-audience`, {
      method: 'POST',
      body,
    })
  }

  // === Coupons ===
  async function getCoupons(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: Record<string, unknown>,
  ) {
    const qs = buildQuery(params)
    return api<{ data: CouponResponse[] }>(`${buildBase(scopeType, scopeId)}/coupons?${qs}`)
  }

  async function getCoupon(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api<{ data: CouponResponse }>(`${buildBase(scopeType, scopeId)}/coupons/${id}`)
  }

  async function createCoupon(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/coupons`, { method: 'POST', body })
  }

  async function updateCoupon(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/coupons/${id}`, { method: 'PUT', body })
  }

  async function deleteCoupon(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/coupons/${id}`, { method: 'DELETE' })
  }

  async function toggleCoupon(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/coupons/${id}/toggle`, { method: 'PATCH' })
  }

  // === Segment Presets ===
  async function getSegmentPresets(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: SegmentPreset[] }>(`${buildBase(scopeType, scopeId)}/segment-presets`)
  }

  async function createSegmentPreset(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: SegmentPreset }>(`${buildBase(scopeType, scopeId)}/segment-presets`, {
      method: 'POST',
      body,
    })
  }

  async function updateSegmentPreset(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/segment-presets/${id}`, { method: 'PUT', body })
  }

  async function deleteSegmentPreset(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/segment-presets/${id}`, { method: 'DELETE' })
  }

  return {
    getPromotions,
    getPromotion,
    createPromotion,
    updatePromotion,
    deletePromotion,
    approvePromotion,
    publishPromotion,
    cancelPromotion,
    schedulePromotion,
    getPromotionStats,
    estimateAudience,
    getCoupons,
    getCoupon,
    createCoupon,
    updateCoupon,
    deleteCoupon,
    toggleCoupon,
    getSegmentPresets,
    createSegmentPreset,
    updateSegmentPreset,
    deleteSegmentPreset,
  }
}
