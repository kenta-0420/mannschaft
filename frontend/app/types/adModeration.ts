/**
 * F09.17 広告モデレーション — 受信者向け通報型
 *
 * 注: このファイルは Phase 11-c-1（基盤）と並行作成された雛形。
 * SYSTEM_ADMIN 向け完全型は 11-c-4 で別途定義される。
 */

/**
 * 通報理由コード（景品表示法・サービス品質維持の観点）
 */
export type AdReportReasonCode =
  | 'OFFENSIVE' // 不快・差別的
  | 'MISLEADING' // 誇大・誤解を招く
  | 'IRRELEVANT' // 自分に無関係
  | 'INAPPROPRIATE' // 不適切（性的表現等）
  | 'SPAM' // 同一広告主からの過剰送信
  | 'OTHER' // その他

/**
 * 通報作成リクエスト（POST /api/v1/me/ad-reports）
 */
export interface CreateAdReportRequest {
  /** 通報対象のキャンペーン ID（UUIDv7） */
  campaignId: string
  /** 通報対象のチャネル */
  channelType: 'ANNOUNCEMENT' | 'EMAIL' | 'PUSH' | 'BANNER'
  /** 通報理由コード */
  reasonCode: AdReportReasonCode
  /** 自由記述（任意、最大 1000 文字） */
  comment?: string
}

export interface AdReportResponse {
  id: string
  campaignId: string
  reasonCode: AdReportReasonCode
  createdAt: string
}
