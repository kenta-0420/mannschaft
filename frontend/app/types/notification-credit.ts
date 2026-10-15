/**
 * F09.13 通知プリペイドクレジット関連型定義。
 */

/** クレジット購入のステータス */
export type CreditPurchaseStatus = 'PENDING' | 'PAID' | 'CANCELLED' | 'REFUNDED'

/** 組織の通知クレジット残高 */
export interface NotificationCreditBalance {
  /** 今月の無料枠使用通数 */
  freeUsedThisMonth: number
  /** 月間無料枠（固定 10,000 通） */
  freeQuota: number
  /** クレジット残高（マイナスあり） */
  creditBalance: number
  /** 猶予期間中フラグ */
  inGracePeriod: boolean
  /** 猶予期間終了日時（ISO 8601） */
  gracePeriodEndsAt: string | null
  /** 猶予期間中の累積負債通数 */
  gracePeriodDebt: number
}

/** 通知クレジットパッケージ */
export interface NotificationCreditPackage {
  id: number
  name: string
  credits: number
  priceJpy: number
}

/** 通知クレジット購入履歴 */
export interface NotificationCreditPurchase {
  id: number
  packageName: string
  creditsGranted: number
  priceJpy: number
  paymentStatus: CreditPurchaseStatus
  paidAt: string | null
  expiresAt: string | null
}

/** Checkout セッション作成レスポンス */
export interface NotificationCreditCheckoutResponse {
  checkoutUrl: string
  sessionId: string
}
