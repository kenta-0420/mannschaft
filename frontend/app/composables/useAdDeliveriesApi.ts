import type {
  AdChannelType,
  AdDeliveryHistoryResponse,
} from '~/types/adMessagingCampaign'
import type {
  AdReportResponse,
  CreateAdReportRequest,
} from '~/types/adModeration'

/**
 * F09.17 受信者向け広告配信履歴 API クライアント
 *
 * - GET `/api/v1/me/ad-deliveries` で過去 90 日分の配信履歴を取得（ページング）
 * - DELETE `/api/v1/me/ad-deliveries` で GDPR 自己データ削除（冪等、user_id を NULL に SET）
 * - POST `/api/v1/me/ad-reports` で広告通報送信
 */
export function useAdDeliveriesApi() {
  const api = useApi()

  async function listDeliveries(params?: {
    channelType?: AdChannelType
    cursor?: string
    limit?: number
  }) {
    return api<{ data: AdDeliveryHistoryResponse }>('/api/v1/me/ad-deliveries', {
      params,
    })
  }

  async function deleteAllDeliveries() {
    return api('/api/v1/me/ad-deliveries', {
      method: 'DELETE',
    })
  }

  async function createReport(body: CreateAdReportRequest) {
    return api<{ data: AdReportResponse }>('/api/v1/me/ad-reports', {
      method: 'POST',
      body,
    })
  }

  return {
    listDeliveries,
    deleteAllDeliveries,
    createReport,
  }
}
