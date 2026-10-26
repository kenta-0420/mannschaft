/**
 * F08.9 P5 継続課金（membership subscription）の手書き型定義。
 *
 * BE DTO（MembershipSubscriptionListItemResponse / MembershipSubscriptionResponse /
 * SetupIntentResponse / PaymentMethodResponse）と camelCase 1:1 で対応する。
 * 生成型（types/generated）が整備されたら段階移行する。
 *
 * 設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4 / 04_ui_i18n.md §2
 */

/** 継続課金の状態。 */
export type MembershipSubscriptionStatus =
  | 'PENDING'
  | 'ACTIVE'
  | 'PAST_DUE'
  | 'CANCELLED'
  | 'EXPIRED'

/** 受領主体の種別。 */
export type SubscriptionScopeKind = 'TEAM' | 'ORG'

/** 課金周期。 */
export type SubscriptionBillingInterval = 'MONTHLY' | 'YEARLY'

/**
 * 継続課金一覧アイテム（GET /api/v1/me/membership-subscriptions）。
 * BE: MembershipSubscriptionListItemResponse に 1:1 対応。
 */
export interface MembershipSubscriptionListItem {
  /** 継続課金 ID（UUID 文字列）。 */
  id: string
  /** 会費項目 ID。 */
  paymentItemId: number
  /** 会費項目名（加入時に固定）。 */
  itemName: string | null
  /** 受益者ユーザー ID。 */
  beneficiaryUserId: number
  /** 受益者の表示名（名前解決済み）。 */
  beneficiaryDisplayName: string | null
  /** 払い手ユーザー ID。 */
  payerUserId: number
  /** 受領主体の種別。 */
  scopeKind: SubscriptionScopeKind | null
  /** 受領主体 ID。 */
  scopeId: number | null
  /** 状態。 */
  status: MembershipSubscriptionStatus | null
  /** 課金周期。 */
  billingInterval: SubscriptionBillingInterval | null
  /** 額面（円整数）。 */
  faceAmount: number | null
  /** 通貨。 */
  currency: string | null
  /** 次回課金日（スキップ中は再開予定日・LocalDate "YYYY-MM-DD"）。 */
  nextBillingDate: string | null
  /** 利用期限（current_period_end・LocalDate "YYYY-MM-DD"）。 */
  validUntil: string | null
  /** 期末解約予約フラグ。 */
  cancelAtPeriodEnd: boolean | null
  /** スキップ中の再開予定日（null=スキップなし・LocalDate "YYYY-MM-DD"）。 */
  skipUntil: string | null
}

/**
 * 継続課金レスポンス（subscribe / cancel / skip / resume）。
 * BE: MembershipSubscriptionResponse に 1:1 対応。
 */
export interface MembershipSubscriptionResponse {
  id: string
  paymentItemId: number
  beneficiaryUserId: number
  payerUserId: number
  scopeKind: SubscriptionScopeKind | null
  scopeId: number | null
  stripeSubscriptionId: string | null
  billingInterval: SubscriptionBillingInterval | null
  billingAnchorDay: number | null
  status: MembershipSubscriptionStatus | null
  feePolicyKey: string | null
  faceAmount: number | null
  currency: string | null
  currentPeriodStart: string | null
  currentPeriodEnd: string | null
  cancelAtPeriodEnd: boolean | null
  cancelledAt: string | null
  skipUntil: string | null
  createdAt: string | null
}

/**
 * SetupIntent 作成レスポンス（POST /api/v1/me/payment-methods/setup-intent）。
 * BE: SetupIntentResponse に 1:1 対応。clientSecret は次 PR の Stripe.js confirm で使用する。
 */
export interface SetupIntentResponse {
  setupIntentId: string | null
  clientSecret: string | null
  status: string | null
}

/**
 * 支払い方法 confirm 結果（POST /api/v1/me/payment-methods/confirm）。
 * BE: PaymentMethodResponse に 1:1 対応。
 */
export interface PaymentMethodResponse {
  defaultPaymentMethod: string | null
  saved: boolean
}

/** 支払い方法 confirm リクエスト。 */
export interface PaymentMethodConfirmRequest {
  paymentMethodId: string
}

/** 継続課金 加入（subscribe）リクエスト。 */
export interface SubscribeRequest {
  beneficiaryUserId: number
  billingAnchorDay?: number
  idempotencyKey?: string
}
