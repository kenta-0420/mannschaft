/**
 * F09.17 Phase 11-c-4 — 広告主向けキャンペーン パフォーマンスレポート API composable。
 *
 * <p>backend `AdvertiserMessagingCampaignController` の
 * {@code GET /report} および {@code GET /report.csv} に対応する。</p>
 *
 * <p><b>11-c-3 との協調</b>: 当初予定では本機能は 11-c-3 の
 * `useAdMessagingCampaignApi` 配下に置く想定だったが、11-c-3 着手前に
 * 11-c-4（本タスク）でレポートページが必要となったため、別ファイルとして先行追加する。
 * 11-c-3 マージ後は `useAdMessagingCampaignApi` へ吸収統合してよい。</p>
 */
import type { AdCampaignReport } from '~/types/adMessagingCampaign'

/** レポート取得時の期間絞り込み条件 */
export interface AdCampaignReportRange {
  /** 開始日 (YYYY-MM-DD) */
  from?: string
  /** 終了日 (YYYY-MM-DD) */
  to?: string
}

/**
 * 広告主向けレポート API composable。
 *
 * <p>呼び出し側が `organizationId` を持っている前提（multi-tenant 分離）。
 * organizationId は backend が SecurityContext から取得する設計のためクエリには載せない。</p>
 */
export function useAdMessagingCampaignReportApi() {
  const api = useApi()

  /**
   * 期間指定でレポート JSON を取得する。
   *
   * @param campaignId キャンペーン ID (UUID)
   * @param range from / to (省略可。未指定時は backend デフォルト=過去 30 日)
   */
  async function getCampaignReport(campaignId: string, range?: AdCampaignReportRange) {
    return api<{ data: AdCampaignReport }>(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/report`,
      {
        params: {
          from: range?.from,
          to: range?.to,
        },
      },
    )
  }

  /**
   * CSV エクスポートを Blob として取得する。
   *
   * <p>backend は `Content-Type: text/csv` を返す。PII（user_id, email, name）は
   * 一切含まれない（{@code AdReportCsvWriter} で固定スキーマ）。</p>
   */
  async function exportReportCsv(campaignId: string, range?: AdCampaignReportRange) {
    return api(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/report.csv`,
      {
        params: {
          from: range?.from,
          to: range?.to,
        },
        responseType: 'blob' as const,
      },
    ) as Promise<Blob>
  }

  return {
    getCampaignReport,
    exportReportCsv,
  }
}
