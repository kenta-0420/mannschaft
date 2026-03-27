export type PaymentItemType = 'ANNUAL_FEE' | 'MONTHLY_FEE' | 'ITEM' | 'DONATION'
export type PaymentMethod = 'STRIPE' | 'MANUAL'
export type PaymentStatus = 'PENDING' | 'PAID' | 'REFUNDED' | 'CANCELLED'
export type ContentGateType = 'POST' | 'FILE' | 'ANNOUNCEMENT' | 'SCHEDULE'

export interface PaymentItemResponse {
  id: number
  name: string
  description: string | null
  type: PaymentItemType
  amount: number
  currency: string
  stripeProductId: string | null
  stripePriceId: string | null
  isActive: boolean
  displayOrder: number
  gracePeriodDays: number
  createdAt: string
}

export interface MemberPaymentResponse {
  id: number
  userId: number
  displayName: string
  paymentItemId: number
  amountPaid: number | null
  currency: string
  paymentMethod: PaymentMethod | null
  status: PaymentStatus | 'UNPAID'
  validFrom: string | null
  validUntil: string | null
  paidAt: string | null
  note: string | null
  receiptUrl: string | null
  createdAt: string | null
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
  contentType: ContentGateType
  contentId: number
  isTitleHidden: boolean
  paymentItem: { id: number; name: string; type: PaymentItemType; amount: number }
}

export interface MyPaymentResponse {
  id: number
  paymentItem: { id: number; name: string; type: PaymentItemType; amount: number }
  scope: { type: 'TEAM' | 'ORGANIZATION'; id: number; name: string }
  amountPaid: number
  status: PaymentStatus
  paidAt: string | null
  validUntil: string | null
}
