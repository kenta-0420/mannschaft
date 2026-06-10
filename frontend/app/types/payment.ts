export type PaymentItemType = 'ANNUAL_FEE' | 'MONTHLY_FEE' | 'ITEM' | 'DONATION' | 'TERM'
export type PaymentMethod = 'STRIPE' | 'MANUAL'
export type PaymentStatus = 'PENDING' | 'PAID' | 'REFUNDED' | 'CANCELLED'
export type ContentGateType = 'POST' | 'FILE' | 'ANNOUNCEMENT' | 'SCHEDULE'

export interface PaymentItemResponse {
  id: number
  meta: {
    name: string
    description: string | null
    type: PaymentItemType
    displayOrder: number
    gracePeriodDays: number
  }
  money: {
    amount: number
    currency: string
  }
  stripe: {
    stripeProductId: string | null
    stripePriceId: string | null
  }
  /**
   * F08.9 P6: 期別課金（TERM）の有効期間。type=TERM 以外は null。
   * BE: payment_items.term_starts_on / term_ends_on（設計書 01 §1.2）
   */
  term: {
    termStartsOn: string | null  // ISO date YYYY-MM-DD
    termEndsOn: string | null    // ISO date YYYY-MM-DD
  } | null
  audit: {
    isActive: boolean
    createdAt: string
    updatedAt: string | null
  }
}

export interface MemberPaymentResponse {
  id: number
  userId: number
  userName: string
  paymentItemId: number
  paymentMethod: PaymentMethod | null
  money: {
    amountPaid: number | null
    currency: string
  }
  statusInfo: {
    status: PaymentStatus | 'UNPAID'
    validFrom: string | null
    validUntil: string | null
    paidAt: string | null
  }
  refund: {
    stripeRefundId: string | null
    stripeReceiptUrl: string | null
    refundedAt: string | null
  }
  audit: {
    note: string | null
    createdAt: string | null
    updatedAt: string | null
  }
}

export interface CheckoutSessionResponse {
  checkoutUrl: string
  sessionId: string
  expiresAt: string
}

export interface PaymentSummaryResponse {
  totalMembers: number
  items: Array<{
    paymentItemId: number
    name: string
    type: PaymentItemType
    amount: number
    paidCount: number
    unpaidCount: number
    totalCollected: number
    isActive: boolean
  }>
}

export interface ContentPaymentGateResponse {
  id: number
  content: {
    contentType: ContentGateType
    contentId: number
    isTitleHidden: boolean
  }
  paymentItem: { id: number; name: string; type: PaymentItemType; amount: number; currency: string }
  audit: {
    createdBy: number
    createdAt: string
  }
}

/**
 * F08.9 P4: ペイウォール判定レスポンス（GET /api/v1/content-gates/check）。
 * BE: GateCheckResponse / GateCheckResponse.RequiredItem に対応。
 */
export interface RequiredPaymentItem {
  paymentItemId: number
  name: string
  faceAmount: number
  satisfied: boolean
}

export interface GateCheckResponse {
  accessible: boolean
  titleHidden: boolean
  requiredItems: RequiredPaymentItem[]
}

export interface MyPaymentResponse {
  id: number
  paymentItem: { id: number; name: string; type: PaymentItemType; amount: number; currency: string }
  scope: { type: 'TEAM' | 'ORGANIZATION'; id: number; name: string }
  money: {
    amountPaid: number
    currency: string
  }
  statusInfo: {
    status: PaymentStatus
    validFrom: string | null
    validUntil: string | null
    paidAt: string | null
  }
  receipt: {
    receiptUrl: string | null
    paymentMethod: PaymentMethod | null
  }
}

/**
 * F08.9 P2: 後見まとめ払い
 * GET /api/v1/me/payable-dues のレスポンス要素
 */
export interface PayableDueItem {
  beneficiaryUserId: number
  beneficiaryDisplayName: string | null
  scopeType: string
  scopeId: number
  scopeName: string | null
  paymentItemId: number
  itemName: string
  faceAmount: number
  payerSurcharge: number
  totalCharge: number
  dueDate: string | null
  kind: 'ONE_TIME' | 'RECURRING' | 'TERM'
  authorizationVia: 'SELF' | 'GUARDIAN' | 'GUARDIAN_PROXY' | 'PROXY_GRANT'
  alreadyPaid: boolean
  paidByUserId: number | null
  paidByDisplayName: string | null
  paidAt: string | null
}

export interface PayableDuesResponse {
  items: PayableDueItem[]
}

export interface BulkCheckoutRequest {
  beneficiaryUserId: number
  paymentItemIds: number[]
}

export interface BulkCheckoutResultItem {
  paymentItemId: number
  status: 'CHECKED_OUT' | 'SKIPPED'
  skipReason: 'ALREADY_PAID' | 'NOT_AUTHORIZED' | 'CONNECT_NOT_READY' | 'ITEM_NOT_FOUND' | 'ERROR' | null
}

export interface BulkCheckoutResponse {
  results: BulkCheckoutResultItem[]
}
