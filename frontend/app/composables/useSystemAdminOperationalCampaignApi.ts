/**
 * F09.19.4b 運用型キャンペーン審査 API クライアント（SYSTEM_ADMIN 向け）。
 *
 * <p>設計書: docs/features/F09.19_ad_slot_serving.md §6.1 / §8.5。
 * backend {@code SystemAdminOperationalAdCampaignController}
 * （{@code /api/v1/system-admin/ad-campaigns-operational}）に対応する。
 * 既存メッセージ型審査（{@code /api/v1/system-admin/ad-campaigns/**}）との URL 衝突回避に
 * {@code -operational} サフィックスで分離されている。</p>
 *
 * <p>型は生成型を直接使用する。詳細（審査判断に必要な広告主帰属・クリエイティブ一覧）は
 * F09.19.1b で追加された {@code OperationalCampaignReviewDetailResponse} を用いる。</p>
 */
import type { components } from '~/types/generated'
import type {
  OperationalCampaign,
  OperationalCampaignStatus,
} from '~/composables/useOperationalCampaignApi'

/** 審査詳細（広告主名・scope・クリエイティブ一覧を含む）。 */
export type OperationalCampaignReviewDetail =
  components['schemas']['OperationalCampaignReviewDetailResponse']

interface ApiEnvelope<T> {
  data: T
}

const BASE = '/api/v1/system-admin/ad-campaigns-operational'

export function useSystemAdminOperationalCampaignApi() {
  const api = useApi()

  /**
   * 審査キュー一覧を取得する（status 既定 PENDING_REVIEW）。
   */
  async function listQueue(params?: {
    status?: OperationalCampaignStatus
    page?: number
    size?: number
  }) {
    return api<components['schemas']['PagedResponseOperationalCampaignResponse']>(BASE, {
      params: {
        status: params?.status ?? 'PENDING_REVIEW',
        page: params?.page ?? 0,
        size: params?.size ?? 20,
      },
    })
  }

  /**
   * 審査詳細を取得する（承認/却下判断用の広告主帰属・クリエイティブ一覧を含む）。
   */
  async function getDetail(id: string) {
    return api<ApiEnvelope<OperationalCampaignReviewDetail>>(`${BASE}/${id}`)
  }

  /** PENDING_REVIEW → ACTIVE（承認）。 */
  async function approve(id: string) {
    return api<ApiEnvelope<OperationalCampaign>>(`${BASE}/${id}/approve`, {
      method: 'PATCH',
    })
  }

  /** PENDING_REVIEW → DRAFT（差戻し。理由必須 1〜500 文字）。 */
  async function reject(id: string, reason: string) {
    return api<ApiEnvelope<OperationalCampaign>>(`${BASE}/${id}/reject`, {
      method: 'PATCH',
      body: { reason },
    })
  }

  return { listQueue, getDetail, approve, reject }
}
