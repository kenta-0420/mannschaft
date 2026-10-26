/**
 * F09.17 Phase 11-c-4 — SYSTEM_ADMIN 用 広告キャンペーン審査 API composable。
 *
 * <p>backend `SystemAdminAdCampaignController`
 * (`/api/v1/system-admin/ad-campaigns/...`) および将来の通報処理 Controller
 * (`/api/v1/system-admin/ad-user-reports`) に対応する。
 * SYSTEM_ADMIN 権限を持つユーザーのみが利用する想定。</p>
 *
 * <p>キャンペーンの詳細プレビュー (`getCampaignForReview`) は backend に
 * 未公開のため、暫定的に `/api/v1/system-admin/ad-campaigns/{id}` 経路を呼ぶ。
 * backend 側で正式 endpoint が追加されたらここを差し替える。</p>
 */
import type { PagedResponse } from '~/types/api'
import type {
  AdCampaignModerationLog,
  AdReviewQueueItem,
  AdReviewQueueListParams,
  AdUserReport,
  ApproveCampaignRequest,
  BlockCampaignRequest,
} from '~/types/adModeration'
import type { AdMessagingCampaign } from '~/types/adMessagingCampaign'
import type { AdReportReason } from '~/types/adPreferences'

/** SYSTEM_ADMIN 通報一覧の絞り込み条件 */
export interface AdUserReportListParams {
  reason?: AdReportReason
  status?: 'NEW' | 'REVIEWING' | 'RESOLVED' | 'DISMISSED'
  page?: number
  size?: number
}

/**
 * SYSTEM_ADMIN 視点での 1 キャンペーン審査詳細。
 *
 * <p>本体 ({@link AdMessagingCampaign}) に、自動 NG 検知された語句一覧と
 * モデレーションログを束ねた表示用構造。`getCampaignForReview()` で取得する。</p>
 */
export interface AdReviewCampaignDetail {
  campaign: AdMessagingCampaign
  detectedNgWords: string[]
  moderationLogs: AdCampaignModerationLog[]
}

/**
 * SYSTEM_ADMIN 広告キャンペーン審査 API composable。
 */
export function useSystemAdminAdCampaignApi() {
  const api = useApi()

  /**
   * 審査キュー一覧を取得する。
   *
   * <p>backend は `{moderation_status IN (PENDING, AUTO_FLAGGED, AUTO_PASSED)}` の
   * キャンペーンを返す。ページング対応。</p>
   */
  async function listReviewQueue(params?: AdReviewQueueListParams) {
    return api<PagedResponse<AdReviewQueueItem>>(
      '/api/v1/system-admin/ad-campaigns/review-queue',
      {
        params: {
          page: params?.page ?? 0,
          size: params?.size ?? 20,
        },
      },
    )
  }

  /**
   * 審査対象キャンペーンの詳細を取得する。
   *
   * <p>全チャネル本文・ターゲットセグメント・モデレーション履歴を含む。</p>
   */
  async function getCampaignForReview(campaignId: string) {
    return api<{ data: AdReviewCampaignDetail }>(
      `/api/v1/system-admin/ad-campaigns/${campaignId}`,
    )
  }

  /**
   * キャンペーンを承認する。`HTTP 204 No Content`。
   *
   * <p>backend は 204 No Content を返すため戻り値の型は使用しない。</p>
   */
  async function approveCampaign(
    campaignId: string,
    body?: ApproveCampaignRequest,
  ): Promise<void> {
    await api(`/api/v1/system-admin/ad-campaigns/${campaignId}/approve`, {
      method: 'POST',
      body: body ?? {},
    })
  }

  /**
   * キャンペーンをブロックする。理由は必須。`HTTP 204 No Content`。
   */
  async function blockCampaign(
    campaignId: string,
    body: BlockCampaignRequest,
  ): Promise<void> {
    await api(`/api/v1/system-admin/ad-campaigns/${campaignId}/block`, {
      method: 'POST',
      body,
    })
  }

  /**
   * ユーザー通報の一覧を取得する。
   *
   * <p>3 件以上集まったキャンペーンには {@code autoSuspendCandidate=true} が付く。</p>
   */
  async function listUserReports(params?: AdUserReportListParams) {
    return api<PagedResponse<AdUserReport>>('/api/v1/system-admin/ad-user-reports', {
      params: {
        reason: params?.reason,
        status: params?.status,
        page: params?.page ?? 0,
        size: params?.size ?? 20,
      },
    })
  }

  /**
   * 通報の対応状態を遷移させる（F09.19.9）。
   *
   * <p>NEW→REVIEWING→RESOLVED/DISMISSED。不正遷移は 409 / AD_027。</p>
   */
  async function updateUserReportStatus(
    reportId: string,
    status: AdUserReport['status'],
  ) {
    return api<{ data: AdUserReport }>(
      `/api/v1/system-admin/ad-user-reports/${reportId}/status`,
      {
        method: 'PATCH',
        body: { status },
      },
    )
  }

  return {
    listReviewQueue,
    getCampaignForReview,
    approveCampaign,
    blockCampaign,
    listUserReports,
    updateUserReportStatus,
  }
}
