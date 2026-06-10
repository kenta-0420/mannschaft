/**
 * F22.1 市の謝礼決済 FE 型定義。
 *
 * BE 実 DTO（camelCase）と 1:1 で対応させる（手書き snake 禁止・casing 前科回避）。
 * 一次ソース:
 *   - payment/connect/dto/OnboardingLinkRequest.java
 *   - payment/connect/dto/OnboardingLinkResponse.java
 *   - payment/connect/dto/ConnectStatusResponse.java
 *   - payment/connect/ScopeKind.java / OnboardingStatus.java / EscrowStatus.java
 *   - payment/escrow/dto/RefundRequest.java
 *   - payment/escrow/dto/RefundResponse.java
 *   - payment/escrow/FeeBearer.java
 *
 * 注意（generated 型を使わない理由）:
 *   生成型 `RefundRequest`（types/generated/index.ts）はチケット課金ドメインの
 *   `{refundType, refundAmount, adjustedRemaining, note}` と**スキーマ名が衝突**しており、
 *   F22.1 エスクロー返金の `{amount, feeBearer, reason, reasonDetail}` とは別物になる。
 *   そのため返金リクエストは本ファイルで Java record と 1:1 に手書きする。
 *   onboarding/status/RefundResponse の生成型は正しいが、参照の一貫性のため本ファイルに集約する。
 */

/** 受領/支払主体の種別（payment/connect/ScopeKind.java）。 */
export type ScopeKind = 'USER' | 'TEAM' | 'ORG'

/** Connect アカウントの onboarding 状態（payment/connect/OnboardingStatus.java）。 */
export type OnboardingStatus = 'PENDING' | 'ONBOARDING' | 'READY' | 'RESTRICTED' | 'DISABLED'

/**
 * エスクロー取引の状態（payment/escrow/EscrowStatus.java・全9値と 1:1）。
 *   - PENDING_CONFIRMATION: PI 作成済・札主の Stripe.js confirm 待ち（clientSecret が非 null で返る状態）。
 *   - DEFERRED: 成立〜役務日が7日超で与信を立てず、完了時に即時払いへフォールバックする予定。
 *   - AUTHORIZED 以降: 与信確定済（再 confirm 不要・clientSecret は null）。
 */
export type EscrowStatus =
  | 'PENDING_CONFIRMATION'
  | 'DEFERRED'
  | 'AUTHORIZED'
  | 'HELD'
  | 'CAPTURED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED'
  | 'CANCELLED'
  | 'DISPUTED'

/** 返金時の決済手数料の負担者（payment/escrow/FeeBearer.java）。 */
export type FeeBearer = 'PAYER' | 'PAYEE'

/**
 * Connect onboarding リンク発行リクエスト（OnboardingLinkRequest.java）。
 * scopeId は TEAM/ORG 時必須・USER 時は無視され本人に固定される。
 */
export interface OnboardingLinkRequest {
  scopeKind: ScopeKind
  scopeId?: number | null
  returnUrl: string
  refreshUrl: string
}

/** Connect onboarding リンク発行レスポンス（OnboardingLinkResponse.java）。 */
export interface OnboardingLinkResponse {
  connectAccountId: string
  stripeAccountId: string
  onboardingStatus: OnboardingStatus
  onboardingUrl: string
  expiresAt: string
}

/** Connect 状態照会レスポンス（ConnectStatusResponse.java）。 */
export interface ConnectStatusResponse {
  connectAccountId: string
  scopeKind: ScopeKind
  scopeId: number | null
  onboardingStatus: OnboardingStatus
  chargesEnabled: boolean
  payoutsEnabled: boolean
  requirementsDue: string[]
}

/**
 * エスクロー返金リクエスト（payment/escrow/dto/RefundRequest.java）。
 *   - amount: 精算額（transferAmount ベース・null=全額）。
 *   - feeBearer: 手数料負担者（null=既定 PAYER）。
 *   - reason / reasonDetail: 任意。
 */
export interface MarketRefundRequest {
  amount?: number | null
  feeBearer?: FeeBearer | null
  reason?: string | null
  reasonDetail?: string | null
}

/** エスクロー返金レスポンス（payment/escrow/dto/RefundResponse.java）。 */
export interface MarketRefundResponse {
  escrowId: string
  status: EscrowStatus
  refundedAmount: number
  residualAmount: number
}

/**
 * 札主の決済確認 / エスクロー状態照会レスポンス
 * （payment/escrow/dto/RecruitmentPaymentResponse.java と 1:1）。
 *
 * 金額はすべて最小通貨単位（円整数・BE は long）。
 *   - clientSecret: 支払者本人 × PENDING_CONFIRMATION 時のみ非 null（Stripe.js で confirm する）。
 *   - escrowTransactionId: エスクロー取引 ID（UUID 文字列）。返金 EP の {id} に渡す。
 *   - faceAmount: 額面（受取側が設定した謝礼の元値）。
 *   - chargeAmount: 課金額（支払者への実請求額＝額面 + 支払手数料）。
 *   - applicationFeeAmount: Mannschaft 徴収手数料。
 */
export interface RecruitmentPaymentResponse {
  clientSecret: string | null
  escrowTransactionId: string
  status: EscrowStatus
  faceAmount: number
  chargeAmount: number
  applicationFeeAmount: number
}
